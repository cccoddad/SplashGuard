package com.splashguard.app.core

import android.content.Context
import android.content.SharedPreferences
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** 拦截响应模式 */
enum class BlockMode {
    /** 返回 0.0.0.0（A 记录），兼容性最好，默认 */
    ZERO,

    /** 返回 NXDOMAIN，某些 App 在 0.0.0.0 下会卡住时用这个 */
    NXDOMAIN
}

/** 分应用模式 */
enum class PerAppMode {
    ALL,
    ONLY_SELECTED
}

/**
 * 唯一的配置读写入口。所有数据都存在应用私有 SharedPreferences 里，不上传、不导出。
 */
class Prefs(context: Context) {

    private val sp: SharedPreferences =
        context.applicationContext.getSharedPreferences("splashguard", Context.MODE_PRIVATE)

    var enabled: Boolean
        get() = sp.getBoolean(KEY_ENABLED, false)
        set(v) = sp.edit().putBoolean(KEY_ENABLED, v).apply()

    var blockMode: BlockMode
        get() = when (sp.getString(KEY_MODE, null)) {
            "nxdomain" -> BlockMode.NXDOMAIN
            else -> BlockMode.ZERO
        }
        set(v) = sp.edit().putString(KEY_MODE, if (v == BlockMode.NXDOMAIN) "nxdomain" else "zero").apply()

    /** 上游 DNS，必须是 IPv4 字面量 */
    var upstreamDns: String
        get() = sp.getString(KEY_UPSTREAM, DEFAULT_UPSTREAM)?.takeIf { it.isNotBlank() } ?: DEFAULT_UPSTREAM
        set(v) = sp.edit().putString(KEY_UPSTREAM, v.trim()).apply()

    var bootStart: Boolean
        get() = sp.getBoolean(KEY_BOOT, true)
        set(v) = sp.edit().putBoolean(KEY_BOOT, v).apply()

    var perAppMode: PerAppMode
        get() = when (sp.getString(KEY_PERAPP_MODE, null)) {
            "selected" -> PerAppMode.ONLY_SELECTED
            else -> PerAppMode.ALL
        }
        set(v) = sp.edit()
            .putString(KEY_PERAPP_MODE, if (v == PerAppMode.ONLY_SELECTED) "selected" else "all")
            .apply()

    var selectedApps: Set<String>
        get() = sp.getStringSet(KEY_SELECTED, emptySet()) ?: emptySet()
        set(v) = sp.edit().putStringSet(KEY_SELECTED, HashSet(v)).apply()

    var userRules: String
        get() = sp.getString(KEY_USER_RULES, "") ?: ""
        set(v) = sp.edit().putString(KEY_USER_RULES, v).apply()

    /** 用户是否在 App 内打开过无障碍开关（真实生效状态以系统为准） */
    var a11yEnabled: Boolean
        get() = sp.getBoolean(KEY_A11Y, false)
        set(v) = sp.edit().putBoolean(KEY_A11Y, v).apply()

    var bootGuided: Boolean
        get() = sp.getBoolean(KEY_BOOT_GUIDED, false)
        set(v) = sp.edit().putBoolean(KEY_BOOT_GUIDED, v).apply()

    // ---- 今日拦截 / 自动跳过计数（跨天自动归零） ----

    fun bumpBlockCount() {
        rollDateIfNeeded()
        sp.edit().putInt(KEY_COUNT, sp.getInt(KEY_COUNT, 0) + 1).apply()
    }

    fun todayCount(): Int {
        rollDateIfNeeded()
        return sp.getInt(KEY_COUNT, 0)
    }

    /** 无障碍成功点掉一次「跳过」时调用 */
    fun bumpSkipCount(pkg: String?) {
        rollDateIfNeeded()
        sp.edit()
            .putInt(KEY_SKIP_COUNT, sp.getInt(KEY_SKIP_COUNT, 0) + 1)
            .putLong(KEY_LAST_SKIP_AT, System.currentTimeMillis())
            .putString(KEY_LAST_SKIP_PKG, pkg)
            .apply()
    }

    fun todaySkipCount(): Int {
        rollDateIfNeeded()
        return sp.getInt(KEY_SKIP_COUNT, 0)
    }

    fun lastSkipAt(): Long = sp.getLong(KEY_LAST_SKIP_AT, 0L)

    fun lastSkipPkg(): String? = sp.getString(KEY_LAST_SKIP_PKG, null)

    fun resetTodayCount() {
        sp.edit()
            .putInt(KEY_COUNT, 0)
            .putInt(KEY_SKIP_COUNT, 0)
            .putString(KEY_DATE, today())
            .remove(KEY_LAST_SKIP_AT)
            .remove(KEY_LAST_SKIP_PKG)
            .apply()
    }

    private fun rollDateIfNeeded() {
        val t = today()
        if (sp.getString(KEY_DATE, null) != t) {
            sp.edit()
                .putString(KEY_DATE, t)
                .putInt(KEY_COUNT, 0)
                .putInt(KEY_SKIP_COUNT, 0)
                .apply()
        }
    }

    private fun today(): String =
        SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())

    companion object {
        const val DEFAULT_UPSTREAM = "223.5.5.5"
        private const val KEY_ENABLED = "enabled"
        private const val KEY_MODE = "block_mode"
        private const val KEY_UPSTREAM = "upstream"
        private const val KEY_BOOT = "boot_start"
        private const val KEY_PERAPP_MODE = "perapp_mode"
        private const val KEY_SELECTED = "selected_apps"
        private const val KEY_USER_RULES = "user_rules"
        private const val KEY_A11Y = "a11y_enabled"
        private const val KEY_BOOT_GUIDED = "boot_guided"
        private const val KEY_COUNT = "block_count"
        private const val KEY_SKIP_COUNT = "skip_count"
        private const val KEY_LAST_SKIP_AT = "last_skip_at"
        private const val KEY_LAST_SKIP_PKG = "last_skip_pkg"
        private const val KEY_DATE = "block_date"
    }
}
