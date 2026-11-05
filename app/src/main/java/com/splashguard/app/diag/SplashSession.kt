package com.splashguard.app.diag

import java.util.ArrayDeque

/**
 * 「这次开屏到底发生了什么」的轻量关联记录。
 *
 * DNS 层在命中规则时记一条（时间 / 应用 / 域名 / 规则），无障碍层在窗口开始时记一个时间点；
 * 诊断报告按「窗口开始前 3 秒 ~ 现在」统计同一 App 的拦截数，
 * 用来区分「DNS 没拦住」「拦住了但素材已缓存」「无障碍没点中」。
 *
 * 纯内存、最多 80 条，不落盘、不联网。
 */
object SplashSession {

    data class Block(val at: Long, val pkg: String?, val host: String, val rule: String)

    private const val MAX_ENTRIES = 80
    /** 窗口开始后最多归因 15 秒，覆盖 8 秒轮询 + 余量，避免把日常信息流广告算进来 */
    private const val MAX_WINDOW_ATTRIBUTION_MS = 15_000L

    private val entries = ArrayDeque<Block>()

    fun recordBlock(pkg: String?, host: String, rule: String, at: Long = System.currentTimeMillis()) {
        synchronized(this) {
            // 同一个 App + 域名的缓存命中会反复走到这里，60 秒内只记一次
            if (entries.any { it.pkg == pkg && it.host == host && at - it.at in 0..60_000L }) {
                return
            }
            entries.addLast(Block(at, pkg, host, rule))
            while (entries.size > MAX_ENTRIES) entries.removeFirst()
        }
    }

    /**
     * 当前窗口关联的拦截记录。
     * DNS 查询通常发生在窗口事件之前，所以向前多看 [lookbackMs]。
     */
    fun blocksForWindow(
        pkg: String?,
        windowStartAt: Long,
        lookbackMs: Long = 3000L,
        now: Long = System.currentTimeMillis(),
    ): List<Block> {
        if (pkg == null || windowStartAt <= 0L) return emptyList()
        val from = windowStartAt - lookbackMs
        // 只统计本次开屏附近：否则用户在同一 App 里停留几分钟后的信息流广告也会被算进来
        val to = minOf(now, windowStartAt + MAX_WINDOW_ATTRIBUTION_MS)
        return synchronized(this) {
            entries.filter { it.pkg == pkg && it.at in from..to }
        }
    }

    fun clear() = synchronized(this) { entries.clear() }

    fun size(): Int = synchronized(this) { entries.size }
}
