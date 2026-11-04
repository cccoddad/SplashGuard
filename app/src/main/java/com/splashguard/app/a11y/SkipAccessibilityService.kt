package com.splashguard.app.a11y

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import java.util.ArrayDeque

/**
 * 冷启动后自动点「跳过」。
 *
 * ## 省电设计（v0.1.5 重写）
 *
 * 早期版本同时监听 `TYPE_WINDOW_CONTENT_CHANGED`，导致滚动列表、播放视频、打字时的每一次
 * 界面变化都会触发一次全树遍历 —— 实测后台 CPU 时间飙到 2 分 48 秒、整机发烫。
 *
 * 现在只监听 `TYPE_WINDOW_STATE_CHANGED`，并且**只在包名变化时**才认为是「切了一个 App」，
 * 同包名内的对话框 / Toast / 页面切换一律忽略。配合一个收敛的轮询窗口：
 *
 * ```
 * 切到新 App ──100ms×6──> 600ms ──300ms×3──> 1500ms 停止
 * ```
 *
 * 单次切换最多 9 次扫描、每次最多 150 个节点；点到即停。相比旧版「每次切换 15 次 × 500 节点
 * + 内容变化时无限次全树」，CPU 占用降低约一个数量级。
 *
 * ## 防误点红线（不变）
 *  1. 只在窗口切换后的 1.5 秒内工作
 *  2. 只匹配「跳过 / 跳过广告 / 关闭广告 / Skip」这类文本
 *  3. 控件宽高必须远小于屏幕 —— 文字节点和最终点击的父节点都要过这道闸
 *  4. 每个窗口最多点一次，两次点击至少间隔 2 秒
 *  5. 不处理本应用自己的界面
 *  6. 不点击「摇一摇 / 扭一扭 / 点击查看」这类互动广告文案
 *
 * 不做：读取界面文字内容、点击广告本体、模拟滑动、上传任何数据。
 */
class SkipAccessibilityService : AccessibilityService() {

    private val handler = Handler(Looper.getMainLooper())

    private var lastClickAt = 0L
    private var lastWindowAt = 0L
    private var lastWindowPkg: String? = null
    private var clickedForWindow = false
    private var screenWidth = 1080
    private var screenHeight = 2400

