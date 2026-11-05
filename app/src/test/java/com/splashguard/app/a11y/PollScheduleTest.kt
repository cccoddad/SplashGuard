package com.splashguard.app.a11y

import org.junit.Assert.assertEquals
import org.junit.Test

class PollScheduleTest {

    @Test
    fun `前 600ms 用 150ms 密集轮询`() {
        assertEquals(150L, PollSchedule.intervalFor(0L))
        assertEquals(150L, PollSchedule.intervalFor(599L))
    }

    @Test
    fun `中段用 350ms`() {
        assertEquals(350L, PollSchedule.intervalFor(600L))
        assertEquals(350L, PollSchedule.intervalFor(2999L))
    }

    @Test
    fun `尾段用 600ms 覆盖晚出现的按钮`() {
        assertEquals(600L, PollSchedule.intervalFor(3000L))
        assertEquals(600L, PollSchedule.intervalFor(7999L))
    }

    @Test
    fun `总窗口为 8 秒且单窗口最多 12 次`() {
        assertEquals(8000L, PollSchedule.WINDOW_MS)
        assertEquals(12, PollSchedule.MAX_SCANS)
    }

    @Test
    fun `快速扫描保持基础节奏`() {
        assertEquals(150L, PollSchedule.adaptiveDelay(0L, 40L))
        assertEquals(600L, PollSchedule.adaptiveDelay(4000L, 40L))
    }

    @Test
    fun `慢扫描按耗时退避`() {
        assertEquals(400L, PollSchedule.adaptiveDelay(0L, 200L))
        assertEquals(1000L, PollSchedule.adaptiveDelay(1000L, 500L))
    }

    @Test
    fun `退避有上限`() {
        assertEquals(2000L, PollSchedule.adaptiveDelay(4000L, 5000L))
    }
}
