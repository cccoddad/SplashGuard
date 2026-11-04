package com.splashguard.app.ui

import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Intent
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.view.accessibility.AccessibilityManager
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import com.splashguard.app.R
import com.splashguard.app.core.Prefs
import com.splashguard.app.databinding.ActivityMainBinding
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
        val hint = if (isA11yEnabled) R.string.a11y_on else R.string.a11y_off
        toast(getString(hint))
        runCatching {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }.onFailure {
            toast("无法打开无障碍设置，请手动进入 系统设置 → 无障碍")
        }
    }

    private fun toast(msg: String) {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
    }
}
