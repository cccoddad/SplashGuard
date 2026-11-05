package com.splashguard.app.diag

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SplashSessionTest {

    @After
    fun tearDown() {
        SplashSession.clear()
    }

    @Test
    fun `按包名和窗口时间筛选拦截记录`() {
        SplashSession.recordBlock("com.a", "gdt.qq.com", "gdt.qq.com", at = 1_000L)
        SplashSession.recordBlock("com.a", "e.qq.com", "e.qq.com", at = 5_000L)
        SplashSession.recordBlock("com.b", "adkwai.com", "adkwai.com", at = 5_000L)

        val blocks = SplashSession.blocksForWindow(
            pkg = "com.a", windowStartAt = 4_000L, lookbackMs = 3_000L, now = 6_000L,
        )
        assertEquals(2, blocks.size)
        assertTrue(blocks.any { it.host == "gdt.qq.com" })
        assertTrue(blocks.any { it.host == "e.qq.com" })
    }

    @Test
    fun `窗口之前的旧记录不计入`() {
        SplashSession.recordBlock("com.a", "old.example.com", "old", at = 1_000L)
        SplashSession.recordBlock("com.a", "new.example.com", "new", at = 9_000L)
        val blocks = SplashSession.blocksForWindow(
            pkg = "com.a", windowStartAt = 8_000L, lookbackMs = 3_000L, now = 10_000L,
        )
        assertEquals(1, blocks.size)
        assertEquals("new.example.com", blocks.first().host)
    }

    @Test
    fun `同一 App 同一域名 60 秒内只记一次`() {
        SplashSession.recordBlock("com.a", "gdt.qq.com", "gdt.qq.com", at = 1_000L)
        SplashSession.recordBlock("com.a", "gdt.qq.com", "gdt.qq.com", at = 2_000L)
        assertEquals(1, SplashSession.size())
        SplashSession.recordBlock("com.a", "gdt.qq.com", "gdt.qq.com", at = 70_000L)
        assertEquals(2, SplashSession.size())
    }

    @Test
    fun `不同 App 的同名域名分别记录`() {
        SplashSession.recordBlock("com.a", "gdt.qq.com", "gdt.qq.com", at = 1_000L)
        SplashSession.recordBlock("com.b", "gdt.qq.com", "gdt.qq.com", at = 1_000L)
        assertEquals(2, SplashSession.size())
    }

    @Test
    fun `窗口 15 秒之后的拦截不计入`() {
        SplashSession.recordBlock("com.a", "early.example.com", "r", at = 9_000L)
        SplashSession.recordBlock("com.a", "late.example.com", "r", at = 30_000L)
        val blocks = SplashSession.blocksForWindow(
            pkg = "com.a", windowStartAt = 8_000L, lookbackMs = 3_000L, now = 40_000L,
        )
        assertEquals(1, blocks.size)
        assertEquals("early.example.com", blocks.first().host)
    }

    @Test
    fun `没有窗口时不返回记录`() {
        SplashSession.recordBlock("com.a", "gdt.qq.com", "gdt.qq.com", at = 5_000L)
        assertTrue(SplashSession.blocksForWindow(null, 1_000L).isEmpty())
        assertTrue(SplashSession.blocksForWindow("com.a", 0L).isEmpty())
    }

    @Test
    fun `最多保留 80 条`() {
        repeat(100) { i ->
            SplashSession.recordBlock("com.a", "h$i.example.com", "r", at = i.toLong())
        }
        assertEquals(80, SplashSession.size())
    }
}
