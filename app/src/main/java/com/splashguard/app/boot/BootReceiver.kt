package com.splashguard.app.boot

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.VpnService
import android.util.Log
import com.splashguard.app.core.Prefs
import com.splashguard.app.net.SplashGuardVpnService

/**
 * 开机 / 升级后自动恢复拦截。
 *
 * 如果用户此前没有授权 VPN，VpnService.prepare() 会返回非 null，
 * 此时静默放弃：不弹通知、不崩溃，等用户下次手动开启。
 */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        if (action != Intent.ACTION_BOOT_COMPLETED && action != Intent.ACTION_MY_PACKAGE_REPLACED) return

        val prefs = Prefs(context)
        if (!prefs.bootStart) return
        if (!prefs.enabled) return

        if (VpnService.prepare(context) != null) {
            Log.i(TAG, "no vpn permission at boot, skip")
            return
        }
        runCatching { SplashGuardVpnService.start(context) }
            .onFailure { Log.w(TAG, "boot start failed: ${it.message}") }
    }

    companion object {
        private const val TAG = "SplashGuardBoot"
    }
}
