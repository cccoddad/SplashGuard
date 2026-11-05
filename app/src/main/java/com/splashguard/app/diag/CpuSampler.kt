package com.splashguard.app.diag

import android.os.Process
import android.os.SystemClock
import java.io.File

/**
 * 进程 / 线程 CPU 时间采样，只读 `/proc/self`，不需要任何权限。
 *
 * 诊断报告里的「墙钟占空比」不等于 CPU：扫描大部分时间在等 binder 返回。
 * v0.1.8 用 [Process.getElapsedCpuTime] 给出本次进程的平均 CPU%，再用逐线程
 * utime+stime 找出是 `tun-reader`、`dns-worker` 还是无障碍主线程在消耗。
 *
 * 所有读取都包在 runCatching 里：部分 ROM 会限制 /proc，失败时只是少一段报告。
 */
object CpuSampler {

    data class ThreadCpu(val name: String, val cpuMs: Long)

    @Volatile private var markedCpuMs: Long = 0L
    @Volatile private var markedUptimeMs: Long = 0L
    @Volatile private var marked: Boolean = false

    /** 在 App.onCreate 调用，之后报告的 CPU% 只统计这段时间 */
    fun markStart() {
        markedCpuMs = Process.getElapsedCpuTime()
        markedUptimeMs = SystemClock.elapsedRealtime()
        marked = true
    }

    /** 进程整体 CPU 使用率（0–100+，多核可超过 100） */
    fun processCpuPercent(): Double {
        val cpuNow = Process.getElapsedCpuTime()
        val uptimeNow = SystemClock.elapsedRealtime()
        val cpu = if (marked) cpuNow - markedCpuMs else cpuNow
        val up = if (marked) uptimeNow - markedUptimeMs else uptimeNow
        if (up <= 0L) return 0.0
        return cpu.toDouble() * 100.0 / up.toDouble()
    }

    fun processCpuMs(): Long {
        val cpuNow = Process.getElapsedCpuTime()
        return if (marked) cpuNow - markedCpuMs else cpuNow
    }

    fun uptimeMs(): Long {
        val uptimeNow = SystemClock.elapsedRealtime()
        return if (marked) uptimeNow - markedUptimeMs else uptimeNow
    }

    /** 自线程创建以来的 utime+stime，按大小降序 */
    fun topThreads(limit: Int = 6): List<ThreadCpu> {
        val dir = File("/proc/self/task")
        val files = runCatching { dir.listFiles() }.getOrNull() ?: return emptyList()
        val clkTck = runCatching {
            android.system.Os.sysconf(android.system.OsConstants._SC_CLK_TCK).toLong()
        }.getOrDefault(100L).coerceAtLeast(1L)
        val list = ArrayList<ThreadCpu>(files.size)
        for (f in files) {
            val cpuMs = threadCpuMs(f, clkTck) ?: continue
            if (cpuMs <= 0L) continue
            val name = runCatching {
                File(f, "comm").readText().trim()
            }.getOrDefault("tid-${f.name}")
            list.add(ThreadCpu(name.ifBlank { "tid-${f.name}" }, cpuMs))
        }
        list.sortByDescending { it.cpuMs }
        return list.take(limit)
    }

    /**
     * /proc/self/task/<tid>/stat 第 14/15 个字段是 utime/stime（单位 USER_HZ）。
     * comm 里可能带空格和括号，所以从最后一个 ')' 之后开始切。
     */
    private fun threadCpuMs(taskDir: File, clkTck: Long): Long? = runCatching {
        val stat = File(taskDir, "stat").readText()
        val rp = stat.lastIndexOf(')')
        if (rp < 0) return@runCatching null
        val fields = stat.substring(rp + 2).trim().split(' ')
        // fields[0]=state，所以 utime=fields[11]，stime=fields[12]
        if (fields.size < 13) return@runCatching null
        val utime = fields[11].toLongOrNull() ?: return@runCatching null
        val stime = fields[12].toLongOrNull() ?: return@runCatching null
        (utime + stime) * 1000L / clkTck
    }.getOrNull()
}
