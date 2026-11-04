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
 * 两条触发路径：
 *  - 事件驱动：窗口内容变化时扫一次
 *  - 抢跑轮询：窗口出现后的 [POLL_WINDOW_MS] 内每 [POLL_INTERVAL_MS] 扫一次
 *    （跳过按钮常延迟 300~800ms 才渲染，只靠事件会错过；摇一摇广告正是抢这段空窗）
 *
 * 严格约束（宁可漏点，也不误点）：
 *  1. 只在窗口切换后的 6 秒内工作
 *  2. 只匹配「跳过 / 跳过广告 / 关闭广告 / Skip」这类文本
 *  3. 控件宽高必须远小于屏幕（避免整块广告位被当成按钮）—— 文字节点和最终点击的父节点都要过这道闸
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
    private var clickedForWindow = false
    private var screenWidth = 1080
    private var screenHeight = 2400

    /** 抢跑任务：窗口出现后高频重试，直到点中或超时 */
    private val pollTask = object : Runnable {
        override fun run() {
            if (clickedForWindow) return
            if (SystemClock.elapsedRealtime() - lastWindowAt > POLL_WINDOW_MS) return
            tryClick(MAX_NODES_POLL)
            if (!clickedForWindow &&
                SystemClock.elapsedRealtime() - lastWindowAt <= POLL_WINDOW_MS
            ) {
                handler.postDelayed(this, POLL_INTERVAL_MS)
            }
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        val dm = resources.displayMetrics
        screenWidth = dm.widthPixels
        screenHeight = dm.heightPixels
        serviceInfo = serviceInfo?.apply {
            eventTypes = AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED or
                AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED
            feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC
            notificationTimeout = 60
            Log.i(TAG, "connected, screen=${screenWidth}x$screenHeight")
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        event ?: return
        val now = SystemClock.elapsedRealtime()

        if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            lastWindowAt = now
            clickedForWindow = false
            handler.removeCallbacks(pollTask)
            handler.post(pollTask)
            return
        }

        if (clickedForWindow) return
        if (now - lastWindowAt > WINDOW_WINDOW_MS) return
        if (now - lastClickAt < COOLDOWN_MS) return
        tryClick(MAX_NODES)
    }

    override fun onInterrupt() = Unit

    override fun onDestroy() {
        handler.removeCallbacks(pollTask)
        super.onDestroy()
    }

    private fun tryClick(maxNodes: Int) {
        val now = SystemClock.elapsedRealtime()
        if (now - lastWindowAt > WINDOW_WINDOW_MS) return
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
        private const val WINDOW_WINDOW_MS = 6000L
        private const val COOLDOWN_MS = 2000L
        private const val POLL_WINDOW_MS = 1400L
        private const val POLL_INTERVAL_MS = 90L
        private const val MAX_NODES = 500
        private const val MAX_NODES_POLL = 260
        private val WHITESPACE = "\\s".toRegex()
        private val EXACT_LABELS = setOf(
            "跳过", "跳过广告", "关闭广告", "跳过广告5", "skip", "skipad", "skipads", "skipadvert"
        )
        private val INTERACTIVE = listOf("摇一摇", "摇动", "扭一扭", "抖一抖", "点击查看", "滑动查看", "晃动")
    }
}
