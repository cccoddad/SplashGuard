package com.splashguard.app.net

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.VpnService
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.splashguard.app.R
import com.splashguard.app.core.BlockMode
import com.splashguard.app.core.Const
import com.splashguard.app.core.PerAppMode
import com.splashguard.app.core.Prefs
import com.splashguard.app.diag.SplashSession
import com.splashguard.app.log.BlockLog
import com.splashguard.app.rules.RuleStore
import com.splashguard.app.ui.MainActivity
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.util.concurrent.Executors

/**
 * 本地 DNS 过滤隧道。
 *
 * 关键设计：只把 DNS 目标地址（虚拟解析器 + 常见公共 DNS）加入路由表，
 * 因此**只有 DNS 报文进入本进程**，其他流量全部走物理网卡。
 * 这既保证近乎零耗电，也不会降低用户网速。
 */
class SplashGuardVpnService : VpnService() {

    @Volatile
    private var running = false
    /** 每次重建隧道递增，旧读循环据此自行退出，避免新旧两个 reader 抢同一个 tun */
    private var generation = 0
    private var tun: ParcelFileDescriptor? = null
    private var worker: Thread? = null
    private var outStream: FileOutputStream? = null
    private val writeLock = Any()
    private lateinit var prefs: Prefs

    private val pool = Executors.newFixedThreadPool(6) { r ->
        Thread(r, "dns-worker").apply { isDaemon = true }
    }

    private val ownerCache = HashMap<Int, Pair<String?, Long>>()

    /**
     * DNS 响应缓存。
     *
     * 手机上同一域名会被反复查询（多 SDK 共用 CDN、页面重试、App 切前台），
     * 每次未命中都要 new DatagramSocket + protect() + 上游往返，代价不小。
     * 缓存命中直接本地回包，省掉整条链路。
     */
    private class CacheEntry(val payload: ByteArray, val expireAt: Long)

