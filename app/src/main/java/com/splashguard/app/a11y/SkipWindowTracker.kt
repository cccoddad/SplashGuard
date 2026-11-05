package com.splashguard.app.a11y

/**
 * 无障碍「窗口生命周期」状态机。纯 Kotlin，无 Android 依赖，可直接单测。
 *
 * v0.1.5 的回归根因在这里：同包名的窗口事件只刷新了时间、没有重启轮询，
 * 而且只监听 `TYPE_WINDOW_STATE_CHANGED`，导致 Android 16 / HyperOS 上
 * 很多 App 一次都不会被扫描。v0.1.6 把状态迁移独立出来，并用单测锁住行为。
 *
 * v0.1.8 调整（对应「跳过出现却没点」）：
 *  - 同包名事件仍然重启轮询，但**延长本次窗口的结束时间**（有上限），
 *    不再从第一次事件固定算 6 秒；否则 5–8 秒才渲染的跳过按钮永远等不到。
 *  - 300ms 内的连续事件只合并、不重启轮询，避免事件风暴反复把扫描推迟到 150ms 后。
 *  - 窗口上限 [maxWindowMs] 防止同一个 App 频繁窗口事件导致无限扫描。
 */
class SkipWindowTracker(
    private val pollWindowMs: Long = PollSchedule.WINDOW_MS,
    private val samePkgRestartMs: Long = 1500L,
    private val cooldownMs: Long = 2000L,
    private val maxWindowMs: Long = 10000L,
    private val eventCoalesceMs: Long = 300L,
) {

    enum class Action {
        /** 不处理（连续事件合并 / 已点过） */
        NONE,

        /** 新窗口：重置状态并启动轮询 */
        START_POLL,

        /** 同包名的又一次窗口变化：确保轮询在跑（v0.1.5 漏掉了这一步） */
        RESTART_POLL,
    }

    private var pkg: String? = null
    private var windowStartAt = 0L
    private var windowEndAt = 0L
    private var lastWindowEventAt = 0L
    private var clicked = false
    private var lastClickAt = 0L

    val windowPkg: String? get() = pkg
    val isClicked: Boolean get() = clicked

    fun elapsed(now: Long): Long =
        if (windowStartAt == 0L) Long.MAX_VALUE else now - windowStartAt

    /**
     * 处理 `TYPE_WINDOW_STATE_CHANGED` / `TYPE_WINDOWS_CHANGED`。
     *
     * - 新包名 / 上一个窗口已超时：视为新窗口，重置并启动轮询。
     * - 同包名已点过：忽略。
     * - 同包名、300ms 内连续事件：只合并时间，返回 NONE（轮询已在前台，不重置计时）。
     * - 同包名、未点过、距上次窗口事件 < 1.5s：重启轮询并延长结束时间（上限 10s）。
     * - 同包名、未点过、间隔 ≥ 1.5s：视为这个 App 的又一次启动，重置窗口。
     */
    fun onWindowEvent(newPkg: String, now: Long): Action {
        val current = pkg
        val expired = current != null &&
            (now >= windowEndAt || now - windowStartAt > maxWindowMs)
        if (current == null || newPkg != current || expired) {
            startWindow(newPkg, now)
            return Action.START_POLL
        }
        if (clicked) return Action.NONE

        val sinceLast = now - lastWindowEventAt
        if (sinceLast < eventCoalesceMs) {
            // 事件风暴（转场时常见）：不重启轮询，避免扫描被反复推迟
            lastWindowEventAt = now
            return Action.NONE
        }
        if (sinceLast < samePkgRestartMs) {
            lastWindowEventAt = now
            // 延长结束时间，但不超过窗口总上限；这是「晚出现按钮」能被点到的关键
            val extended = minOf(now + pollWindowMs, windowStartAt + maxWindowMs)
            if (extended > windowEndAt) windowEndAt = extended
            return Action.RESTART_POLL
        }
        // 同包名但间隔够久：视为这个 App 的又一次启动
        startWindow(newPkg, now)
        return Action.START_POLL
    }

    /** 当前是否允许尝试点击：未点过、窗口未超时、距上次点击已过冷却 */
    fun canClick(now: Long): Boolean {
        if (pkg == null || clicked) return false
        if (now > windowEndAt) return false
        if (now - windowStartAt > maxWindowMs) return false
        if (lastClickAt != 0L && now - lastClickAt < cooldownMs) return false
        return true
    }

    /** 本次窗口还可以活多久（毫秒），用于诊断 */
    fun remainingMs(now: Long): Long =
        if (pkg == null) 0L else (windowEndAt - now).coerceAtLeast(0L)

    fun onClicked(now: Long) {
        clicked = true
        lastClickAt = now
    }

    fun reset() {
        pkg = null
        windowStartAt = 0L
        windowEndAt = 0L
        lastWindowEventAt = 0L
        clicked = false
        lastClickAt = 0L
    }

    private fun startWindow(newPkg: String, now: Long) {
        pkg = newPkg
        windowStartAt = now
        windowEndAt = now + pollWindowMs
        lastWindowEventAt = now
        clicked = false
    }
}
