package com.splashguard.app.a11y

/**
 * 抢跑轮询的节奏，纯 Kotlin，可直接单测。
 *
 * v0.1.8 的设计目标是「命中时更快、未命中时不比 v0.1.7 更耗」：
 *  - 每次窗口最多 [MAX_SCANS] 次尝试，避免 8 秒窗口把扫描次数推到 v0.1.7 的 2.6 倍；
 *  - 命中路径只做一次「跳过」文本查找，通常几十毫秒就点掉；
 *  - 未命中路径按上一次扫描耗时退避：扫描越慢，间隔越大，把占空比压在可控范围；
 *  - BFS 和英文 `skip` 只在窗口前段低频兜底，不是每次扫描都做。
 */
object PollSchedule {

    /** 轮询总时长（从窗口事件算起） */
    const val WINDOW_MS = 8000L

    /** 窗口切换后延后多久做第一次扫描，避开转场峰值 */
    const val INITIAL_DELAY_MS = 150L

    /** 前段密集期：跳过按钮刚渲染的高峰 */
    const val DENSE_PHASE_MS = 600L
    const val DENSE_INTERVAL_MS = 150L

    /** 中段：异步填充较慢的广告 SDK */
    const val MID_PHASE_MS = 3000L
    const val MID_INTERVAL_MS = 350L

    /** 后段：低频兜底，覆盖 5–8 秒才出现的按钮 */
    const val TAIL_INTERVAL_MS = 600L

    /** 单个窗口最多扫描次数；v0.1.7 约 8 次，这里最多 12 次 */
    const val MAX_SCANS = 12

    /** 超过这个耗时的扫描，间隔按耗时倍数退避，最多退到 2 秒 */
    private const val SLOW_SCAN_MS = 120L
    private const val MAX_ADAPTIVE_DELAY_MS = 2000L

    fun intervalFor(elapsedMs: Long): Long = when {
        elapsedMs < DENSE_PHASE_MS -> DENSE_INTERVAL_MS
        elapsedMs < MID_PHASE_MS -> MID_INTERVAL_MS
        else -> TAIL_INTERVAL_MS
    }

    /**
     * 下一次扫描的间隔。
     *
     * 上次扫描慢，说明目标 App 的 UI 线程忙或树很大；此时继续 150ms 扫只会把占空比推高。
     * 用 2 倍耗时作为下限，把「扫描耗时 / 总时间」压在 50% 以下（再叠加 MAX_SCANS）。
     */
    fun adaptiveDelay(elapsedMs: Long, lastScanMs: Long): Long {
        val base = intervalFor(elapsedMs)
        if (lastScanMs < SLOW_SCAN_MS) return base
        return maxOf(base, (lastScanMs * 2).coerceAtMost(MAX_ADAPTIVE_DELAY_MS))
    }
}
