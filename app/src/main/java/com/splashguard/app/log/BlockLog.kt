package com.splashguard.app.log

import com.splashguard.app.core.Const
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 内存中的拦截日志环形缓冲，最多 [Const.MAX_LOG] 条。不写磁盘、不上传。
 */
object BlockLog {

    data class Entry(
        val time: Long,
        val pkg: String?,
        val host: String,
        val rule: String
    )

    private val entries = ArrayList<Entry>(Const.MAX_LOG)
    private val listeners = ArrayList<() -> Unit>()

    private val fmt = SimpleDateFormat("HH:mm:ss", Locale.US)

    @Synchronized
    fun record(pkg: String?, host: String, rule: String) {
        entries.add(0, Entry(System.currentTimeMillis(), pkg, host, rule))
        while (entries.size > Const.MAX_LOG) entries.removeAt(entries.size - 1)
        val snapshot = ArrayList(listeners)
        snapshot.forEach { runCatching { it() } }
    }

    @Synchronized
    fun snapshot(): List<Entry> = ArrayList(entries)

    @Synchronized
    fun clear() {
        entries.clear()
        val snapshot = ArrayList(listeners)
        snapshot.forEach { runCatching { it() } }
    }

    @Synchronized
    fun addListener(l: () -> Unit) {
        listeners.add(l)
    }

    @Synchronized
    fun removeListener(l: () -> Unit) {
        listeners.remove(l)
    }

    fun formatTime(t: Long): String = fmt.format(Date(t))
}
