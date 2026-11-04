package com.splashguard.app.boot

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.VpnService
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import com.splashguard.app.R
import com.splashguard.app.core.Const
import com.splashguard.app.core.Prefs
import com.splashguard.app.net.SplashGuardVpnService
import com.splashguard.app.ui.MainActivity

/**
 * 开机 / 升级后自动恢复拦截。
 *
 * 如果用户此前没有授权 VPN，VpnService.prepare() 会返回非 null：
 * 此时**不发通知就不了了之是不行的** —— 用户会以为拦截还开着，其实早就停了。
 * 所以这里发一条"点一下恢复"的通知，把静默失败变成可恢复。
 */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        if (action != Intent.ACTION_BOOT_COMPLETED && action != Intent.ACTION_MY_PACKAGE_REPLACED) return

        val prefs = Prefs(context)
        if (!prefs.bootStart) return
        if (!prefs.enabled) return

        if (VpnService.prepare(context) != null) {
            // 授权在重启后可能失效（不同 ROM 行为不一致），引导用户一键恢复
            Log.i(TAG, "no vpn permission at boot, ask user to re-authorize")
            notifyReauth(context)
            return
        }
        runCatching { SplashGuardVpnService.start(context) }
            .onFailure { Log.w(TAG, "boot start failed: ${it.message}") }
    }

    private fun notifyReauth(context: Context) {
        val nm = context.getSystemService(NotificationManager::class.java) ?: return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            nm.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    context.getString(R.string.notif_alert_channel),
                    NotificationManager.IMPORTANCE_DEFAULT
                )
            )
        }
        val open = PendingIntent.getActivity(
            context, 10,
            Intent(context, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val n = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_shield)
            .setContentTitle(context.getString(R.string.boot_need_auth_title))
            .setContentText(context.getString(R.string.boot_need_auth_body))
            .setAutoCancel(true)
            .setContentIntent(open)
            .build()
        runCatching { nm.notify(NOTIF_ID, n) }
            .onFailure { Log.w(TAG, "cannot post re-auth notification: ${it.message}") }
    }

    companion object {
        private const val TAG = "SplashGuardBoot"
        private const val CHANNEL_ID = "splashguard_alert"
        private const val NOTIF_ID = Const.NOTIF_ID + 1
    }
}
