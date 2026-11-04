package com.splashguard.app.diag

import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.view.accessibility.AccessibilityManager
import com.splashguard.app.core.BlockMode
import com.splashguard.app.core.Prefs
import com.splashguard.app.log.BlockLog
import com.splashguard.app.net.SplashGuardVpnService
import com.splashguard.app.rules.RuleStore
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 生成一份纯文本诊断报告，用于真机验收时把现场状态带回来。
 *
 * 报告只在本机生成，写入应用私有目录，由用户手动复制 / 分享。
 * 不含账号、IMEI、MAC、位置，也不含任何网络请求 —— 代码里没有任何上传路径。
 */
object DiagnosticReport {

    private const val MAX_LOG_LINES = 200

    private val stampFull = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)
    private val stampFile = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US)

    fun build(context: Context): String {
        val prefs = Prefs(context)
        val now = System.currentTimeMillis()
        val sb = StringBuilder(8192)

        sb.appendLine("SplashGuard 诊断报告")
        sb.appendLine("生成时间: ${stampFull.format(Date(now))}")
        sb.appendLine("=".repeat(48))
        sb.appendLine()

        // ---- 应用 ----
        val pkgInfo = runCatching {
            @Suppress("DEPRECATION")
            context.packageManager.getPackageInfo(context.packageName, 0)
        }.getOrNull()
        val verName = pkgInfo?.versionName ?: "?"
        val verCode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            pkgInfo?.longVersionCode ?: 0L
        } else {
            @Suppress("DEPRECATION") (pkgInfo?.versionCode ?: 0).toLong()
        }

        sb.appendLine("[应用]")
        sb.appendLine("版本: $verName ($verCode)")
        sb.appendLine("包名: ${context.packageName}")
        sb.appendLine("签名: ${signatureHash(context)}")
        sb.appendLine()

        // ---- 设备 ----
        sb.appendLine("[设备]")
        sb.appendLine("厂商: ${Build.MANUFACTURER}")
        sb.appendLine("型号: ${Build.MODEL}")
        sb.appendLine("品牌/设备: ${Build.BRAND} / ${Build.DEVICE}")
        sb.appendLine("系统版本: Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
        sb.appendLine("系统指纹: ${Build.DISPLAY}")
        sb.appendLine("ROM: ${romName()}")
        sb.appendLine("ABI: ${Build.SUPPORTED_ABIS.joinToString(", ")}")
        val dm = context.resources.displayMetrics
        sb.appendLine("屏幕: ${dm.widthPixels}x${dm.heightPixels} @${dm.densityDpi}dpi")
        sb.appendLine()

        // ---- 运行状态 ----
        sb.appendLine("[拦截状态]")
        sb.appendLine("隧道运行中: ${yesNo(SplashGuardVpnService.isRunning)}")
        sb.appendLine("用户开关(持久化): ${yesNo(prefs.enabled)}")
        sb.appendLine("拦截模式: ${if (prefs.blockMode == BlockMode.NXDOMAIN) "NXDOMAIN" else "0.0.0.0"}")
        sb.appendLine("上游 DNS: ${prefs.upstreamDns}")
        sb.appendLine("开机自启: ${yesNo(prefs.bootStart)}")
        sb.appendLine()

        // ---- 规则 ----
        sb.appendLine("[规则]")
        sb.appendLine("生效规则总数: ${RuleStore.ruleCount}")
        sb.appendLine("其中内置: ${RuleStore.builtinCount}")
        sb.appendLine("其中自定义: ${(RuleStore.ruleCount - RuleStore.builtinCount).coerceAtLeast(0)}")
        val userRules = prefs.userRules.lines().map { it.trim() }.filter { it.isNotEmpty() }
        if (userRules.isNotEmpty()) {
            sb.appendLine("自定义规则内容:")
            userRules.forEach { sb.appendLine("  $it") }
        }
        sb.appendLine()

        // ---- 分应用 ----
        sb.appendLine("[分应用]")
        sb.appendLine("模式: ${if (prefs.perAppMode.name == "ONLY_SELECTED") "仅选中的应用" else "全部应用"}")
        val selected = prefs.selectedApps
        if (prefs.perAppMode.name == "ONLY_SELECTED") {
            sb.appendLine("已选应用数: ${selected.size}")
            selected.sorted().forEach { sb.appendLine("  - ${labelOf(context, it)} ($it)") }
        } else {
            sb.appendLine("已选应用数: ${selected.size}（当前模式不生效，仅暂存）")
        }
        sb.appendLine()

        // ---- 无障碍 ----
        sb.appendLine("[无障碍 / 自动跳过]")
        val a11yOn = isA11yEnabled(context)
        sb.appendLine("系统已授权: ${yesNo(a11yOn)}")
        sb.appendLine("App 内记录: ${yesNo(prefs.a11yEnabled)}")
        sb.appendLine()

        // ---- 计数 ----
        sb.appendLine("[拦截计数]")
        sb.appendLine("今日拦截: ${prefs.todayCount()}")
        sb.appendLine("内存日志条数: ${BlockLog.snapshot().size}")
        sb.appendLine()

        // ---- 日志 ----
        sb.appendLine("[最近拦截日志]（最新在上，最多 $MAX_LOG_LINES 条）")
        val logs = BlockLog.snapshot().take(MAX_LOG_LINES)
        if (logs.isEmpty()) {
            sb.appendLine("(无记录)")
        } else {
            sb.appendLine("时间                 | 命中规则               | 域名                           | 应用")
            sb.appendLine("-".repeat(96))
            logs.forEach { e ->
                sb.appendLine(
                    buildString {
                        append(pad(stampFull.format(Date(e.time)), 20))
                        append(" | ")
                        append(pad(e.rule, 22))
                        append(" | ")
                        append(pad(e.host, 30))
                        append(" | ")
                        append(e.pkg ?: "-")
                    }
                )
            }
        }
        sb.appendLine()

        // ---- 说明 ----
        sb.appendLine("=".repeat(48))
        sb.appendLine("本报告由本机生成，未上传到任何服务器。")
        sb.appendLine("反馈问题时请说明：你执行了哪一条验收用例、期望结果、实际结果。")

        return sb.toString()
    }

    /** 写入私有目录，返回文件（供「保存到文件」使用） */
    fun writeToFile(context: Context, content: String): File {
        val dir = File(context.filesDir, "reports").apply { mkdirs() }
        val f = File(dir, "splashguard-${stampFile.format(Date())}.txt")
        f.writeText(content)
        return f
    }

    /** 签名证书 SHA-256 前 16 位，用于确认装的是同一个包（覆盖安装时签名不一致会装不上） */
    private fun signatureHash(context: Context): String = runCatching {
        val pm = context.packageManager
        val pkg = context.packageName
        val sigs = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            pm.getPackageInfo(pkg, PackageManager.GET_SIGNING_CERTIFICATES)
                .signingInfo?.apkContentsSigners
        } else {
            @Suppress("DEPRECATION")
            pm.getPackageInfo(pkg, PackageManager.GET_SIGNATURES).signatures
        }
        val bytes = sigs?.firstOrNull()?.toByteArray() ?: return@runCatching "未知"
        java.security.MessageDigest.getInstance("SHA-256")
            .digest(bytes)
            .joinToString("") { "%02x".format(it) }
            .take(16)
    }.getOrDefault("未知")

    private fun romName(): String = runCatching {        val cls = Class.forName("android.os.SystemProperties")
        val get = cls.getMethod("get", String::class.java)
        val miui = get.invoke(null, "ro.miui.ui.version.name") as? String
        val emui = get.invoke(null, "ro.build.version.emui") as? String
        val colorOs = get.invoke(null, "ro.build.version.opporom") as? String
        val originOs = get.invoke(null, "ro.vivo.os.version") as? String
        val harmony = get.invoke(null, "hw_sc.build.platform.version") as? String
        listOfNotNull(
            miui?.takeIf { it.isNotBlank() }?.let { "MIUI/HyperOS $it" },
            emui?.takeIf { it.isNotBlank() },
            colorOs?.takeIf { it.isNotBlank() }?.let { "ColorOS $it" },
            originOs?.takeIf { it.isNotBlank() }?.let { "OriginOS $it" },
            harmony?.takeIf { it.isNotBlank() }?.let { "HarmonyOS $it" }
        ).firstOrNull() ?: "原生/AOSP 或未识别"
    }.getOrDefault("原生/AOSP 或未识别")

    private fun isA11yEnabled(context: Context): Boolean = runCatching {
        val am = context.getSystemService(Context.ACCESSIBILITY_SERVICE) as? AccessibilityManager
            ?: return false
        am.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK)
            .any { it.resolveInfo.serviceInfo.packageName == context.packageName }
    }.getOrDefault(false)

    private fun labelOf(context: Context, pkg: String): String = runCatching {
        context.packageManager.getApplicationLabel(
            context.packageManager.getApplicationInfo(pkg, 0)
        ).toString()
    }.getOrDefault(pkg)

    private fun yesNo(b: Boolean) = if (b) "是" else "否"

    private fun pad(s: String, width: Int): String {
        // 中文按 2 个宽度算，保证表格对齐
        var w = 0
        for (c in s) w += if (c.code > 0x2E80) 2 else 1
        return if (w >= width) s else s + " ".repeat(width - w)
    }
}