    /** 抢跑任务：窗口出现后逐次退避地重试，直到点中或超出窗口 */
    private val pollTask = object : Runnable {
        override fun run() {
            if (clickedForWindow) return
            val elapsed = SystemClock.elapsedRealtime() - lastWindowAt
            if (elapsed > POLL_WINDOW_MS) return

            tryClick(MAX_NODES)

            if (clickedForWindow) return
            val delay = if (elapsed < DENSE_PHASE_MS) DENSE_INTERVAL_MS else SPARSE_INTERVAL_MS
            if (elapsed + delay <= POLL_WINDOW_MS) {
                handler.postDelayed(this, delay)
            }
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        val dm = resources.displayMetrics
        screenWidth = dm.widthPixels
        screenHeight = dm.heightPixels
        serviceInfo = serviceInfo?.apply {
            // 关键：不再监听 TYPE_WINDOW_CONTENT_CHANGED，那是耗电元凶
            eventTypes = AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED
            feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC
            notificationTimeout = 200
            Log.i(TAG, "connected, screen=${screenWidth}x$screenHeight")
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        event ?: return
        if (event.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return

        val pkg = event.packageName?.toString() ?: return
        if (pkg == packageName) return

        val now = SystemClock.elapsedRealtime()

        if (pkg == lastWindowPkg) {
            // 同一 App 内部：已经点过就不再打扰
            if (clickedForWindow) return
            // 没点过 —— 若距上次窗口切换还不到一个轮询窗口，说明只是页面内跳转，续上当前这一轮即可
            if (now - lastWindowAt < SAME_PKG_RESTART_MS) {
                lastWindowAt = now
                return
            }
            // 间隔足够久，视为这个 App 的又一次启动（如开屏 Activity 与主页 Activity 同包名）
        }

        lastWindowPkg = pkg
        lastWindowAt = now
        clickedForWindow = false
        handler.removeCallbacks(pollTask)
        handler.post(pollTask)
    }

    override fun onInterrupt() = Unit

    override fun onDestroy() {
        handler.removeCallbacks(pollTask)
        super.onDestroy()
    }

    private fun tryClick(maxNodes: Int) {
        val now = SystemClock.elapsedRealtime()
        if (now - lastWindowAt > POLL_WINDOW_MS + 200) return
        if (now - lastClickAt < COOLDOWN_MS) return

        val root = runCatching { rootInActiveWindow }.getOrNull() ?: return
        val pkg = root.packageName?.toString()
        if (pkg == packageName) return

        val target = findSkip(root, maxNodes) ?: return
        if (runCatching { target.performAction(AccessibilityNodeInfo.ACTION_CLICK) }.getOrDefault(false)) {
            lastClickAt = now
            clickedForWindow = true
            handler.removeCallbacks(pollTask)
            Log.i(TAG, "clicked skip in $pkg")
        }
    }

    private fun findSkip(root: AccessibilityNodeInfo, maxNodes: Int): AccessibilityNodeInfo? {
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(root)
        var visited = 0
        while (queue.isNotEmpty() && visited < maxNodes) {
            val node = queue.removeFirst()
            visited++
            val label = labelOf(node)
            if (label != null && isSkipLabel(label) && isSmall(node)) {
                clickableTarget(node)?.let { return it }
            }
            for (i in 0 until node.childCount) {
                node.getChild(i)?.let { queue.add(it) }
            }
        }
        return null
    }

    private fun labelOf(node: AccessibilityNodeInfo): String? {
        val t = node.text?.toString()?.takeIf { it.isNotBlank() }
        if (t != null) return t
        return node.contentDescription?.toString()?.takeIf { it.isNotBlank() }
    }

    private fun isSkipLabel(raw: String): Boolean {
        val s = raw.replace(WHITESPACE, "").lowercase()
        if (s.isEmpty() || s.length > 10) return false
        // 「摇一摇跳转详情」「点击查看」这类互动广告文案，绝不能点
        if (INTERACTIVE.any { s.contains(it) }) return false
        if (s in EXACT_LABELS) return true
        // "跳过3"、"跳过 5s" 这类带倒计时的
        return (s.startsWith("跳过") || s.startsWith("skip")) && s.length <= 6
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
        while (cur != null && depth < 4) {
            if (cur.isClickable && isSmall(cur)) return cur
            cur = cur.parent
            depth++
        }
        return null
    }

    companion object {
        private const val TAG = "SplashGuardA11y"

        /** 抢跑总时长：开屏广告的跳过按钮基本都在这段时间内出现 */
        private const val POLL_WINDOW_MS = 1500L
        /** 前段密集（跳过按钮刚渲染的高峰期） */
        private const val DENSE_PHASE_MS = 600L
        private const val DENSE_INTERVAL_MS = 100L
        /** 后段稀疏，覆盖异步填充较慢的广告 SDK */
        private const val SPARSE_INTERVAL_MS = 300L
        /** 两次点击的最小间隔 */
        private const val COOLDOWN_MS = 2000L
        /** 同包名的窗口变化间隔超过这个值，才当作「又一次启动」并重开一轮扫描 */
        private const val SAME_PKG_RESTART_MS = 1500L

        /** 单次扫描的节点上限：跳过按钮总在树的浅层，150 足够，再大只是白烧 CPU */
        private const val MAX_NODES = 150

        private val WHITESPACE = "\\s".toRegex()
        private val EXACT_LABELS = setOf(
            "跳过", "跳过广告", "关闭广告", "跳过广告5", "skip", "skipad", "skipads", "skipadvert"
        )
        private val INTERACTIVE = listOf("摇一摇", "摇动", "扭一扭", "抖一抖", "点击查看", "滑动查看", "晃动")
    }
}
