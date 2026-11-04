package com.splashguard.app.a11y

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.graphics.Rect
import android.os.SystemClock
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import java.util.ArrayDeque

/**
 * 冷启动后自动点「跳过」。
 *
 * 严格约束（宁可漏点，也不误点）：
 *  1. 只在窗口切换后的 6 秒内工作
 *  2. 只匹配「跳过 / 跳过广告 / 关闭广告 / Skip」这类文本
 *  3. 控件宽高必须远小于屏幕（避免整块广告位被当成按钮）
 *  4. 每个窗口最多点一次，两次点击至少间隔 2 秒
 *  5. 不处理本应用自己的界面
 *  6. 只点可点击的控件（自身或最近的可点击父节点）
 *
 * 不做：读取界面文字内容、点击广告本体、模拟滑动、上传任何数据。
 */
class SkipAccessibilityService : AccessibilityService() {

    private var lastClickAt = 0L
    private var lastWindowAt = 0L
    private var clickedForWindow = false
    private var screenWidth = 1080
    private var screenHeight = 2400

    override fun onServiceConnected() {
        super.onServiceConnected()
        val dm = resources.displayMetrics
        screenWidth = dm.widthPixels
        screenHeight = dm.heightPixels
        serviceInfo = serviceInfo?.apply {
            eventTypes = AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED or
                AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED
            feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC
            notificationTimeout = 80
            Log.i(TAG, "connected, screen=${screenWidth}x$screenHeight")
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        event ?: return
        val now = SystemClock.elapsedRealtime()

        if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            lastWindowAt = now
            clickedForWindow = false
        }
        if (clickedForWindow) return
        if (now - lastWindowAt > WINDOW_WINDOW_MS) return
        if (now - lastClickAt < COOLDOWN_MS) return

        val root = rootInActiveWindow ?: return
        val pkg = root.packageName?.toString()
        if (pkg == packageName) return

        val target = findSkip(root) ?: return
        if (target.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
            lastClickAt = now
            clickedForWindow = true
            Log.i(TAG, "clicked skip in $pkg")
        }
    }

    override fun onInterrupt() = Unit

    private fun findSkip(root: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(root)
        var visited = 0
        while (queue.isNotEmpty() && visited < MAX_NODES) {
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

    private fun clickableTarget(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        var cur: AccessibilityNodeInfo? = node
        var depth = 0
        while (cur != null && depth < 4) {
            if (cur.isClickable) return cur
            cur = cur.parent
            depth++
        }
        return null
    }

    companion object {
        private const val TAG = "SplashGuardA11y"
        private const val WINDOW_WINDOW_MS = 6000L
        private const val COOLDOWN_MS = 2000L
        private const val MAX_NODES = 500
        private val WHITESPACE = "\\s".toRegex()
        private val EXACT_LABELS = setOf(
            "跳过", "跳过广告", "关闭广告", "跳过广告5", "skip", "skipad", "skipads", "skipadvert"
        )
    }
}
