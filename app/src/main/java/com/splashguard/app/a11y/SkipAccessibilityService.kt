package com.splashguard.app.a11y

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.splashguard.app.core.Prefs
import java.util.ArrayDeque

/**
 * 冷启动后自动点「跳过」。
 *
 * ## 触发面
 *
 * 只监听 `TYPE_WINDOW_STATE_CHANGED` / `TYPE_WINDOWS_CHANGED`（遵守 AGENTS.md 红线，
 * **不监听 `TYPE_WINDOW_CONTENT_CHANGED`**），窗口变化后启动 [PollSchedule] 轮询：
 * 前 600ms 每 150ms，600ms–3s 每 350ms，之后每 600ms；单窗口最多 12 次，
 * 上次扫描慢时按耗时退避，直到点中或窗口结束。
 *
 * ## v0.1.8 性能与延迟修复
 *  - **扫描门控**：只扫当前跟踪的 App；桌面、系统 UI、设置、搜索框、输入法直接拒绝。
 *    诊断报告显示 v0.1.7 有约三成扫描发生在这些界面上，是发热主因之一。
 *  - **文本查找优先**：先用系统 `findAccessibilityNodeInfosByText` 定位「跳过」候选，
 *    命中后才做可见性 / 尺寸 / 可点击祖先校验；BFS 降为 80 节点兜底，
 *    不再对 150 节点逐节点做 text + contentDescription 的数百次 binder 调用。
 *  - **窗口可延长**：同包名窗口事件把结束时间延后（上限 10 秒），
 *    覆盖 5–8 秒才渲染的跳过按钮。
 *  - **事件即时扫描不被轮询闸挡住**：事件自带 source 时走独立的时间戳。
 *  - **每次扫描都记日志**，并记录拒绝原因、扫描耗时、点击 action 耗时。
 *
 * ## 防误点红线（不变）
 *  1. 只在窗口切换后的轮询窗口内工作
 *  2. 只匹配「跳过 / 跳过广告 / 关闭广告 / Skip」这类文本（见 [SkipLabelMatcher]）
 *  3. 文字节点和最终点击的父节点都要过尺寸闸
 *  4. 每个窗口最多点一次，两次点击至少间隔 2 秒
 *  5. 不处理本应用、桌面和系统 UI 的窗口
 *  6. 不点击「摇一摇 / 扭一扭 / 点击查看」这类互动广告文案
 */
class SkipAccessibilityService : AccessibilityService() {

    private enum class Trigger { POLL, EVENT_SOURCE }

    private val handler = Handler(Looper.getMainLooper())
    private lateinit var prefs: Prefs

    private val tracker = SkipWindowTracker(
        pollWindowMs = PollSchedule.WINDOW_MS,
        samePkgRestartMs = SAME_PKG_RESTART_MS,
        cooldownMs = COOLDOWN_MS,
    )

    private var screenWidth = 1080
    private var screenHeight = 2400
    private var homePkg: String? = null
    private var lastScanAt = 0L
    private var lastEventSourceScanAt = 0L
    /** 本次窗口已经尝试了几次轮询扫描（用于 MAX_SCANS 上限） */
    private var windowScanAttempts = 0
    private var lastBfsAt = 0L
    private var lastEnglishTextAt = 0L
    /** 轮询是否已经排上队；RESTART 时不重复 reset，避免事件风暴饿死扫描 */
    private var pollActive = false
    /** 复用当前窗口的根节点；窗口切换/配置变化时置空 */
    private var cachedRoot: AccessibilityNodeInfo? = null

