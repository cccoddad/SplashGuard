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
import java.util.concurrent.atomic.AtomicInteger

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
    private var tun: ParcelFileDescriptor? = null
    private var worker: Thread? = null
    private var outStream: FileOutputStream? = null
    private val writeLock = Any()
    private lateinit var prefs: Prefs

    private val pool = Executors.newFixedThreadPool(6) { r ->
        Thread(r, "dns-worker").apply { isDaemon = true }
    }

    private val ownerCache = HashMap<Int, Pair<String?, Long>>()
    private val blockedSinceNotify = AtomicInteger(0)

    override fun onCreate() {
        super.onCreate()
        prefs = Prefs(this)
        RuleStore.reload(this)
        ensureChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        return when (intent?.action) {
            Const.ACTION_STOP -> {
                stopVpn()
                stopSelf()
                START_NOT_STICKY
            }
            Const.ACTION_RESTART -> {
                // 分应用 / 拦截模式变了，需要重建隧道
                stopVpn()
                startVpn()
                START_STICKY
            }
            else -> {
                startVpn()
                START_STICKY
            }
        }
    }

    private fun startVpn() {
        if (running) return

        // 通知必须尽早发出，避免 startForegroundService 5 秒超时
        startForeground(Const.NOTIF_ID, buildNotification(getString(R.string.notif_starting)))

        if (prepare(this) != null) {
            // 需要用户交互，服务里没法弹授权框
            Log.w(TAG, "VPN permission not granted, abort")
            prefs.enabled = false
            stopSelf()
            return
        }

        val fd = buildTun()
        if (fd == null) {
            Log.e(TAG, "establish() returned null")
            prefs.enabled = false
            updateNotification(getString(R.string.notif_failed))
            stopSelf()
            return
        }

        tun = fd
        running = true
        isRunning = true
        prefs.enabled = true

        worker = Thread({ loop(fd) }, "tun-reader").apply {
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

    private fun loop(fd: ParcelFileDescriptor) {
        val input = FileInputStream(fd.fileDescriptor)
        outStream = FileOutputStream(fd.fileDescriptor)
        val buf = ByteArray(32767)
        while (running) {
            val n = try {
                input.read(buf)
            } catch (e: IOException) {
                if (running) Log.w(TAG, "tun read failed: ${e.message}")
                break
            }
            if (n <= 0) continue
            val copy = buf.copyOf(n)
            runCatching { pool.execute { handle(copy) } }
        }
        Log.i(TAG, "read loop exited")
    }

    private fun handle(packet: ByteArray) {
        val info = PacketBuilder.udpInfo(packet, packet.size) ?: return
        if (info.dstPort != Const.DNS_PORT) return
        if (info.payloadLength <= 0) return

        val payload = packet.copyOfRange(info.payloadOffset, info.payloadOffset + info.payloadLength)
        val question = DnsPacket.parseQuestion(payload, payload.size) ?: return
        val rule = RuleStore.match(question.name)

        val responsePayload: ByteArray = if (rule != null) {
            val mode = prefs.blockMode
            val resp = DnsPacket.buildBlockResponse(payload, payload.size, mode)
            if (resp.isNotEmpty()) {
                BlockLog.record(pkgFor(info), question.name, rule)
                prefs.bumpBlockCount()
                blockedSinceNotify.incrementAndGet()
                if (blockedSinceNotify.get() % 5 == 0) {
                    updateNotification(getString(R.string.notif_running))
                }
            }
            resp
        } else {
            forward(payload, prefs.upstreamDns) ?: return
        }

        if (responsePayload.isEmpty()) return
        val wrapped = PacketBuilder.wrapUdpResponse(packet, packet.size, responsePayload)
        if (wrapped.isEmpty()) return
        writeToTun(wrapped)
    }

    /** 转发到上游。socket 必须 protect()，否则会再次进入自己的 tun 形成死循环。 */
    private fun forward(payload: ByteArray, upstream: String): ByteArray? {
        return try {
            DatagramSocket().use { socket ->
                if (!protect(socket)) return null
                socket.soTimeout = Const.UPSTREAM_TIMEOUT_MS
                val addr = InetAddress.getByName(upstream)
                socket.send(DatagramPacket(payload, payload.size, addr, Const.DNS_PORT))
                val buf = ByteArray(4096)
                val resp = DatagramPacket(buf, buf.size)
                socket.receive(resp)
                buf.copyOf(resp.length)
            }
        } catch (e: Exception) {
            Log.w(TAG, "forward to $upstream failed: ${e.message}")
            null
        }
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

    private fun stopVpn() {
        running = false
        isRunning = false
        prefs.enabled = false
        runCatching { worker?.interrupt() }
        worker = null
        runCatching { tun?.close() }
        tun = null
        runCatching { outStream?.close() }
        outStream = null
        runCatching { stopForeground(Service.STOP_FOREGROUND_REMOVE) }
        notifyState()
        Log.i(TAG, "stopped")
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
