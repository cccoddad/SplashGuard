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
 * ## v0.1.6 触发面修复
 *
 * v0.1.5 为了省电，把监听收窄成「只监听 TYPE_WINDOW_STATE_CHANGED、窗口 1.5 秒」。
 * 结果在 Android 16 / HyperOS 上出现整批 App 不触发：只要开屏窗口没有发出 state-changed，
 * 或者跳过按钮晚于 1.5 秒才渲染，就一次都不会扫。
 *
 * 现在恢复两条触发路径（遵守 AGENTS.md 红线，**不监听 `TYPE_WINDOW_CONTENT_CHANGED`**）：
 *  - `TYPE_WINDOW_STATE_CHANGED` / `TYPE_WINDOWS_CHANGED`：切换 App 时启动 6 秒轮询
 *    （Android 16 / 各家 ROM 对「冷启动」用哪一种事件并不一致，所以两种都监听）
 *  - 轮询：前 600ms 每 100ms，600–2000ms 每 250ms，之后每 800ms，直到点中或 6 秒结束
 *
 * 晚于 1.5 秒出现的按钮由 6 秒轮询覆盖；不靠内容变化事件，避免重演旧版耗电问题。
 *
 * ## v0.1.7 扫描优化
 *  - 先用 `findAccessibilityNodeInfosByText` 直查，再少量 BFS 兜底，降低冷启动卡顿
 *  - **不做坐标盲点**：文字节点不可点、祖先又是满屏广告容器时，宁可不点，
 *    也不能点到广告本体（AGENTS.md 硬约束）
 *  - 诊断报告记录每次扫描耗时，便于判断卡顿来源
 *
 * ## 防误点红线（不变）
 *  1. 只在窗口切换后的 6 秒内工作
 *  2. 只匹配「跳过 / 跳过广告 / 关闭广告 / Skip」这类文本（见 [SkipLabelMatcher]）
 *  3. 文字节点和最终点击的父节点都要过尺寸闸
 *  4. 每个窗口最多点一次，两次点击至少间隔 2 秒
 *  5. 不处理本应用、桌面和系统 UI 的窗口
 *  6. 不点击「摇一摇 / 扭一扭 / 点击查看」这类互动广告文案
 */
class SkipAccessibilityService : AccessibilityService() {

    private val handler = Handler(Looper.getMainLooper())
    private lateinit var prefs: Prefs

    private val tracker = SkipWindowTracker(
        pollWindowMs = POLL_WINDOW_MS,
        samePkgRestartMs = SAME_PKG_RESTART_MS,
        cooldownMs = COOLDOWN_MS,
    )

    private var screenWidth = 1080
    private var screenHeight = 2400
    private var homePkg: String? = null
    private var lastScanAt = 0L
    private var lastDeepSearchAt = 0L
    /** 复用当前窗口的根节点；窗口切换/配置变化时置空 */
    private var cachedRoot: AccessibilityNodeInfo? = null

