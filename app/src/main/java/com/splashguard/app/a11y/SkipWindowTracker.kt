package com.splashguard.app.a11y

/**
 * 无障碍「窗口生命周期」状态机。纯 Kotlin，无 Android 依赖，可直接单测。
 *
 * v0.1.5 的回归根因在这里：同包名的窗口事件只刷新了时间、没有重启轮询，
 * 而且只监听 `TYPE_WINDOW_STATE_CHANGED`，导致 Android 16 / HyperOS 上
 * 很多 App 一次都不会被扫描。v0.1.6 把状态迁移独立出来，并用单测锁住行为。
 */
class SkipWindowTracker(
    private val pollWindowMs: Long = 6000L,
    private val samePkgRestartMs: Long = 1500L,
    private val cooldownMs: Long = 2000L,
) {

    enum class Action {
        /** 不处理 */
        NONE,

        /** 新窗口：重置状态并启动轮询 */
        START_POLL,

        /** 同包名的又一次窗口变化：确保轮询在跑（v0.1.5 漏掉了这一步） */
        RESTART_POLL,
    }

    private var pkg: String? = null
    private var windowStartAt = 0L
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
     * - 同包名且已点过：忽略。
     * - 同包名、未点过、距上次窗口事件 < 1.5s：只是页面内窗口变化，**重启轮询**。
     * - 同包名、未点过、间隔 ≥ 1.5s：视为这个 App 的又一次启动，重置窗口。
     */
    fun onWindowEvent(newPkg: String, now: Long): Action {
        val current = pkg
        if (current == null || newPkg != current || now - windowStartAt > pollWindowMs) {
            pkg = newPkg
            windowStartAt = now
            lastWindowEventAt = now
            clicked = false
            return Action.START_POLL
        }
        if (clicked) return Action.NONE
        if (now - lastWindowEventAt < samePkgRestartMs) {
            lastWindowEventAt = now
            return Action.RESTART_POLL
        }
        pkg = newPkg
        windowStartAt = now
        lastWindowEventAt = now
        clicked = false
        return Action.START_POLL
    }

    /** 当前是否允许尝试点击：未点过、窗口未超时、距上次点击已过冷却 */
    fun canClick(now: Long): Boolean {
        if (pkg == null || clicked) return false
        if (now - windowStartAt > pollWindowMs) return false
        if (lastClickAt != 0L && now - lastClickAt < cooldownMs) return false
        return true
    }

    fun onClicked(now: Long) {
        clicked = true
        lastClickAt = now
    }

    fun reset() {
        pkg = null
        windowStartAt = 0L
        lastWindowEventAt = 0L
        clicked = false
        lastClickAt = 0L
    }
}
