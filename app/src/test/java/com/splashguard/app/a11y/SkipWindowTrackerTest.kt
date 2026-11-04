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
}
