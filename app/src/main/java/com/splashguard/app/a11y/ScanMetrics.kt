package com.splashguard.app.a11y

/**
 * 扫描耗时与结果的轻量统计，纯 Kotlin，可直接单测。
 *
 * 固定内存：只保留最近 [capacity] 次耗时（用于 P50/P95）和 8 个耗时桶，不会无限增长。
 */
class ScanMetrics(private val capacity: Int = 64) {

    init {
        require(capacity > 0) { "capacity must be positive" }
    }

    private val durations = IntArray(capacity)
    private var next = 0
    private var size = 0

    private val buckets = LongArray(8)

    @Volatile var scanCount: Long = 0L
        private set
    @Volatile var totalMs: Long = 0L
        private set
    @Volatile var maxMs: Long = 0L
        private set

    fun onScan(elapsedMs: Long) {
        val ms = elapsedMs.coerceIn(0L, Int.MAX_VALUE.toLong()).toInt()
        synchronized(this) {
            durations[next] = ms
            next = (next + 1) % capacity
            if (size < capacity) size++
            scanCount++
            totalMs += ms
            if (ms > maxMs) maxMs = ms.toLong()
            buckets[bucketIndex(ms)]++
        }
    }

    fun avgMs(): Long = if (scanCount == 0L) 0L else totalMs / scanCount

    /** 最近 [capacity] 次扫描的百分位；p 取 50 / 95 */
    fun percentile(p: Int): Long = synchronized(this) {
        if (size == 0) return 0L
        val sorted = IntArray(size)
        for (i in 0 until size) sorted[i] = durations[i]
        sorted.sort()
        // 最近秩法：p50/8 个样本取第 4 个，p95 取最后一个
        val idx = ((size * p + 99) / 100 - 1).coerceIn(0, size - 1)
        sorted[idx].toLong()
    }

    /** 耗时桶：<20 / <50 / <100 / <250 / <500 / <1000 / <2000 / ≥2000 ms */
    fun bucketCounts(): LongArray = synchronized(this) { buckets.copyOf() }

    fun reset() = synchronized(this) {
        next = 0
        size = 0
        buckets.fill(0L)
        scanCount = 0L
        totalMs = 0L
        maxMs = 0L
    }

    private fun bucketIndex(ms: Int): Int {
        for (i in BUCKET_LIMITS.indices) {
            if (ms < BUCKET_LIMITS[i]) return i
        }
        return BUCKET_LIMITS.size
    }

    companion object {
        val BUCKET_LIMITS = intArrayOf(20, 50, 100, 250, 500, 1000, 2000)
        val BUCKET_LABELS = arrayOf("<20", "<50", "<100", "<250", "<500", "<1000", "<2000", "≥2000")
    }
}