    private val cacheLock = Any()
    private val dnsCache = object : LinkedHashMap<String, CacheEntry>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, CacheEntry>?): Boolean =
            size > MAX_CACHE_ENTRIES
    }

    @Volatile
    private var upstreamAddr: InetAddress? = null

    @Volatile
    private var upstreamAddrFor: String? = null

    override fun onCreate() {
        super.onCreate()
        prefs = Prefs(this)
        RuleStore.reload(this)
        ensureChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.i(TAG, "onStartCommand action=${intent?.action} startId=$startId running=$running")
        return when (intent?.action) {
            Const.ACTION_STOP -> {
                stopVpn()
                stopSelf()
                START_NOT_STICKY
            }
            Const.ACTION_RESTART -> {
                // 分应用 / 拦截模式变了：先拆旧隧道，稍等系统回收后再建新的。
                // 立刻 establish() 会撞上"旧会话还没拆完"，所以给个退避 + 重试。
                stopVpn(keepForeground = true)
                mainHandler.postDelayed({ startVpn() }, REBUILD_DELAY_MS)
                START_STICKY
            }
            else -> {
                startVpn()
                START_STICKY
            }
        }
    }

    private fun startVpn(attempt: Int = 0) {
        Log.i(TAG, "startVpn enter, running=$running gen=$generation attempt=$attempt")
        if (running) return

        // 通知必须尽早发出，避免 startForegroundService 5 秒超时
        if (attempt == 0) {
            startForeground(Const.NOTIF_ID, buildNotification(getString(R.string.notif_starting)))
        }

        if (prepare(this) != null) {
            // 需要用户交互，服务里没法弹授权框
            Log.w(TAG, "VPN permission not granted, abort")
            prefs.enabled = false
            stopSelf()
            return
        }

        val fd = buildTun()
        if (fd == null) {
            if (attempt < MAX_ESTABLISH_RETRY) {
                // 刚拆掉的旧会话可能还没被系统回收，退避后重试
                Log.w(TAG, "establish() returned null, retry ${attempt + 1}/$MAX_ESTABLISH_RETRY")
                mainHandler.postDelayed({ startVpn(attempt + 1) }, REBUILD_DELAY_MS)
                return
            }
            Log.e(TAG, "establish() returned null after retries, abort")
            prefs.enabled = false
            updateNotification(getString(R.string.notif_failed))
            stopSelf()
            return
        }

        tun = fd
        running = true
        isRunning = true
        prefs.enabled = true

        val gen = ++generation
        Log.i(TAG, "tunnel up, gen=$gen fd=${fd.fd}")
        worker = Thread({ loop(fd, gen) }, "tun-reader-$gen").apply {
            isDaemon = true
            start()
        }
        updateNotification(getString(R.string.notif_running))
        notifyState()
    }

    private fun buildTun(): ParcelFileDescriptor? {
        val builder = Builder()
            .setSession(getString(R.string.app_name))
            .setMtu(Const.MTU)
            .addAddress(Const.VIRTUAL_TUN_ADDR, 32)
            .addDnsServer(Const.VIRTUAL_DNS)
            // 只把 DNS 地址放进路由表 —— 这是「零耗电」的关键
            .addRoute(Const.VIRTUAL_DNS, 32)

        // 让硬编码公共 DNS 的 App 也把查询交给我们
        Const.COMMON_DNS.forEach { dns ->
            runCatching { builder.addRoute(dns, 32) }
        }

        val selected = prefs.selectedApps.filter { it != packageName }
        val useAllowList = prefs.perAppMode == PerAppMode.ONLY_SELECTED && selected.isNotEmpty()
        if (useAllowList) {
            selected.forEach { pkg ->
                runCatching { builder.addAllowedApplication(pkg) }
                    .onFailure { Log.w(TAG, "addAllowedApplication($pkg) failed: ${it.message}") }
            }
        } else {
            // 全应用模式：排除自己，避免自我回环
            runCatching { builder.addDisallowedApplication(packageName) }
        }

        return try {
            builder.establish()
        } catch (e: Exception) {
            Log.e(TAG, "establish failed", e)
            null
        }
    }

    private fun loop(fd: ParcelFileDescriptor, gen: Int) {
        val input = try {
            FileInputStream(fd.fileDescriptor)
        } catch (e: Exception) {
            Log.e(TAG, "reader gen=$gen cannot open stream: ${e.message}")
            onReaderDead(gen)
            return
        }
        val out = try {
            FileOutputStream(fd.fileDescriptor)
        } catch (e: Exception) {
            Log.e(TAG, "reader gen=$gen cannot open output: ${e.message}")
            onReaderDead(gen)
            return
        }
        synchronized(writeLock) { if (gen == generation) outStream = out }
        Log.i(TAG, "reader started gen=$gen")

        val buf = ByteArray(32767)
        var zeroStreak = 0
        while (running && gen == generation) {
            val n = try {
                input.read(buf)
            } catch (e: IOException) {
                Log.i(TAG, "reader gen=$gen read stopped: ${e.message}")
                break
            }
            if (n < 0) {
                Log.i(TAG, "reader gen=$gen EOF, exit")
                break
            }
            if (n == 0) {
                // 0 只表示「当前没有报文」，不等于 fd 失效。v0.1.5 曾在这里连续 200 次
                // （约 2 秒）就 break，某些机型空闲时 read 返回 0，结果隧道自己关掉。
                // 现在只在连续空读后检查 fd 是否仍有效；有效就继续等，绝不因空闲拆隧道。
                zeroStreak++
                if (zeroStreak >= ZERO_READS_BEFORE_FD_CHECK) {
                    zeroStreak = 0
                    val valid = runCatching { tun?.fileDescriptor?.valid() == true }.getOrDefault(false)
                    if (!valid) {
                        Log.w(TAG, "reader gen=$gen fd invalid after idle, exit")
                        break
                    }
                    Log.i(TAG, "reader gen=$gen idle zero-reads, fd still valid")
                }
                runCatching { Thread.sleep(ZERO_READ_BACKOFF_MS) }
                continue
            }
            zeroStreak = 0
            val copy = buf.copyOf(n)
            runCatching { pool.execute { handle(copy) } }
        }
        Log.i(TAG, "reader exit gen=$gen (running=$running, cur=$generation)")
        onReaderDead(gen)
    }

    /**
     * 读循环意外退出时的兜底：隧道网络还在、却没人读 = 全系统 DNS 黑洞（表现为断网）。
     * 这里主动拆掉隧道，让系统回落到物理网络的 DNS，宁可"不拦"也不能"断网"。
     */
    private fun onReaderDead(gen: Int) {
        if (gen != generation) return       // 正常重启换代的退出，忽略
        if (!running) return                // 主动停止，忽略
        Log.e(TAG, "reader gen=$gen died unexpectedly, tearing down tunnel to avoid DNS blackhole")
        stopVpn()
        stopSelf()
    }

    private fun handle(packet: ByteArray) {
        val info = PacketBuilder.udpInfo(packet, packet.size) ?: return
        if (info.dstPort != Const.DNS_PORT) return
        if (info.payloadLength <= 0) return

        val payload = packet.copyOfRange(info.payloadOffset, info.payloadOffset + info.payloadLength)
        val question = DnsPacket.parseQuestion(payload, payload.size) ?: return
        val rule = RuleStore.match(question.name)

        if (rule != null) {
            val blockKey = "B|" + question.name + "|" + question.qType
            cacheGet(blockKey, DnsPacket.queryId(payload))?.let { cached ->
                DnsStats.onBlock(cacheHit = true)
                // 缓存命中也要记归因，否则「同一个域名 60 秒内第二次查询」会让开屏归因为 0
                SplashSession.recordBlock(pkgFor(info), question.name, rule)
                reply(packet, cached)
                return
            }

            val resp = DnsPacket.buildBlockResponse(payload, payload.size, prefs.blockMode)
            if (resp.isNotEmpty()) {
                blockCachePut(blockKey, resp)
                DnsStats.onBlock(cacheHit = false)
                val pkg = pkgFor(info)
                BlockLog.record(pkg, question.name, rule)
                SplashSession.recordBlock(pkg, question.name, rule)
                prefs.bumpBlockCount()
            }
            reply(packet, resp)
            return
        }

        // 缓存键必须带 qType，否则 AAAA 查询会拿到 A 记录
        val key = question.name + "|" + question.qType
        cacheGet(key, DnsPacket.queryId(payload))?.let { cached ->
            reply(packet, cached)
            return
        }

        val resp = forward(payload, prefs.upstreamDns) ?: return
        cachePut(key, resp)
        reply(packet, resp)
    }

    private fun reply(packet: ByteArray, responsePayload: ByteArray) {
        if (responsePayload.isEmpty()) return
        val wrapped = PacketBuilder.wrapUdpResponse(packet, packet.size, responsePayload)
        if (wrapped.isEmpty()) return
        writeToTun(wrapped)
    }

    // ---------------- DNS 缓存 ----------------

    private fun cacheGet(key: String, queryId: Int): ByteArray? {
        val e = synchronized(cacheLock) { dnsCache[key] } ?: return null
        if (SystemClock.elapsedRealtime() > e.expireAt) {
            synchronized(cacheLock) { dnsCache.remove(key) }
            return null
        }
        // 改回本次查询的 transaction ID —— 复用别人的报文必须换 ID，否则客户端会丢弃
        val copy = e.payload.copyOf()
        if (copy.size >= 2) {
            copy[0] = ((queryId shr 8) and 0xFF).toByte()
            copy[1] = (queryId and 0xFF).toByte()
        }
        return copy
    }

    private fun cachePut(key: String, response: ByteArray) {
        // 只缓存干净的肯定应答：错误码 / 空应答 / 超大包都不适合缓存
        if (response.size < 12 || response.size > MAX_CACHE_PACKET) return
        if (DnsPacket.rcode(response) != 0) return
        if (DnsPacket.answerCount(response) < 1) return
        val entry = CacheEntry(response, SystemClock.elapsedRealtime() + CACHE_TTL_MS)
        synchronized(cacheLock) { dnsCache[key] = entry }
    }

    /**
     * 拦截应答缓存：0.0.0.0 / NXDOMAIN 不满足 [cachePut] 的「NOERROR + 有答案」条件，
     * 单独缓存 60 秒。广告 SDK 重试同一域名时直接回包，不再重复记日志/写计数。
     */
    private fun blockCachePut(key: String, response: ByteArray) {
        if (response.size < 12) return
        val entry = CacheEntry(response, SystemClock.elapsedRealtime() + BLOCK_CACHE_TTL_MS)
        synchronized(cacheLock) { dnsCache[key] = entry }
    }

    /** 转发到上游。socket 必须 protect()，否则会再次进入自己的 tun 形成死循环。 */
    private fun forward(payload: ByteArray, upstream: String): ByteArray? {
        val start = SystemClock.elapsedRealtime()
        var ok = false
        return try {
            DatagramSocket().use { socket ->
                if (!protect(socket)) return null
                socket.soTimeout = Const.UPSTREAM_TIMEOUT_MS
                val addr = upstreamAddress(upstream) ?: return null
                socket.send(DatagramPacket(payload, payload.size, addr, Const.DNS_PORT))
                val buf = ByteArray(65535)
                val resp = DatagramPacket(buf, buf.size)
                socket.receive(resp)
                val out = buf.copyOf(resp.length)
                ok = true
                out
            }
        } catch (e: Exception) {
            Log.w(TAG, "forward to $upstream failed: ${e.message}")
            null
        } finally {
            DnsStats.onForward(SystemClock.elapsedRealtime() - start, ok)
        }
    }

    /** 上游地址是 IP 字面量，解析结果不会变，缓存起来避免每条查询都走一次 */
    private fun upstreamAddress(upstream: String): InetAddress? {
        val cached = upstreamAddr
        if (cached != null && upstream == upstreamAddrFor) return cached
        val addr = runCatching { InetAddress.getByName(upstream) }.getOrNull() ?: return null
        upstreamAddr = addr
        upstreamAddrFor = upstream
        return addr
    }

    private fun writeToTun(data: ByteArray) {
        val out = outStream ?: return
        synchronized(writeLock) {
            try {
                out.write(data)
            } catch (e: IOException) {
                Log.w(TAG, "tun write failed: ${e.message}")
            }
        }
    }

    /**
     * 尽力识别是哪个 App 发的查询（API 29+）。失败返回 null，日志显示「未知」。
     * 结果按源端口缓存 30 秒，避免每条查询都走一次 IPC。
     */
    private fun pkgFor(info: PacketBuilder.UdpInfo): String? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return null
        val now = SystemClock.elapsedRealtime()
        synchronized(ownerCache) {
            ownerCache[info.srcPort]?.let { (pkg, at) ->
                if (now - at < 30_000) return pkg
            }
        }
        val pkg = try {
            val cm = getSystemService(ConnectivityManager::class.java) ?: return null
            val uid = cm.getConnectionOwnerUid(
                17,
                PacketBuilder.socketAddress(info.srcIp, info.srcPort),
                PacketBuilder.socketAddress(info.dstIp, info.dstPort)
            )
            packageManager.getPackagesForUid(uid)?.firstOrNull()
        } catch (e: Exception) {
            null
        }
        synchronized(ownerCache) {
            if (ownerCache.size > 256) ownerCache.clear()
            ownerCache[info.srcPort] = pkg to now
        }
        return pkg
    }

    private fun stopVpn(keepForeground: Boolean = false) {
        // 先让旧读循环失效，再关 fd 解除阻塞，最后等它退出，避免新旧 reader 同时抓着 tun
        generation++
        running = false
        isRunning = false
        prefs.enabled = false
        runCatching { tun?.close() }
        runCatching { worker?.join(500) }
        worker = null
        tun = null
        synchronized(writeLock) {
            runCatching { outStream?.close() }
            outStream = null
        }
        if (!keepForeground) runCatching { stopForeground(Service.STOP_FOREGROUND_REMOVE) }
        notifyState()
        Log.i(TAG, "stopped, gen=$generation keepFg=$keepForeground")
    }

    override fun onRevoke() {
        // 另一个 VPN 抢占了隧道，或被系统撤销
        stopVpn()
        stopSelf()
        super.onRevoke()
    }

    override fun onDestroy() {
        stopVpn()
        pool.shutdownNow()
        super.onDestroy()
    }

    // ---------------- 通知 ----------------

    private fun ensureChannel() {
        val nm = getSystemService(NotificationManager::class.java) ?: return
        val ch = NotificationChannel(
            Const.CHANNEL_ID,
            getString(R.string.notif_channel),
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            setShowBadge(false)
            enableVibration(false)
            setSound(null, null)
            description = getString(R.string.notif_channel_desc)
        }
        nm.createNotificationChannel(ch)
    }

    private fun buildNotification(text: String): Notification {
        val openIntent = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val stopIntent = PendingIntent.getService(
            this, 1,
            Intent(this, SplashGuardVpnService::class.java).setAction(Const.ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return NotificationCompat.Builder(this, Const.CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_shield)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setContentIntent(openIntent)
            .addAction(0, getString(R.string.action_stop), stopIntent)
            .build()
    }

    private fun updateNotification(text: String) {
        runCatching {
            val nm = getSystemService(NotificationManager::class.java)
            nm?.notify(Const.NOTIF_ID, buildNotification(text))
        }
    }

    companion object {
        private const val TAG = "SplashGuardVpn"

        /** 拆旧隧道到建新隧道之间的退避时间 */
        private const val REBUILD_DELAY_MS = 350L
        /** establish() 最多重试次数（旧 VPN 会话可能还没被系统回收） */
        private const val MAX_ESTABLISH_RETRY = 3

        /** DNS 缓存条数上限 */
        private const val MAX_CACHE_ENTRIES = 256
        /** 缓存存活时间。刻意保守：太长会让 CDN 调度变钝，太短则起不到省电作用 */
        private const val CACHE_TTL_MS = 45_000L
        /** 拦截应答缓存时间，与 DnsPacket 里拦截应答的 TTL 保持一致 */
        private const val BLOCK_CACHE_TTL_MS = 60_000L
        /** 超过这个大小的应答不缓存（多为超长 TXT，命中率低） */
        private const val MAX_CACHE_PACKET = 1024

        /**
         * 连续空读达到这个次数（约 50 秒）才检查一次 fd 是否还有效。
         * 0 只代表「暂时没有报文」，不能作为拆隧道的依据。
         */
        private const val ZERO_READS_BEFORE_FD_CHECK = 1000
        /**
         * 每次空读让出 CPU 的时间。
         * 某些机型空闲时 read 会持续返回 0；10ms 退避等于每秒 100 次唤醒，
         * 实测空闲仍有约 3% 一个核。50ms 把空闲唤醒降到 20 次/秒，
         * DNS 最坏只多等 50ms，远小于一次上游往返。
         */
        private const val ZERO_READ_BACKOFF_MS = 50L

        @Volatile
        var isRunning: Boolean = false
            private set

        @Volatile
        private var stateListener: (() -> Unit)? = null

        private val mainHandler = Handler(Looper.getMainLooper())

        fun setStateListener(l: (() -> Unit)?) {
            stateListener = l
        }

        private fun notifyState() {
            val l = stateListener ?: return
            mainHandler.post { runCatching { l() } }
        }

        fun start(context: Context) {
            val intent = Intent(context, SplashGuardVpnService::class.java)
                .setAction(Const.ACTION_START)
            ContextCompat.startForegroundService(context, intent)
        }

        fun stop(context: Context) {
            val intent = Intent(context, SplashGuardVpnService::class.java)
                .setAction(Const.ACTION_STOP)
            runCatching { context.startService(intent) }
        }

        /** 配置变更后重建隧道（保持"开着"的状态） */
        fun restart(context: Context) {
            if (!isRunning) return
            val intent = Intent(context, SplashGuardVpnService::class.java)
                .setAction(Const.ACTION_RESTART)
            runCatching { ContextCompat.startForegroundService(context, intent) }
        }
    }
}
