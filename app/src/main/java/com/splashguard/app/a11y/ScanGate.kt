package com.splashguard.app.a11y

/**
 * 扫描门控：决定「当前这个窗口值不值得扫」，纯 Kotlin，可直接单测。
 *
 * 背景：v0.1.7 的扫描只排除了本 App 包名，导致设置页、桌面、系统 UI、搜索框
 * 也会被 150 节点 BFS 扫一遍。诊断报告显示 91 次扫描里约三成发生在明显不是开屏的界面，
 * 是发热的主要来源之一。
 */
object ScanGate {

    enum class Verdict {
        /** 可以扫描 */
        SCAN,
        /** 本 App 自己的窗口 */
        SELF,
        /** 桌面 / 启动器 */
        HOME,
        /** 明确的系统界面、权限弹窗、输入法、系统设置等 */
        IGNORED,
        /** 根节点不是当前跟踪的 App（转场或系统覆盖层），本次跳过但保留轮询 */
        WRONG_ROOT,
        /** 拿不到根节点包名 */
        NO_PKG,
    }

    /** 这些窗口不是目标 App：桌面、系统 UI、权限弹窗、输入法、系统设置 */
    val IGNORED_PKGS = setOf(
        "android",
        "com.android.systemui",
        "com.android.permissioncontroller",
        "com.google.android.permissioncontroller",
        "com.android.packageinstaller",
        "com.google.android.packageinstaller",
        "com.android.settings",
        "com.android.quicksearchbox",
        "com.google.android.googlequicksearchbox",
        "com.miui.personalassistant",
        "com.miui.securitycenter",
        "com.lbe.security.miui",
        "com.miui.home",
        "com.android.launcher3",
        "com.google.android.apps.nexuslauncher",
        "com.sec.android.app.launcher",
        "com.miui.securityinputmethod",
        "com.sohu.inputmethod.sogou",
        "com.baidu.input",
        "com.iflytek.inputmethod",
    )

    fun shouldScan(
        rootPkg: String?,
        trackedPkg: String?,
        selfPkg: String,
        homePkg: String?,
    ): Verdict {
        if (rootPkg.isNullOrBlank()) return Verdict.NO_PKG
        if (rootPkg == selfPkg) return Verdict.SELF
        if (rootPkg == homePkg) return Verdict.HOME
        if (rootPkg in IGNORED_PKGS) return Verdict.IGNORED
        if (trackedPkg != null && rootPkg != trackedPkg) return Verdict.WRONG_ROOT
        return Verdict.SCAN
    }
}
