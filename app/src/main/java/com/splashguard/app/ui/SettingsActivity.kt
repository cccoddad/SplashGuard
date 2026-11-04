package com.splashguard.app.ui

import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.splashguard.app.R
import com.splashguard.app.core.BlockMode
import com.splashguard.app.core.Prefs
import com.splashguard.app.databinding.ActivitySettingsBinding
import com.splashguard.app.net.SplashGuardVpnService
import com.splashguard.app.rules.RuleStore

class SettingsActivity : AppCompatActivity() {

    private lateinit var b: ActivitySettingsBinding
    private lateinit var prefs: Prefs

    private val ipv4 = Regex("^((25[0-5]|2[0-4]\\d|1\\d\\d|[1-9]?\\d)\\.){3}(25[0-5]|2[0-4]\\d|1\\d\\d|[1-9]?\\d)$")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(b.root)
        prefs = Prefs(this)

        b.toolbar.setNavigationOnClickListener { finish() }

        // 拦截方式
        b.rbZero.isChecked = prefs.blockMode == BlockMode.ZERO
        b.rbNx.isChecked = prefs.blockMode == BlockMode.NXDOMAIN
        b.rgMode.setOnCheckedChangeListener { _, id ->
            prefs.blockMode = if (id == R.id.rbNx) BlockMode.NXDOMAIN else BlockMode.ZERO
        }

        // 上游 DNS + 规则
        b.etUpstream.setText(prefs.upstreamDns)
        b.etRules.setText(prefs.userRules)

        b.btnSave.setOnClickListener { save() }

        // 开机自启
        b.switchBoot.isChecked = prefs.bootStart
        b.switchBoot.setOnCheckedChangeListener { _, checked -> prefs.bootStart = checked }

        // 降级模式
        b.btnPrivateDns.setOnClickListener { openPrivateDnsSettings() }

        // 隐私说明
        b.btnPrivacy.setOnClickListener {
            AlertDialog.Builder(this)
                .setTitle(R.string.privacy_title)
                .setMessage(R.string.privacy_body)
                .setPositiveButton(R.string.action_ok, null)
                .show()
        }
    }

    private fun save() {
        val upstream = b.etUpstream.text?.toString()?.trim().orEmpty()
        if (!ipv4.matches(upstream)) {
            b.tilUpstream.error = getString(R.string.toast_bad_upstream)
            return
        }
        b.tilUpstream.error = null

        prefs.upstreamDns = upstream
        prefs.userRules = b.etRules.text?.toString().orEmpty()
        RuleStore.reload(this)

        toast(getString(R.string.toast_saved, RuleStore.ruleCount))
        SplashGuardVpnService.restart(this)
    }

    private fun openPrivateDnsSettings() {
        // ACTION_PRIVATE_DNS_SETTINGS 是 @hide 常量，只能用字面量 + 降级兜底
        val actions = listOf(
            "android.settings.PRIVATE_DNS_SETTINGS",
            Settings.ACTION_WIRELESS_SETTINGS
        )
        for (action in actions) {
            val ok = runCatching { startActivity(Intent(action)) }.isSuccess
            if (ok) return
        }
        toast("请手动进入 设置 → 网络 → 私有 DNS")
    }

    private fun toast(msg: String) {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
    }
}
