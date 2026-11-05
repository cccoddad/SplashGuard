package com.splashguard.app.a11y

import org.junit.Assert.assertEquals
import org.junit.Test

class ScanMetricsTest {

    @Test
    fun `平均与最大耗时`() {
        val m = ScanMetrics(capacity = 8)
        m.onScan(10)
        m.onScan(30)
        m.onScan(20)
        assertEquals(20L, m.avgMs())
        assertEquals(30L, m.maxMs)
        assertEquals(3L, m.scanCount)
    }

    @Test
    fun `百分位按最近样本计算`() {
        val m = ScanMetrics(capacity = 8)
        (1..10).forEach { m.onScan(it.toLong()) }
        // 只保留最近 8 个：3..10
        assertEquals(6L, m.percentile(50))
        assertEquals(10L, m.percentile(95))
    }

    @Test
    fun `耗时桶统计`() {
        val m = ScanMetrics(capacity = 8)
        m.onScan(10)   // <20
        m.onScan(60)   // <100
        m.onScan(400)  // <500
        m.onScan(3000) // ≥2000
        val b = m.bucketCounts()
        assertEquals(1L, b[0])
        assertEquals(1L, b[2])
        assertEquals(1L, b[4])
        assertEquals(1L, b[7])
    }

    @Test
    fun `reset 清空`() {
        val m = ScanMetrics(capacity = 8)
        m.onScan(100)
        m.reset()
        assertEquals(0L, m.scanCount)
        assertEquals(0L, m.totalMs)
        assertEquals(0L, m.maxMs)
        assertEquals(0L, m.avgMs())
    }
}
