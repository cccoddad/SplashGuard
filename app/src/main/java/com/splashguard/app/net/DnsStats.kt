package com.splashguard.app.net

/**
 * DNS 热路径的轻量统计，只存在内存里，供诊断报告判断「慢在哪」。
 *
 * 不参与任何功能决策。v0.1.8 增加最大耗时和耗时桶，用来区分
 * 「平均 102ms 的正常上游往返」与「偶发 1.5 秒长尾/超时」。
 */
object DnsStats {

    val BUCKET_LIMITS = longArrayOf(20, 50, 100, 250, 500, 1000, 2000)
    val BUCKET_LABELS = arrayOf("<20", "<50", "<100", "<250", "<500", "<1000", "<2000", "≥2000")

    @Volatile var forwardCount: Long = 0L
        private set
    @Volatile var forwardFailures: Long = 0L
        private set
    @Volatile var totalForwardMs: Long = 0L
        private set
    @Volatile var lastForwardMs: Long = 0L
        private set
    @Volatile var maxForwardMs: Long = 0L
        private set
    @Volatile var blockTotal: Long = 0L
        private set
    @Volatile var blockCacheHits: Long = 0L
        private set

    private val buckets = LongArray(BUCKET_LIMITS.size + 1)

    fun onForward(elapsedMs: Long, ok: Boolean) {
        synchronized(this) {
            forwardCount++
            lastForwardMs = elapsedMs
            totalForwardMs += elapsedMs
            if (elapsedMs > maxForwardMs) maxForwardMs = elapsedMs
            if (!ok) forwardFailures++
            buckets[bucketIndex(elapsedMs)]++
        }
    }

    fun onBlock(cacheHit: Boolean) {
        synchronized(this) {
            blockTotal++
            if (cacheHit) blockCacheHits++
        }
    }

    fun avgForwardMs(): Long =
        if (forwardCount == 0L) 0L else totalForwardMs / forwardCount

    fun bucketCounts(): LongArray = synchronized(this) { buckets.copyOf() }

    fun reset() = synchronized(this) {
        forwardCount = 0L
        forwardFailures = 0L
        totalForwardMs = 0L
        lastForwardMs = 0L
        maxForwardMs = 0L
        blockTotal = 0L
        blockCacheHits = 0L
        buckets.fill(0L)
    }

    private fun bucketIndex(ms: Long): Int {
        for (i in BUCKET_LIMITS.indices) {
            if (ms < BUCKET_LIMITS[i]) return i
        }
        return BUCKET_LIMITS.size
    }
}