    /** 抢跑任务：窗口出现后逐次退避地重试，直到点中或超出窗口 */
    private val pollTask = object : Runnable {
        override fun run() {
            val now = SystemClock.elapsedRealtime()
            if (!tracker.canClick(now)) return

            tryClick(now)
            if (!tracker.canClick(now)) return

            val elapsed = tracker.elapsed(now)
            val delay = when {
                elapsed < DENSE_PHASE_MS -> DENSE_INTERVAL_MS
                elapsed < MID_PHASE_MS -> MID_INTERVAL_MS
                else -> SPARSE_INTERVAL_MS
            }
            if (elapsed + delay <= POLL_WINDOW_MS) {
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
        // 晚出现的按钮由 6 秒轮询覆盖。
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
            // 离开目标 App（回桌面 / 打开本 App）：结束当前窗口，
            // 这样 6 秒内再次打开同一个 App 也能重新开始一轮
            tracker.reset()
            cachedRoot = null
            return
        }
        if (pkg in IGNORED_PKGS) return

        val now = SystemClock.elapsedRealtime()
        A11yState.onEvent(type, pkg)
        A11yState.log("event ${A11yState.eventTypeName(type)} pkg=$pkg")

        when (tracker.onWindowEvent(pkg, now)) {
            SkipWindowTracker.Action.START_POLL -> {
                lastDeepSearchAt = 0L
                startPoll()
            }
            SkipWindowTracker.Action.RESTART_POLL -> startPoll()
            else -> Unit
        }

        // 事件自带的 source 可能在跳过按钮刚渲染时就已包含它：先即时查一次，
        // 点中就不用等 150ms 的首轮轮询。source 只在本回调内使用。
        event.source?.let { source ->
            tryClick(now, rootOverride = source)
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
        handler.removeCallbacks(pollTask)
        tracker.reset()
        cachedRoot = null
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        A11yState.onDisconnected("destroy")
        handler.removeCallbacks(pollTask)
        tracker.reset()
        cachedRoot = null
        super.onDestroy()
    }

    private fun startPoll() {
        handler.removeCallbacks(pollTask)
        // 新的窗口事件：旧根节点可能已失效，下一次重新取
        cachedRoot = null
        // 窗口切换的瞬间系统最忙，延后 150ms 再做第一次扫描，避开转场峰值
        handler.postDelayed(pollTask, INITIAL_POLL_DELAY_MS)
    }

    private fun updateScreenSize() {
        val dm = resources.displayMetrics
        screenWidth = dm.widthPixels.coerceAtLeast(1)
        screenHeight = dm.heightPixels.coerceAtLeast(1)
    }

    private fun rootPkg(): String? =
        runCatching { rootInActiveWindow?.packageName?.toString() }.getOrNull()

    private fun tryClick(now: Long, rootOverride: AccessibilityNodeInfo? = null) {
        if (!tracker.canClick(now)) return
        // 同一时刻重复扫树没有意义
        if (now - lastScanAt < SCAN_MIN_INTERVAL_MS) return
        lastScanAt = now

        val scanStart = SystemClock.elapsedRealtime()
        // 复用当前窗口的根节点：refresh() 比每次 rootInActiveWindow() 的 IPC 便宜；
        // 根节点失效（窗口被销毁）时刷新会返回 false，再重新取。
        val root = rootOverride
            ?: cachedRoot?.takeIf { runCatching { it.refresh() }.getOrDefault(false) }
            ?: runCatching { rootInActiveWindow }.getOrNull()
            ?: return
        if (rootOverride == null) cachedRoot = root
        val rootPkg = root.packageName?.toString()
        if (rootPkg == packageName) return

        val result = findSkip(root)
        A11yState.onScan(
            result.visited,
            result.target != null,
            SystemClock.elapsedRealtime() - scanStart,
        )
        if (result.target == null) {
            if (A11yState.scanCount % 5L == 0L) {
                A11yState.log("scan pkg=$rootPkg visited=${result.visited} found=0")
            }
            return
        }

        val targetPkg = result.target.packageName?.toString()
            ?: rootPkg
            ?: "unknown"

        val clicked = runCatching {
            result.target.performAction(AccessibilityNodeInfo.ACTION_CLICK)
        }.getOrDefault(false)

        if (clicked) {
            tracker.onClicked(now)
            prefs.bumpSkipCount(targetPkg)
            A11yState.onClick(targetPkg)
            A11yState.log("CLICK pkg=$targetPkg visited=${result.visited}")
            handler.removeCallbacks(pollTask)
            Log.i(TAG, "clicked skip in $targetPkg")
        } else {
            A11yState.log("click failed pkg=$targetPkg visited=${result.visited}")
            Log.w(TAG, "click failed in $targetPkg")
        }
    }

    private data class FindResult(
        val target: AccessibilityNodeInfo?,
        val visited: Int,
        /** BFS 是否因为达到节点上限而被截断；未截断说明整棵树已经看完 */
        val truncated: Boolean = false,
    )

    /**
     * 两段式扫描，兼顾覆盖率和冷启动卡顿：
     *  - 先做 150 节点的快速 BFS，覆盖绝大多数开屏的浅层按钮；
     *  - 只有 BFS 被截断（树比 150 节点更深）才做全树文本深查，
     *    且窗口出现 600ms 后、每 1.5 秒最多一次。
     *    小树/浅层按钮不会触发第二次全树遍历。
     */
    private fun findSkip(root: AccessibilityNodeInfo): FindResult {
        val quick = findSkipBfs(root, QUICK_MAX_NODES)
        if (quick.target != null || !quick.truncated) return quick

        val now = SystemClock.elapsedRealtime()
        if (tracker.elapsed(now) < DEEP_SEARCH_START_MS) return quick
        if (now - lastDeepSearchAt < DEEP_SEARCH_INTERVAL_MS) return quick
        lastDeepSearchAt = now

        val deep = findSkipByText(root)
        return if (deep.target != null) {
            deep
        } else {
            FindResult(null, quick.visited + deep.visited)
        }
    }

    private fun findSkipBfs(root: AccessibilityNodeInfo, maxNodes: Int): FindResult {
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(root)
        var visited = 0
        while (queue.isNotEmpty() && visited < maxNodes) {
            val node = queue.removeFirst()
            visited++
            val label = labelOf(node)
            if (label != null &&
                SkipLabelMatcher.isSkipLabel(label) &&
                node.isVisibleToUser &&
                isSmall(node)
            ) {
                clickableTarget(node)?.let { return FindResult(it, visited) }
                // 没有小尺寸可点击祖先：宁可不点，绝不点广告本体
            }
            for (i in 0 until node.childCount) {
                node.getChild(i)?.let { queue.add(it) }
            }
        }
        return FindResult(null, visited, truncated = queue.isNotEmpty())
    }

    /**
     * 按文本查（系统 API），只用于快速 BFS 没找到时的深查。
     * 逐条查、找到即停，避免固定做 4 次全树遍历。
     */
    private fun findSkipByText(root: AccessibilityNodeInfo): FindResult {
        var visited = 0
        for (query in TEXT_QUERIES) {
            val found = runCatching {
                root.findAccessibilityNodeInfosByText(query)
            }.getOrNull()
            if (found.isNullOrEmpty()) continue

            for (node in found) {
                visited++
                val label = labelOf(node) ?: continue
                if (!SkipLabelMatcher.isSkipLabel(label)) continue
                if (!node.isVisibleToUser || !isSmall(node)) continue

                clickableTarget(node)?.let { return FindResult(it, visited) }
                // 文字节点不可点、祖先又是满屏广告容器：宁可不点
            }
        }
        return FindResult(null, visited)
    }

    private fun labelOf(node: AccessibilityNodeInfo): String? {
        val t = node.text?.toString()?.takeIf { it.isNotBlank() }
        if (t != null) return t
        return node.contentDescription?.toString()?.takeIf { it.isNotBlank() }
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

        /** 抢跑总时长：与 PRD/旧版一致，覆盖 2~3 秒才出现的跳过按钮 */
        const val POLL_WINDOW_MS = 6000L
        /** 窗口切换后延后多久做第一次扫描，避开转场峰值 */
        private const val INITIAL_POLL_DELAY_MS = 150L
        /** 前段密集：跳过按钮刚渲染的高峰期 */
        private const val DENSE_PHASE_MS = 600L
        private const val DENSE_INTERVAL_MS = 250L
        /** 中段：异步填充较慢的广告 SDK */
        private const val MID_PHASE_MS = 3000L
        private const val MID_INTERVAL_MS = 700L
        /** 后段：只做低频兜底，避免 6 秒内反复扫树 */
        private const val SPARSE_INTERVAL_MS = 1500L
        /** 两次点击的最小间隔 */
        private const val COOLDOWN_MS = 2000L
        /** 同包名的窗口事件间隔超过这个值，才当作「又一次启动」 */
        private const val SAME_PKG_RESTART_MS = 1500L
        /** 每次扫描先做的快速 BFS 节点上限 */
        private const val QUICK_MAX_NODES = 150
        /** 窗口出现多久后才允许全树文本深查（避开冷启动最忙的阶段） */
        private const val DEEP_SEARCH_START_MS = 600L
        /** 全树文本深查的最小间隔 */
        private const val DEEP_SEARCH_INTERVAL_MS = 1500L
        /** 用系统文本查找接口预筛；逐条查、找到即停 */
        private val TEXT_QUERIES = arrayOf("跳过", "skip", "Skip", "关闭广告")
        /** 向上找可点击祖先的最大层数 */
        private const val MAX_ANCESTOR_DEPTH = 8
        /** 两次扫描的最小间隔，避免轮询重复扫树 */
        private const val SCAN_MIN_INTERVAL_MS = 80L

        /** 这些窗口不是目标 App：桌面、系统 UI、权限弹窗、输入法 */
        private val IGNORED_PKGS = setOf(
            "android",
            "com.android.systemui",
            "com.android.permissioncontroller",
            "com.google.android.permissioncontroller",
            "com.android.packageinstaller",
            "com.google.android.packageinstaller",
            "com.miui.securitycenter",
            "com.lbe.security.miui",
        )
    }
}
