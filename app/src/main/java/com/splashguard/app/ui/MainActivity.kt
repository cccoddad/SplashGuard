package com.splashguard.app.ui

import android.accessibilityservice.AccessibilityServiceInfo
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Typeface
import android.net.VpnService
import android.os.Build
import android.os.Bundle
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
        b.tvRules.text = RuleStore.ruleCount.toString()

        b.btnA11y.text = if (isA11yEnabled) {
            getString(R.string.nav_a11y) + " · 开"
        } else {
            getString(R.string.nav_a11y)
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
        if (isA11yEnabled) {
            toast(getString(R.string.a11y_on))
            runCatching { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
            return
        }
        // 各厂商把入口藏得深浅不一（小米尤其），先给一份带机型提示的引导
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.a11y_guide_title)
            .setMessage(R.string.a11y_guide_body)
            .setPositiveButton(R.string.a11y_go_settings) { _, _ ->
                runCatching { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
                    .onFailure { toast(getString(R.string.toast_open_settings_failed)) }
            }
            .setNeutralButton(R.string.a11y_copy_adb) { _, _ ->
                copyToClipboard(ADB_A11Y_COMMAND, getString(R.string.toast_adb_copied))
            }
            .setNegativeButton(R.string.action_close, null)
            .show()
    }

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
        /** 系统设置里实在找不到入口时，让用户复制去电脑上用 adb 授权 */
        const val ADB_A11Y_COMMAND =
            "adb shell settings put secure enabled_accessibility_services " +
                "com.splashguard.app/com.splashguard.app.a11y.SkipAccessibilityService\n" +
                "adb shell settings put secure accessibility_enabled 1"
    }
}