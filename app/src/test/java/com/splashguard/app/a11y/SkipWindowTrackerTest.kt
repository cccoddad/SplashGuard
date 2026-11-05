package com.splashguard.app.a11y

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SkipWindowTrackerTest {

    private fun tracker() = SkipWindowTracker(
        pollWindowMs = 6000L,
        samePkgRestartMs = 1500L,
        cooldownMs = 2000L,
    )

    @Test
    fun `新窗口启动轮询`() {
        assertEquals(
            SkipWindowTracker.Action.START_POLL,
            tracker().onWindowEvent("com.a", 1000L)
        )
    }

    @Test
    fun `同包名 1_5 秒内必须重启轮询而不是忽略`() {
        val t = tracker()
        t.onWindowEvent("com.a", 1000L)
        assertEquals(SkipWindowTracker.Action.RESTART_POLL, t.onWindowEvent("com.a", 1500L))
    }

    @Test
    fun `同包名超过 1_5 秒视为又一次启动`() {
        val t = tracker()
        t.onWindowEvent("com.a", 1000L)
        assertEquals(SkipWindowTracker.Action.START_POLL, t.onWindowEvent("com.a", 3000L))
    }

    @Test
    fun `已点击后同包名事件忽略`() {
        val t = tracker()
        t.onWindowEvent("com.a", 1000L)
        t.onClicked(1200L)
        assertEquals(SkipWindowTracker.Action.NONE, t.onWindowEvent("com.a", 1300L))
    }

    @Test
    fun `切换到新 App 重置点击状态`() {
        val t = tracker()
        t.onWindowEvent("com.a", 1000L)
        t.onClicked(1200L)
        assertEquals(SkipWindowTracker.Action.START_POLL, t.onWindowEvent("com.b", 1300L))
        assertFalse(t.isClicked)
    }

    @Test
    fun `窗口超时后不再点击`() {
        val t = tracker()
        t.onWindowEvent("com.a", 1000L)
        assertFalse(t.canClick(7001L))
    }

    @Test
    fun `点击冷却跨窗口生效`() {
        val t = tracker()
        t.onWindowEvent("com.a", 1000L)
        t.onClicked(1000L)
        t.onWindowEvent("com.b", 2000L)
        assertFalse(t.canClick(2500L))
        assertTrue(t.canClick(3001L))
    }

    @Test
    fun `晚到的同包名事件会延长窗口结束时间`() {
        val t = tracker()
        t.onWindowEvent("com.a", 1000L)          // 原窗口 1000..7000
        // 冷启动过渡时 Activity 切换会在 1.5 秒内继续发事件
        t.onWindowEvent("com.a", 1500L)          // 延长到 min(7500, 11000)=7500
        assertEquals(SkipWindowTracker.Action.RESTART_POLL, t.onWindowEvent("com.a", 2500L))
        // 延长到 min(8500, 11000)=8500，8.4 秒时仍可点
        assertTrue(t.canClick(8400L))
        assertFalse(t.canClick(8501L))
    }

    @Test
    fun `窗口延长有上限不会无限续命`() {
        val t = tracker()
        t.onWindowEvent("com.a", 1000L)
        t.onWindowEvent("com.a", 1500L)
        t.onWindowEvent("com.a", 2900L)
        t.onWindowEvent("com.a", 4300L)
        t.onWindowEvent("com.a", 5700L)
        // 上限 1000+10000=11000
        assertTrue(t.canClick(10999L))
        assertFalse(t.canClick(11001L))
    }

    @Test
    fun `300ms 内连续事件只合并不重启轮询`() {
        val t = tracker()
        t.onWindowEvent("com.a", 1000L)
        assertEquals(SkipWindowTracker.Action.NONE, t.onWindowEvent("com.a", 1100L))
        assertEquals(SkipWindowTracker.Action.NONE, t.onWindowEvent("com.a", 1250L))
        // 合并不影响窗口本身
        assertTrue(t.canClick(1300L))
    }

    @Test
    fun `窗口过期后同包名事件重新开始`() {
        val t = tracker()
        t.onWindowEvent("com.a", 1000L)
        assertEquals(SkipWindowTracker.Action.START_POLL, t.onWindowEvent("com.a", 20000L))
        assertTrue(t.canClick(20001L))
    }
}
