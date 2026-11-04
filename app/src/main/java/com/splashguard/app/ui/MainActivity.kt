package com.splashguard.app.ui

import android.Manifest
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Typeface
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.provider.Settings
import android.view.View
import android.view.View.MeasureSpec
import android.view.accessibility.AccessibilityManager
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.splashguard.app.R
import com.splashguard.app.a11y.A11yController
import com.splashguard.app.a11y.A11yState
import com.splashguard.app.core.Prefs
import com.splashguard.app.databinding.ActivityMainBinding
import com.splashguard.app.diag.DiagnosticReport
import com.splashguard.app.log.BlockLog
import com.splashguard.app.net.SplashGuardVpnService
import com.splashguard.app.rules.RuleStore

class MainActivity : AppCompatActivity() {

    private lateinit var b: ActivityMainBinding
    private lateinit var prefs: Prefs
    private lateinit var adapter: LogAdapter

    private var suppressSwitch = false
    private var pendingEnable = false
    private var repairingA11y = false
    private var lastRepairAt = 0L
    private var promptedA11yNotConnected = false
    private val labelCache = HashMap<String, String>()

    private val vpnPermission =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
            if (!pendingEnable) return@registerForActivityResult
            pendingEnable = false
            if (VpnService.prepare(this) == null) {
                startTunnel()
            } else {
                toast(getString(R.string.toast_vpn_denied))
                refresh()
            }
        }

    private val notifPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    private val stateListener: () -> Unit = {
        runOnUiThread { refresh() }
    }

    private val logListener: () -> Unit = {
        runOnUiThread {
            // 除了列表，计数也要跟着走 —— 否则拦截发生时首页一直显示 0
            b.tvToday.text = prefs.todayCount().toString()
            refreshLog()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityMainBinding.inflate(layoutInflater)
        setContentView(b.root)

        prefs = Prefs(this)
        RuleStore.reload(this)

        adapter = LogAdapter { pkg -> labelOf(pkg) }
        b.recycler.layoutManager = LinearLayoutManager(this)
        b.recycler.adapter = adapter

        b.switchBlock.setOnCheckedChangeListener { _, checked ->
            if (suppressSwitch) return@setOnCheckedChangeListener
            if (checked) requestEnable() else disableNow()
        }

        b.btnApps.setOnClickListener { startActivity(Intent(this, AppPickerActivity::class.java)) }
        b.btnRules.setOnClickListener { startActivity(Intent(this, SettingsActivity::class.java)) }
        b.btnA11y.setOnClickListener { openAccessibilitySettings() }
        b.btnExport.setOnClickListener { exportReport() }
        b.btnClear.setOnClickListener {
            BlockLog.clear()
            prefs.resetTodayCount()
            refresh()
        }
    }

    override fun onResume() {
        super.onResume()
        SplashGuardVpnService.setStateListener(stateListener)
        BlockLog.addListener(logListener)
        refresh()
        // 稍等一会儿再判断：系统可能在 Activity 启动后才把服务绑上
        b.root.postDelayed({ maybeRepairA11y() }, 900)
    }

    override fun onPause() {
        SplashGuardVpnService.setStateListener(null)
        BlockLog.removeListener(logListener)
        super.onPause()
    }

    // ---------------- 开关 ----------------

    private fun requestEnable() {
        val intent = VpnService.prepare(this)
        if (intent != null) {
            pendingEnable = true
            vpnPermission.launch(intent)
        } else {
            startTunnel()
        }
    }

    private fun startTunnel() {
        prefs.enabled = true
        SplashGuardVpnService.start(this)
        askNotificationPermission()
        b.root.postDelayed({ refresh() }, 700)
    }

    private fun disableNow() {
        SplashGuardVpnService.stop(this)
        b.root.postDelayed({ refresh() }, 500)
    }

    private fun askNotificationPermission() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        val granted = checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) ==
            android.content.pm.PackageManager.PERMISSION_GRANTED
        if (!granted) notifPermission.launch(android.Manifest.permission.POST_NOTIFICATIONS)
    }

    // ---------------- 刷新 ----------------

    private fun refresh() {
        val running = SplashGuardVpnService.isRunning

        suppressSwitch = true
        b.switchBlock.isChecked = running
        suppressSwitch = false

        b.tvStatus.text = getString(if (running) R.string.status_on else R.string.status_off)
        b.tvStatusSub.text = getString(if (running) R.string.status_hint_on else R.string.status_hint_off)
        b.tvToday.text = prefs.todayCount().toString()
        b.tvTodaySkip.text = prefs.todaySkipCount().toString()
        b.tvRules.text = RuleStore.ruleCount.toString()

        b.btnA11y.text = when {
            isA11yEnabled && A11yState.connected -> getString(R.string.nav_a11y) + " · 开"
            isA11yEnabled -> getString(R.string.nav_a11y) + " · 未连接"
            else -> getString(R.string.nav_a11y)
        }

        refreshLog()
    }

    private fun refreshLog() {
        val list = BlockLog.snapshot()
        adapter.submit(list)
        val empty = list.isEmpty()
        b.tvEmpty.visibility = if (empty) View.VISIBLE else View.GONE
        b.recycler.visibility = if (empty) View.INVISIBLE else View.VISIBLE
    }

    private fun labelOf(pkg: String?): String {
        if (pkg == null) return getString(R.string.apps_unknown)
        labelCache[pkg]?.let { return it }
        val label = runCatching {
            val ai = packageManager.getApplicationInfo(pkg, 0)
            packageManager.getApplicationLabel(ai).toString()
        }.getOrDefault(pkg)
        labelCache[pkg] = label
        return label
    }

    // ---------------- 无障碍 ----------------

    private val isA11yEnabled: Boolean
        get() {
            val am = getSystemService(AccessibilityManager::class.java) ?: return false
            return am.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK)
                .any { it.resolveInfo.serviceInfo.packageName == packageName }
        }

    private fun openAccessibilitySettings() {
        if (!isA11yEnabled) {
            showA11yGuide()
            return
        }
        if (!A11yState.connected) {
            showA11yNotConnectedDialog()
            return
        }
        if (A11yController.isShortcutEnabled(this)) {
            showHideButtonDialog()
        } else {
            toast(getString(R.string.a11y_on))
            openA11ySettingsPage()
        }
    }

    private fun showA11yGuide() {
        // 各厂商把入口藏得深浅不一（小米尤其），先给一份带机型提示的引导
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.a11y_guide_title)
            .setMessage(R.string.a11y_guide_body)
            .setPositiveButton(R.string.a11y_go_settings) { _, _ -> openA11ySettingsPage() }
            .setNeutralButton(R.string.a11y_copy_adb) { _, _ ->
                copyToClipboard(ADB_A11Y_COMMAND, getString(R.string.toast_adb_copied))
            }
            .setNegativeButton(R.string.action_close, null)
            .show()
    }

    /**
     * 系统显示已授权、但服务没有真正连上。更新 App 后 HyperOS 重置无障碍服务是最常见的原因。
     */
    private fun showA11yNotConnectedDialog() {
        val canWrite = A11yController.canWriteSecureSettings(this)
        val builder = MaterialAlertDialogBuilder(this)
            .setTitle(R.string.a11y_not_connected_title)
            .setMessage(R.string.a11y_not_connected_body)
            .setNegativeButton(R.string.action_close, null)

        if (canWrite) {
            builder.setPositiveButton(R.string.a11y_repair) { _, _ -> startA11yRepair() }
            builder.setNeutralButton(R.string.a11y_go_settings) { _, _ -> openA11ySettingsPage() }
        } else {
            builder.setPositiveButton(R.string.a11y_go_settings) { _, _ -> openA11ySettingsPage() }
            builder.setNeutralButton(R.string.a11y_copy_adb) { _, _ ->
                copyToClipboard(ADB_A11Y_COMMAND, getString(R.string.toast_adb_copied))
            }
        }
        builder.show()
    }

    private fun startA11yRepair() {
        if (!A11yController.repairBinding(this)) {
            toast(getString(R.string.toast_a11y_repair_failed))
            return
        }
        toast(getString(R.string.toast_a11y_repairing))
        b.root.postDelayed({ refresh() }, 1500)
    }

    /**
     * 打开 App 时如果「系统已授权但服务未连接」，自动做一次移除/重新加入，
     * 修复更新 App 后被系统解绑的状态。15 秒冷却，避免反复写设置。
     */
    private fun maybeRepairA11y() {
        if (!isA11yEnabled || A11yState.connected) return

        if (!A11yController.canWriteSecureSettings(this)) {
            // 没权限时至少把「服务没连上」明确告诉用户，而不是显示一个假的「已开启」
            if (!promptedA11yNotConnected && !isFinishing) {
                promptedA11yNotConnected = true
                showA11yNotConnectedDialog()
            }
            return
        }

        val now = SystemClock.elapsedRealtime()
        if (repairingA11y || now - lastRepairAt < REPAIR_COOLDOWN_MS) return

        repairingA11y = true
        lastRepairAt = now
        val started = A11yController.repairBinding(this) {
            b.root.postDelayed({
                repairingA11y = false
                refresh()
            }, 1200)
        }
        if (!started) {
            repairingA11y = false
            return
        }
        b.root.postDelayed({ repairingA11y = false }, 2500)
    }

    /**
     * 屏幕上的无障碍悬浮按钮是系统的「无障碍快捷方式」，不是本 App 画的。
     * 关掉快捷方式不会关掉已开启的无障碍服务。
     */
    private fun showHideButtonDialog() {
        val canWrite = A11yController.canWriteSecureSettings(this)
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.a11y_button_title)
            .setMessage(getString(R.string.a11y_button_body))
            .setPositiveButton(
                if (canWrite) R.string.a11y_button_hide else R.string.a11y_button_copy_grant
            ) { _, _ ->
                if (canWrite) {
                    hideAccessibilityButton()
                } else {
                    copyToClipboard(grantSecureSettingsCommand(), getString(R.string.toast_adb_copied))
                }
            }
            .setNeutralButton(R.string.a11y_go_settings) { _, _ -> openA11ySettingsPage() }
            .setNegativeButton(R.string.action_close, null)
            .show()
    }

    private fun openA11ySettingsPage() {
        runCatching { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
            .onFailure { toast(getString(R.string.toast_open_settings_failed)) }
    }

    private fun hideAccessibilityButton() {
        if (A11yController.hideShortcutButton(this)) {
            toast(getString(R.string.toast_a11y_button_hidden))
            refresh()
        } else {
            toast(getString(R.string.toast_a11y_button_hide_failed))
        }
    }

    private fun grantSecureSettingsCommand(): String =
        "adb shell pm grant $packageName ${Manifest.permission.WRITE_SECURE_SETTINGS}"

    // ---------------- 诊断报告（真机验收用） ----------------

    private fun exportReport() {
        val text = runCatching { DiagnosticReport.build(this) }.getOrElse { e ->
            toast(getString(R.string.toast_export_failed, e.message ?: e.javaClass.simpleName))
            return
        }
        val density = resources.displayMetrics.density
        val pad = (14 * density).toInt()

        val body = TextView(this).apply {
            this.text = text
            textSize = 9.5f
            typeface = Typeface.MONOSPACE
            setTextIsSelectable(true)
            setPadding(pad, pad, pad, pad)
        }
        // 限制高度，否则正文会把「复制/分享/关闭」按钮挤出屏幕
        val maxBodyHeight = (resources.displayMetrics.heightPixels * 0.42f).toInt()
        val scroller = object : ScrollView(this) {
            override fun onMeasure(widthSpec: Int, heightSpec: Int) {
                super.onMeasure(
                    widthSpec,
                    MeasureSpec.makeMeasureSpec(maxBodyHeight, MeasureSpec.AT_MOST)
                )
            }
        }.apply { addView(body) }

        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.export_title)
            .setMessage(R.string.export_hint)
            .setView(scroller)
            .setPositiveButton(R.string.action_share) { _, _ -> shareReport(text) }
            .setNeutralButton(R.string.action_copy) { _, _ -> copyReport(text) }
            .setNegativeButton(R.string.action_close, null)
            .show()
    }

    private fun copyReport(text: String) {
        copyToClipboard(text, getString(R.string.toast_copied))
    }

    private fun copyToClipboard(text: String, hint: String) {
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return
        cm.setPrimaryClip(ClipData.newPlainText("SplashGuard", text))
        toast(hint)
    }

    private fun shareReport(text: String) {
        runCatching {
            val f = DiagnosticReport.writeToFile(this, text)
            val uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", f)
            val send = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_SUBJECT, "SplashGuard 诊断报告")
                putExtra(Intent.EXTRA_STREAM, uri)
                putExtra(Intent.EXTRA_TEXT, text)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(Intent.createChooser(send, getString(R.string.share_chooser)))
        }.onFailure { e ->
            toast(getString(R.string.toast_export_failed, e.message ?: e.javaClass.simpleName))
        }
    }

    private fun toast(msg: String) {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
    }

    private companion object {
        /** 自动修复的最小间隔，避免反复写系统设置 */
        const val REPAIR_COOLDOWN_MS = 15_000L

        /**
         * 系统设置里找不到入口时的 ADB 兜底。
         * 先把本服务从列表里移除、再重新加入，强制系统重新绑定；
         * 同时保留 TalkBack 等其他无障碍服务。
         */
        const val ADB_A11Y_COMMAND =
            "adb shell 'comp=com.splashguard.app/com.splashguard.app.a11y.SkipAccessibilityService; " +
                "cur=\$(settings get secure enabled_accessibility_services); " +
                "[ \"\$cur\" = \"null\" ] && cur=\"\"; " +
                "without=\$(echo \"\$cur\" | sed \"s#\$comp:##g; s#:\$comp##g; s#\$comp##g; s/^://; s/:\$//\"); " +
                "settings put secure enabled_accessibility_services \"\$without\"; " +
                "sleep 1; " +
                "settings put secure enabled_accessibility_services \"\${without:+\$without:}\$comp\"; " +
                "settings put secure accessibility_enabled 1'"
    }
}