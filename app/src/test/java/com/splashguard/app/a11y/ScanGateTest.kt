package com.splashguard.app.a11y

import org.junit.Assert.assertEquals
import org.junit.Test

class ScanGateTest {

    private fun verdict(root: String?, tracked: String? = "com.target") =
        ScanGate.shouldScan(root, tracked, "com.splashguard.app", "com.miui.home")

    @Test
    fun `跟踪的 App 可以扫描`() {
        assertEquals(ScanGate.Verdict.SCAN, verdict("com.target"))
    }

    @Test
    fun `本 App 不扫描`() {
        assertEquals(ScanGate.Verdict.SELF, verdict("com.splashguard.app"))
    }

    @Test
    fun `桌面不扫描`() {
        assertEquals(ScanGate.Verdict.HOME, verdict("com.miui.home"))
    }

    @Test
    fun `系统设置和系统 UI 不扫描`() {
        assertEquals(ScanGate.Verdict.IGNORED, verdict("com.android.settings"))
        assertEquals(ScanGate.Verdict.IGNORED, verdict("com.android.systemui"))
        assertEquals(ScanGate.Verdict.IGNORED, verdict("com.android.quicksearchbox"))
    }

    @Test
    fun `根节点不是跟踪包时跳过本次扫描`() {
        assertEquals(ScanGate.Verdict.WRONG_ROOT, verdict("com.other"))
    }

    @Test
    fun `拿不到包名不扫描`() {
        assertEquals(ScanGate.Verdict.NO_PKG, verdict(null))
        assertEquals(ScanGate.Verdict.NO_PKG, verdict(""))
    }

    @Test
    fun `没有跟踪包时只排除系统界面`() {
        assertEquals(ScanGate.Verdict.SCAN, verdict("com.any", tracked = null))
        assertEquals(ScanGate.Verdict.IGNORED, verdict("com.android.settings", tracked = null))
    }
}
