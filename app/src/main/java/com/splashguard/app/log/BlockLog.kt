package com.splashguard.app.log

import android.content.Context
import android.os.Handler
import android.os.HandlerThread
import com.splashguard.app.core.Const
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 拦截日志环形缓冲，最多 [Const.MAX_LOG] 条。
 *
 * 持久化策略：内存里是主副本，同时异步追加写入应用私有目录的 TSV 文件。
 * 这样 App 进程被系统回收后，下次打开还能看到之前的拦截记录（真机验收必需）。
 * 文件只存在本机、可一键清空、不上传 —— 与 docs/privacy.md 的承诺一致。
 */
object BlockLog {

    data class Entry(
        val time: Long,
        val pkg: String?,
        val host: String,
        val rule: String
    )

    private const val FILE_NAME = "recent_blocks.tsv"
    private const val MAX_FILE_LINES = Const.MAX_LOG * 2

    private val entries = ArrayList<Entry>(Const.MAX_LOG)
    private val listeners = ArrayList<() -> Unit>()

    private val fmt = SimpleDateFormat("HH:mm:ss", Locale.US)

    @Volatile
    private var appContext: Context? = null

    private var ioThread: HandlerThread? = null
    private var ioHandler: Handler? = null

    /** 在 Application.onCreate 里调用一次，之后 record() 才会落盘 */
    @Synchronized
    fun init(context: Context) {
        if (appContext != null) return
        appContext = context.applicationContext

        val t = HandlerThread("blocklog-io").apply { start() }
        ioThread = t
        ioHandler = Handler(t.looper)

        loadFromDisk()
    }

    @Synchronized
    fun record(pkg: String?, host: String, rule: String) {
        val e = Entry(System.currentTimeMillis(), pkg, host, rule)
        synchronized(entries) {
            entries.add(0, e)
            while (entries.size > Const.MAX_LOG) entries.removeAt(entries.size - 1)
        }
        val snapshot = ArrayList(listeners)
        snapshot.forEach { runCatching { it() } }
        persistAsync(e)
    }

    @Synchronized
    fun snapshot(): List<Entry> = synchronized(entries) { ArrayList(entries) }

    /** 清空内存与磁盘 */
    @Synchronized
    fun clear() {
        synchronized(entries) { entries.clear() }
        ioHandler?.post {
            runCatching { logFile()?.delete() }
        }
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

    // ---------------- 磁盘 ----------------

    private fun logFile(): File? {
        val ctx = appContext ?: return null
        return File(ctx.filesDir, FILE_NAME)
    }

    private fun persistAsync(e: Entry) {
        val handler = ioHandler ?: return
        handler.post {
            val f = logFile() ?: return@post
            runCatching {
                val line = buildString {
                    append(e.time).append('\t')
                    append(e.pkg ?: "").append('\t')
                    append(e.host).append('\t')
                    append(e.rule).append('\n')
                }
                f.appendText(line)
                trimIfNeeded(f)
            }
        }
    }

    /** 文件行数超过上限时，只保留最新的 MAX_FILE_LINES 行 */
    private fun trimIfNeeded(f: File) {
        val lines = f.readLines()
        if (lines.size <= MAX_FILE_LINES) return
        val kept = lines.takeLast(MAX_FILE_LINES)
        f.writeText(kept.joinToString("\n", postfix = "\n"))
    }

    private fun loadFromDisk() {
        val f = logFile() ?: return
        if (!f.exists()) return
        runCatching {
            val loaded = ArrayList<Entry>(Const.MAX_LOG)
            f.useLines { seq ->
                seq.forEach { raw ->
                    if (raw.isBlank()) return@forEach
                    val parts = raw.split('\t')
                    if (parts.size < 4) return@forEach
                    val time = parts[0].toLongOrNull() ?: return@forEach
                    val pkg = parts[1].takeIf { it.isNotEmpty() }
                    loaded.add(Entry(time, pkg, parts[2], parts[3]))
                }
            }
            // 文件是追加写的，末尾最新；内存按「最新在前」排列
            loaded.sortByDescending { it.time }
            synchronized(entries) {
                entries.clear()
                entries.addAll(loaded.take(Const.MAX_LOG))
            }
        }
    }
}
