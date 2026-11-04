package com.splashguard.app.a11y

import java.text.SimpleDateFormat
import java.util.ArrayDeque
import java.util.Date
import java.util.Locale

/**
 * 无障碍服务的运行态与最近事件摘要，供首页和诊断报告读取。
 *
 * 只存在内存里，不落盘、不联网。服务被系统杀掉后归零，这本身也是一种诊断信息：
 * 「系统已授权」但 `connected=false` 说明服务没有真正绑定。
 */
object A11yState {

    private const val MAX_LINES = 60

    @Volatile var connected: Boolean = false
    /** 墙钟时间（毫秒），0 表示尚未连接 */
    @Volatile var connectedAt: Long = 0L
    @Volatile var lastEventAt: Long = 0L
    @Volatile var lastEventPkg: String? = null
    @Volatile var lastEventType: Int = 0
    @Volatile var lastScanAt: Long = 0L
    @Volatile var lastScanVisited: Int = 0
    @Volatile var lastScanFound: Int = 0
    @Volatile var lastClickAt: Long = 0L
    @Volatile var lastClickPkg: String? = null
    @Volatile var eventCount: Long = 0L
    @Volatile var scanCount: Long = 0L
    @Volatile var clickCount: Long = 0L

    private val fmt = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)
    private val lines = ArrayDeque<String>()

    @Synchronized
    fun log(text: String) {
        lines.addLast("${fmt.format(Date())} $text")
        while (lines.size > MAX_LINES) lines.removeFirst()
    }

    @Synchronized
    fun recent(): List<String> = lines.toList()

    @Synchronized
    fun clear() {
        lines.clear()
    }

    fun onConnected() {
        connected = true
        connectedAt = System.currentTimeMillis()
        eventCount = 0L
        scanCount = 0L
        clickCount = 0L
        clear()
        log("service connected")
    }

    fun onDisconnected(reason: String) {
        connected = false
        log("service disconnected: $reason")
    }

    fun onEvent(type: Int, pkg: String) {
        eventCount++
        lastEventAt = System.currentTimeMillis()
        lastEventPkg = pkg
        lastEventType = type
    }

    fun onScan(visited: Int, found: Boolean) {
        scanCount++
        lastScanAt = System.currentTimeMillis()
        lastScanVisited = visited
        lastScanFound = if (found) 1 else 0
    }

    fun onClick(pkg: String) {
        clickCount++
        lastClickAt = System.currentTimeMillis()
        lastClickPkg = pkg
    }

    fun eventTypeName(type: Int): String = when (type) {
        android.view.accessibility.AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> "STATE"
        android.view.accessibility.AccessibilityEvent.TYPE_WINDOWS_CHANGED -> "WINDOWS"
        android.view.accessibility.AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED -> "CONTENT"
        else -> "OTHER($type)"
    }
}
