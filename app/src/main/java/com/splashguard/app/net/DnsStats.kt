package com.splashguard.app.net

/**
 * DNS 热路径的轻量统计，只存在内存里，供诊断报告判断「慢在哪」。
 *
 * 不参与任何功能决策；计数在并发下可能有几十的误差，够用即可。
 */
object DnsStats {

    @Volatile var forwardCount: Long = 0L
    @Volatile var forwardFailures: Long = 0L
    @Volatile var totalForwardMs: Long = 0L
    @Volatile var lastForwardMs: Long = 0L
    @Volatile var blockTotal: Long = 0L
    @Volatile var blockCacheHits: Long = 0L

    fun onForward(elapsedMs: Long, ok: Boolean) {
        forwardCount++
        lastForwardMs = elapsedMs
        totalForwardMs += elapsedMs
        if (!ok) forwardFailures++
    }

    fun onBlock(cacheHit: Boolean) {
        blockTotal++
        if (cacheHit) blockCacheHits++
    }

    fun avgForwardMs(): Long =
        if (forwardCount == 0L) 0L else totalForwardMs / forwardCount

    fun reset() {
        forwardCount = 0L
        forwardFailures = 0L
        totalForwardMs = 0L
        lastForwardMs = 0L
        blockTotal = 0L
        blockCacheHits = 0L
    }
}