    /** 抢跑任务：窗口出现后逐次退避地重试，直到点中或窗口结束 */
    private val pollTask = object : Runnable {
        override fun run() {
            pollActive = false
            val now = SystemClock.elapsedRealtime()
            if (!tracker.canClick(now)) return
            // 硬上限：即使同包名事件反复重启轮询，也不超过 12 次
            if (windowScanAttempts >= PollSchedule.MAX_SCANS) return

            windowScanAttempts++
            val scanMs = tryClick(now, Trigger.POLL)

            val after = SystemClock.elapsedRealtime()
            if (!tracker.canClick(after)) return

            val elapsed = tracker.elapsed(after)
            // 上次扫描慢就退避，避免「8 秒窗口 × 150ms」把占空比推高
            val delay = PollSchedule.adaptiveDelay(elapsed, scanMs)
            // 用 canClick(now+delay) 判断是否还值得排下一轮：
            // tracker 可能因同包名事件延长了窗口，不能再用固定 8 秒判断。
            if (tracker.canClick(after + delay)) {
                pollActive = true
                handler.postDelayed(this, delay)
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        prefs = Prefs(this)
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        updateScreenSize()
        homePkg = resolveHomePackage()

        // 遵守 AGENTS.md 红线：不监听 TYPE_WINDOW_CONTENT_CHANGED。
        // Android 16 / 各家 ROM 对「App 冷启动」用 STATE 还是 WINDOWS 并不一致，所以两者都监听；
        // 晚出现的按钮由轮询覆盖。
        serviceInfo = serviceInfo?.apply {
            eventTypes = AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED or
                AccessibilityEvent.TYPE_WINDOWS_CHANGED
            feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC
            notificationTimeout = 100
        }

        A11yState.onConnected()
        A11yState.log("connected screen=${screenWidth}x$screenHeight home=$homePkg")
        Log.i(TAG, "connected, screen=${screenWidth}x$screenHeight home=$homePkg")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        event ?: return
        val type = event.eventType
        if (type != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED &&
            type != AccessibilityEvent.TYPE_WINDOWS_CHANGED
        ) return

        val rawPkg = event.packageName?.toString()
            ?: event.source?.packageName?.toString()
            ?: if (type == AccessibilityEvent.TYPE_WINDOWS_CHANGED) rootPkg() else null
        val pkg = rawPkg ?: return

        if (pkg == packageName || pkg == homePkg) {
            // 离开目标 App（回桌面 / 打开本 App）：结束当前窗口并真正取消已排队的轮询。
            // v0.1.7 只 reset 状态机，队列里的一次 poll 仍会执行。
            cancelPoll()
            tracker.reset()
            cachedRoot = null
            return
        }
        if (pkg in ScanGate.IGNORED_PKGS) {
            // 系统 UI / 设置 / 权限弹窗可能盖住了目标窗口，下一次重新取根节点
            cachedRoot = null
            return
        }

        val now = SystemClock.elapsedRealtime()
        A11yState.onEvent(type, pkg)
        A11yState.log("event ${A11yState.eventTypeName(type)} pkg=$pkg")

        when (tracker.onWindowEvent(pkg, now)) {
            SkipWindowTracker.Action.START_POLL -> {
                A11yState.lastWindowAction = "START"
                A11yState.onWindowStart(pkg)
                startPoll(restart = true)
            }
            SkipWindowTracker.Action.RESTART_POLL -> {
                A11yState.lastWindowAction = "RESTART"
                // 同包名也可能换了 Activity，旧根节点可能已经失效
                cachedRoot = null
                startPoll(restart = false)
            }
            SkipWindowTracker.Action.NONE -> {
                A11yState.lastWindowAction = "NONE"
            }
        }

        // 事件自带的 source 可能在跳过按钮刚渲染时就已包含它：先即时查一次。
        // 走独立时间戳，不被轮询的 80ms 闸挡住（v0.1.7 这里会漏掉最快路径）。
        event.source?.let { source ->
            tryClick(now, Trigger.EVENT_SOURCE, rootOverride = source)
        }
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        updateScreenSize()
        cachedRoot = null
        A11yState.log("configuration changed screen=${screenWidth}x$screenHeight")
    }

    override fun onInterrupt() {
        A11yState.log("interrupted")
    }

    override fun onUnbind(intent: Intent?): Boolean {
        A11yState.onDisconnected("unbind")
        cancelPoll()
        tracker.reset()
        cachedRoot = null
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        A11yState.onDisconnected("destroy")
        cancelPoll()
        tracker.reset()
        cachedRoot = null
        super.onDestroy()
    }

    private fun cancelPoll() {
        handler.removeCallbacks(pollTask)
        pollActive = false
    }

    /**
     * @param restart true 表示新窗口，必须清掉旧根节点并从 150ms 后重新计时；
     *                false 表示同包名窗口事件，只要轮询还活着就不打断它。
     */
    private fun startPoll(restart: Boolean) {
        if (!restart && pollActive) return
        handler.removeCallbacks(pollTask)
        cachedRoot = null
        if (restart) {
            windowScanAttempts = 0
            lastBfsAt = 0L
            lastEnglishTextAt = 0L
        }
        pollActive = true
        handler.postDelayed(pollTask, PollSchedule.INITIAL_DELAY_MS)
    }

    private fun updateScreenSize() {
        val dm = resources.displayMetrics
        screenWidth = dm.widthPixels.coerceAtLeast(1)
        screenHeight = dm.heightPixels.coerceAtLeast(1)
    }

    private fun rootPkg(): String? =
        runCatching { rootInActiveWindow?.packageName?.toString() }.getOrNull()

    /** @return 本次查找耗时（毫秒）；没有实际扫描时返回 0，供轮询自适应退避使用 */
    private fun tryClick(now: Long, trigger: Trigger, rootOverride: AccessibilityNodeInfo? = null): Long {
        if (!tracker.canClick(now)) return 0L
        // 同一时刻重复扫树没有意义；事件快路径用独立时间戳，避免被轮询挡掉
        if (rootOverride == null) {
            if (now - lastScanAt < SCAN_MIN_INTERVAL_MS) return 0L
            lastScanAt = now
        } else {
            if (now - lastEventSourceScanAt < EVENT_SOURCE_MIN_INTERVAL_MS) return 0L
            lastEventSourceScanAt = now
        }

        val scanStart = SystemClock.elapsedRealtime()
        // 复用当前窗口的根节点：refresh() 比每次 rootInActiveWindow() 的 IPC 便宜；
        // 根节点失效（窗口被销毁）时刷新会返回 false，再重新取。
        val root = rootOverride
            ?: cachedRoot?.takeIf { runCatching { it.refresh() }.getOrDefault(false) }
            ?: runCatching { rootInActiveWindow }.getOrNull()
            ?: return 0L
        val rootPkg = root.packageName?.toString()

        val verdict = ScanGate.shouldScan(rootPkg, tracker.windowPkg, packageName, homePkg)
        if (verdict != ScanGate.Verdict.SCAN) {
            val outcome = gateOutcome(verdict)
            A11yState.onScanRejected(rootPkg, trigger.name, outcome)
            A11yState.log("skip-scan pkg=${rootPkg ?: "-"} t=${trigger.name} reason=${outcome.label}")
            return 0L
        }
        if (rootOverride == null) cachedRoot = root

        A11yState.lastScanShake = false
        val result = findSkip(root, now)
        val scanMs = SystemClock.elapsedRealtime() - scanStart
        if (result.target == null) {
            A11yState.onScan(
                result.visited, false, scanMs, rootPkg, trigger.name, result.outcome,
            )
            A11yState.log(
                "scan pkg=$rootPkg t=${trigger.name} visited=${result.visited} " +
                    "ms=$scanMs result=${result.outcome.label} shake=${result.sawInteractive}"
            )
            return scanMs
        }

        val targetPkg = result.target.packageName?.toString() ?: rootPkg ?: "unknown"
        // 同一个 App 10 秒内只点一次：防止点击后 App 内部 Activity 切换被当成新开屏，
        // 又点到下一个页面里的「关闭」类控件。
        if (prefs.lastSkipPkg() == targetPkg &&
            System.currentTimeMillis() - prefs.lastSkipAt() < SAME_PKG_CLICK_COOLDOWN_MS
        ) {
            A11yState.log("skip click cooldown pkg=$targetPkg t=${trigger.name}")
            return scanMs
        }

        val actionStart = SystemClock.elapsedRealtime()
        val clicked = runCatching {
            result.target.performAction(AccessibilityNodeInfo.ACTION_CLICK)
        }.getOrDefault(false)
        val actionMs = SystemClock.elapsedRealtime() - actionStart
        A11yState.lastClickActionMs = actionMs

        if (clicked) {
            tracker.onClicked(SystemClock.elapsedRealtime())
            prefs.bumpSkipCount(targetPkg)
            A11yState.onClick(targetPkg)
            A11yState.onScan(
                result.visited, true, scanMs, rootPkg, trigger.name, A11yState.ScanOutcome.CLICKED,
            )
            A11yState.log(
                "CLICK pkg=$targetPkg t=${trigger.name} visited=${result.visited} " +
                    "scanMs=$scanMs actionMs=$actionMs"
            )
            cancelPoll()
            Log.i(TAG, "clicked skip in $targetPkg (scan=${scanMs}ms action=${actionMs}ms)")
        } else {
            A11yState.onScan(
                result.visited, true, scanMs, rootPkg, trigger.name,
                A11yState.ScanOutcome.CLICK_FAILED,
            )
            A11yState.log(
                "click failed pkg=$targetPkg t=${trigger.name} visited=${result.visited} " +
                    "scanMs=$scanMs actionMs=$actionMs"
            )
            Log.w(TAG, "click failed in $targetPkg (action=${actionMs}ms)")
        }
        return scanMs
    }

    private fun gateOutcome(v: ScanGate.Verdict): A11yState.ScanOutcome = when (v) {
        ScanGate.Verdict.SELF -> A11yState.ScanOutcome.SELF_WINDOW
        ScanGate.Verdict.HOME -> A11yState.ScanOutcome.HOME_WINDOW
        ScanGate.Verdict.IGNORED -> A11yState.ScanOutcome.IGNORED_WINDOW
        ScanGate.Verdict.WRONG_ROOT -> A11yState.ScanOutcome.WRONG_ROOT
        ScanGate.Verdict.NO_PKG -> A11yState.ScanOutcome.NO_PKG
        ScanGate.Verdict.SCAN -> A11yState.ScanOutcome.NONE_FOUND
    }

    private data class FindResult(
        val target: AccessibilityNodeInfo?,
        val visited: Int,
        val outcome: A11yState.ScanOutcome,
        /** BFS 是否因为达到节点上限而被截断 */
        val truncated: Boolean = false,
        /** 本次扫描是否看到「摇一摇 / 扭一扭 / 点击查看」文案（只诊断，不点击） */
        val sawInteractive: Boolean = false,
    )

    /**
     * 三段式扫描，按命中概率从高到低：
     *  1. 系统文本查找「跳过」—— 绝大多数国产开屏在这一步命中；
     *  2. 快速 BFS 80 节点兜底 —— 覆盖只写在 contentDescription、或「关闭广告」等文案；
     *  3. 系统文本查找「skip」—— 只在前面都没命中时再查，减少英文 App 之外的全树遍历。
     *
     * v0.1.7 的顺序相反：先 BFS 150 节点，再对截断的树做 4 次全树文本查询，
     * 单次扫描平均 428ms，是发热和点击延迟的主要来源。
     */
    private fun findSkip(root: AccessibilityNodeInfo, now: Long): FindResult {
        var sawInteractive = false
        val zh = findSkipByText(root, TEXT_ZH)
        if (zh.sawInteractive) {
            sawInteractive = true
            A11yState.onShakeSeen()
        }
        if (zh.target != null) return zh

        var visited = zh.visited
        var outcome = zh.outcome
        var truncated = false

        // 第一次扫描做一次 BFS 兜底（覆盖英文 / contentDescription），之后每 1.2 秒一次；
        // 不是每次扫描都做，避免未命中路径比 v0.1.7 更耗。
        val allowBfs = lastBfsAt == 0L || now - lastBfsAt >= BFS_INTERVAL_MS
        if (allowBfs) {
            lastBfsAt = now
            val bfs = findSkipBfs(root, QUICK_MAX_NODES)
            if (bfs.sawInteractive) {
                sawInteractive = true
                A11yState.onShakeSeen()
            }
            visited += bfs.visited
            if (outcome == A11yState.ScanOutcome.NONE_FOUND) outcome = bfs.outcome
            truncated = bfs.truncated
            if (bfs.target != null) return bfs.copy(sawInteractive = sawInteractive)
        }

        // 「关闭广告」和英文只在窗口 1 秒后、每 2 秒兜底一次，避免每次扫描都做全树查询
        val elapsed = tracker.elapsed(now)
        val allowFallbackText = elapsed >= ENGLISH_TEXT_START_MS &&
            now - lastEnglishTextAt >= ENGLISH_TEXT_INTERVAL_MS
        if (allowFallbackText) {
            lastEnglishTextAt = now
            for (query in FALLBACK_TEXT_QUERIES) {
                val fb = findSkipByText(root, query)
                if (fb.sawInteractive) {
                    sawInteractive = true
                    A11yState.onShakeSeen()
                }
                visited += fb.visited
                if (outcome == A11yState.ScanOutcome.NONE_FOUND) outcome = fb.outcome
                if (fb.target != null) return fb.copy(sawInteractive = sawInteractive)
            }
        }

        return FindResult(null, visited, outcome, truncated, sawInteractive)
    }

    private fun findSkipByText(root: AccessibilityNodeInfo, query: String): FindResult {
        val found = runCatching {
            root.findAccessibilityNodeInfosByText(query)
        }.getOrNull()
        if (found.isNullOrEmpty()) {
            return FindResult(null, 0, A11yState.ScanOutcome.NONE_FOUND)
        }

        var visited = 0
        var rejection: A11yState.ScanOutcome? = null
        var sawInteractive = false
        for (node in found) {
            visited++
            // text 和 contentDescription 都要看：有的广告把「跳过」写在 text，把「摇一摇」写在
            // contentDescription，反过来也常见；只取其一会造成漏点或误点。
            val labels = labelsOf(node)
            val interactive = labels.any { SkipLabelMatcher.isInteractive(it) }
            if (interactive) sawInteractive = true
            // 同一个节点既写「跳过」又写「摇一摇」时宁可不点，避免点到互动广告本体
            if (interactive || labels.none { SkipLabelMatcher.isSkipLabel(it) }) continue

            val (target, outcome) = validateCandidate(node)
            if (target != null) {
                return FindResult(
                    target, visited, A11yState.ScanOutcome.CLICKED,
                    sawInteractive = sawInteractive,
                )
            }
            if (rejection == null) rejection = outcome
        }
        return FindResult(
            null, visited, rejection ?: A11yState.ScanOutcome.NONE_FOUND,
            sawInteractive = sawInteractive,
        )
    }

    private fun findSkipBfs(root: AccessibilityNodeInfo, maxNodes: Int): FindResult {
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(root)
        var visited = 0
        var rejection: A11yState.ScanOutcome? = null
        var sawInteractive = false
        while (queue.isNotEmpty() && visited < maxNodes) {
            val node = queue.removeFirst()
            visited++
            val labels = labelsOf(node)
            val interactive = labels.any { SkipLabelMatcher.isInteractive(it) }
            if (interactive) sawInteractive = true
            if (!interactive && labels.any { SkipLabelMatcher.isSkipLabel(it) }) {
                val (target, outcome) = validateCandidate(node)
                if (target != null) {
                    return FindResult(
                        target, visited, A11yState.ScanOutcome.CLICKED,
                        sawInteractive = sawInteractive,
                    )
                }
                if (rejection == null) rejection = outcome
            }
            for (i in 0 until node.childCount) {
                node.getChild(i)?.let { queue.add(it) }
            }
        }
        return FindResult(
            null,
            visited,
            rejection ?: A11yState.ScanOutcome.NONE_FOUND,
            truncated = queue.isNotEmpty(),
            sawInteractive = sawInteractive,
        )
    }

    /** 文案节点必须可见、小尺寸，并且能找到一个同样小尺寸的可点击祖先 */
    private fun validateCandidate(
        node: AccessibilityNodeInfo,
    ): Pair<AccessibilityNodeInfo?, A11yState.ScanOutcome> {
        if (!node.isVisibleToUser) {
            return null to A11yState.ScanOutcome.LABEL_NOT_VISIBLE
        }
        if (!isSmall(node)) {
            return null to A11yState.ScanOutcome.LABEL_TOO_BIG
        }
        val target = clickableTarget(node)
            ?: return null to A11yState.ScanOutcome.NO_CLICKABLE_ANCESTOR
        return target to A11yState.ScanOutcome.CLICKED
    }

    /** text 和 contentDescription 都返回；两者都是本地字段，不产生额外 IPC */
    private fun labelsOf(node: AccessibilityNodeInfo): List<String> {
        val labels = ArrayList<String>(2)
        node.text?.toString()?.takeIf { it.isNotBlank() }?.let { labels.add(it) }
        node.contentDescription?.toString()?.takeIf { it.isNotBlank() }?.let { labels.add(it) }
        return labels
    }

    /** 只接受明显是按钮的小控件，防止误点整块广告位 */
    private fun isSmall(node: AccessibilityNodeInfo): Boolean {
        val r = Rect()
        node.getBoundsInScreen(r)
        if (r.width() <= 0 || r.height() <= 0) return false
        return r.width() <= screenWidth * 0.6f && r.height() <= screenHeight * 0.2f
    }

    /**
     * 向上找可点击节点。
     *
     * 关键：找到的父节点同样要过尺寸闸 —— 否则「跳过」文字不可点、父节点是满屏广告容器时，
     * 会把整块广告点掉，直接跳转落地页。这比不点危险得多。
     */
    private fun clickableTarget(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        var cur: AccessibilityNodeInfo? = node
        var depth = 0
        while (cur != null && depth < MAX_ANCESTOR_DEPTH) {
            if (cur.isClickable && cur.isVisibleToUser && isSmall(cur)) return cur
            cur = cur.parent
            depth++
        }
        return null
    }

    private fun resolveHomePackage(): String? = runCatching {
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
        packageManager.resolveActivity(intent, PackageManager.MATCH_DEFAULT_ONLY)
            ?.activityInfo?.packageName
    }.getOrNull()

    companion object {
        private const val TAG = "SplashGuardA11y"

        /** 同包名的窗口事件间隔超过这个值，才当作「又一次启动」 */
        private const val SAME_PKG_RESTART_MS = 1500L
        /** 两次点击的最小间隔 */
        private const val COOLDOWN_MS = 2000L
        /** 同一个 App 的跨窗口点击冷却，防止页面内跳转被当成新开屏重复点击 */
        private const val SAME_PKG_CLICK_COOLDOWN_MS = 10_000L
        /** 每次扫描先做的快速 BFS 节点上限（文本查找失败时兜底） */
        private const val QUICK_MAX_NODES = 80
        /** 系统文本查找的查询词；中文先查，英文只在兜底阶段查 */
        private const val TEXT_ZH = "跳过"
        private const val TEXT_EN = "skip"
        /** 低频兜底查询：覆盖「关闭广告 5s」等非「跳过」文案 */
        private val FALLBACK_TEXT_QUERIES = arrayOf("关闭广告", TEXT_EN)
        /** BFS 兜底的最小间隔：第一次扫描后每 1.2 秒一次 */
        private const val BFS_INTERVAL_MS = 1200L
        /** 英文文本查找从窗口 1 秒后开始，之后每 2 秒一次 */
        private const val ENGLISH_TEXT_START_MS = 1000L
        private const val ENGLISH_TEXT_INTERVAL_MS = 2000L
        /** 向上找可点击祖先的最大层数 */
        private const val MAX_ANCESTOR_DEPTH = 8
        /** 两次轮询扫描的最小间隔 */
        private const val SCAN_MIN_INTERVAL_MS = 80L
        /** 两次事件快路径扫描的最小间隔（与轮询独立） */
        private const val EVENT_SOURCE_MIN_INTERVAL_MS = 100L
    }
}
