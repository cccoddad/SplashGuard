package com.splashguard.app.a11y

import android.content.Context
import android.content.pm.PackageManager
import android.os.Handler
import android.os.Looper
import android.provider.Settings

/**
 * 无障碍服务的系统设置读写。
 *
 * 为什么需要它：App 更新 / 被系统强停后，Android 经常出现「enabled_accessibility_services
 * 里还有本服务，但服务没有真正绑定」的状态 —— 界面显示已授权，实际一次都不会点。
 * 把服务从列表里移除再重新加入，可以强制系统重新绑定。
 *
 * 这些写入需要 `WRITE_SECURE_SETTINGS`，普通安装不会授予；用户可通过一次 ADB
 * `pm grant` 打开。未授权时 App 只做检测和引导，不会假装修复成功。
 */
object A11yController {

    private const val KEY_ENABLED_SERVICES = "enabled_accessibility_services"
    private const val KEY_ACCESSIBILITY_ENABLED = "accessibility_enabled"
    private const val KEY_SHORTCUT_ENABLED = "accessibility_shortcut_enabled"
    private const val KEY_BUTTON_TARGETS = "accessibility_button_targets"
    private const val KEY_SHORTCUT_TARGET = "accessibility_shortcut_target_service"

    /** 移除与重新加入之间的间隔，给系统时间处理第一次变更 */
    private const val REPAIR_DELAY_MS = 400L

    fun component(context: Context): String =
        "${context.packageName}/${SkipAccessibilityService::class.java.name}"

    fun canWriteSecureSettings(context: Context): Boolean = runCatching {
        context.checkSelfPermission(android.Manifest.permission.WRITE_SECURE_SETTINGS) ==
            PackageManager.PERMISSION_GRANTED
    }.getOrDefault(false)

    fun isShortcutEnabled(context: Context): Boolean = runCatching {
        val shortcut = Settings.Secure.getInt(
            context.contentResolver, KEY_SHORTCUT_ENABLED, 0
        ) == 1
        val targets = Settings.Secure.getString(
            context.contentResolver, KEY_BUTTON_TARGETS
        ).orEmpty().isNotBlank()
        shortcut || targets
    }.getOrDefault(false)

    /**
     * 强制系统重新绑定本服务。必须在 [canWriteSecureSettings] 为 true 时调用。
     * 会保留其他无障碍服务，只移除并重新加入本服务。
     *
     * @param onlyIfListed 为 true 时，只有系统列表里原本就有本服务才修复；
     *        用于 App 更新后自动修复，避免把用户已经主动关闭的服务重新打开。
     * @return false 表示没有权限或无需修复，未做任何修改
     */
    fun repairBinding(
        context: Context,
        onlyIfListed: Boolean = false,
        onDone: (() -> Unit)? = null,
    ): Boolean {
        if (!canWriteSecureSettings(context)) return false

        val cr = context.contentResolver
        val comp = component(context)
        val current = Settings.Secure.getString(cr, KEY_ENABLED_SERVICES).orEmpty()
        val listed = current.split(':')
            .map { it.trim() }
            .filter { it.isNotEmpty() && it != "null" }
        if (onlyIfListed && comp !in listed) return false

        val others = listed.filter { it != comp }

        // 先移除，系统会解绑；再重新加入，系统会重新绑定
        Settings.Secure.putString(cr, KEY_ENABLED_SERVICES, others.joinToString(":"))
        Handler(Looper.getMainLooper()).postDelayed({
            Settings.Secure.putString(cr, KEY_ENABLED_SERVICES, (others + comp).joinToString(":"))
            Settings.Secure.putInt(cr, KEY_ACCESSIBILITY_ENABLED, 1)
            onDone?.invoke()
        }, REPAIR_DELAY_MS)
        return true
    }

    /**
     * 隐藏系统「无障碍快捷方式 / 无障碍按钮」。它和已启用的无障碍服务是两套设置，
     * 关掉按钮不会关掉自动跳过。
     */
    fun hideShortcutButton(context: Context): Boolean {
        if (!canWriteSecureSettings(context)) return false
        val cr = context.contentResolver
        return runCatching {
            Settings.Secure.putInt(cr, KEY_SHORTCUT_ENABLED, 0)
            Settings.Secure.putString(cr, KEY_BUTTON_TARGETS, null)
            Settings.Secure.putString(cr, KEY_SHORTCUT_TARGET, null)
            Settings.Secure.getInt(cr, KEY_SHORTCUT_ENABLED, 0) == 0
        }.getOrDefault(false)
    }
}
