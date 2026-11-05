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

    /** v0.1.8：扫描日志改为每次必记，行数上限同步提高（约 20 KB 内存） */
    private const val MAX_LINES = 200

    /** 一次扫描的最终结果，用于定位「为什么没点」 */
    enum class ScanOutcome(val label: String) {
        NONE_FOUND("未找到"),
        CLICKED("已点击"),
        CLICK_FAILED("点击失败"),
        LABEL_NOT_VISIBLE("文案不可见"),
        LABEL_TOO_BIG("文案超尺寸"),
        NO_CLICKABLE_ANCESTOR("无可点祖先"),
        SELF_WINDOW("本应用"),
        HOME_WINDOW("桌面"),
        IGNORED_WINDOW("系统界面"),
        WRONG_ROOT("根包不匹配"),
        NO_PKG("无包名"),
    }

    @Volatile var connected: Boolean = false
    /** 墙钟时间（毫秒），0 表示尚未连接 */
    @Volatile var connectedAt: Long = 0L
    @Volatile var lastEventAt: Long = 0L
    @Volatile var lastEventPkg: String? = null
    @Volatile var lastEventType: Int = 0
    /** 最近一次窗口事件让状态机做了什么：START / RESTART / NONE */
    @Volatile var lastWindowAction: String = "-"
    @Volatile var lastScanAt: Long = 0L
    @Volatile var lastScanVisited: Int = 0
    @Volatile var lastScanFound: Int = 0
    @Volatile var lastScanMs: Long = 0L
    @Volatile var lastScanPkg: String? = null
    @Volatile var lastScanTrigger: String = "-"
    @Volatile var lastScanOutcome: ScanOutcome = ScanOutcome.NONE_FOUND
    @Volatile var lastScanShake: Boolean = false
    @Volatile var lastClickActionMs: Long = 0L
    @Volatile var totalScanMs: Long = 0L
    @Volatile var lastClickAt: Long = 0L
    @Volatile var lastClickPkg: String? = null
    @Volatile var eventCount: Long = 0L
    @Volatile var scanCount: Long = 0L
    @Volatile var clickCount: Long = 0L

    /** 当前窗口（一次冷启动）的摘要，用于计算「窗口出现 → 点击」的延迟 */
    @Volatile var windowPkg: String? = null
    @Volatile var windowStartedAt: Long = 0L
    @Volatile var windowScanCount: Long = 0L
    @Volatile var windowFirstFoundAt: Long = 0L
    /** 本次窗口是否看到过「摇一摇 / 扭一扭 / 点击查看」文案 */
    @Volatile var windowSawShake: Boolean = false

    val metrics = ScanMetrics()
    private val outcomeCounts = LongArray(ScanOutcome.values().size)

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
        totalScanMs = 0L
        lastClickAt = 0L
        lastClickPkg = null
        lastClickActionMs = 0L
        windowPkg = null
        windowStartedAt = 0L
        windowScanCount = 0L
        windowFirstFoundAt = 0L
        windowSawShake = false
        metrics.reset()
        synchronized(this) { outcomeCounts.fill(0L) }
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

    /** 新窗口 / 同包名再次启动：重置本次窗口摘要 */
    fun onWindowStart(pkg: String) {
        windowPkg = pkg
        windowStartedAt = System.currentTimeMillis()
        windowScanCount = 0L
        windowFirstFoundAt = 0L
        windowSawShake = false
    }

    fun onShakeSeen() {
        lastScanShake = true
        windowSawShake = true
    }

    fun onScan(
        visited: Int,
        found: Boolean,
        elapsedMs: Long,
        pkg: String? = null,
        trigger: String = "-",
        outcome: ScanOutcome = if (found) ScanOutcome.CLICKED else ScanOutcome.NONE_FOUND,
    ) {
        scanCount++
        lastScanAt = System.currentTimeMillis()
        lastScanVisited = visited
        lastScanFound = if (found) 1 else 0
        lastScanMs = elapsedMs
        lastScanPkg = pkg
        lastScanTrigger = trigger
        lastScanOutcome = outcome
        totalScanMs += elapsedMs
        metrics.onScan(elapsedMs)
        synchronized(this) {
            val idx = outcome.ordinal
            if (idx in outcomeCounts.indices) outcomeCounts[idx]++
        }
        if (windowStartedAt > 0L) {
            windowScanCount++
            if (found && windowFirstFoundAt == 0L) windowFirstFoundAt = System.currentTimeMillis()
        }
    }

    /** 门控直接拒绝的扫描尝试：不计入耗时指标，只计数并更新最近结果 */
    fun onScanRejected(pkg: String?, trigger: String, outcome: ScanOutcome) {
        lastScanAt = System.currentTimeMillis()
        lastScanPkg = pkg
        lastScanTrigger = trigger
        lastScanOutcome = outcome
        lastScanVisited = 0
        lastScanMs = 0L
        lastScanFound = 0
        lastScanShake = false
        synchronized(this) {
            val idx = outcome.ordinal
            if (idx in outcomeCounts.indices) outcomeCounts[idx]++
        }
    }

    fun avgScanMs(): Long = metrics.avgMs()

    fun outcomeCount(outcome: ScanOutcome): Long = synchronized(this) { outcomeCounts[outcome.ordinal] }

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
