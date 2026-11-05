package com.splashguard.app.a11y

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SkipLabelMatcherTest {

    @Test
    fun `精确文案命中`() {
        listOf("跳过", "跳过广告", "关闭广告", "跳过广告5", "点击跳过").forEach {
            assertTrue("应命中: $it", SkipLabelMatcher.isSkipLabel(it))
        }
    }

    @Test
    fun `倒计时变体命中`() {
        listOf(
            "跳过 3",
            "跳过 5s",
            "跳过广告 10s",
            "跳过广告5秒",
            "Skip",
            "Skip Ad",
            "Skip Ad 5s",
            "Skip in 5s",
            "skip 5 seconds",
            "skipadvert",
        ).forEach {
            assertTrue("应命中: $it", SkipLabelMatcher.isSkipLabel(it))
        }
    }

    @Test
    fun `新增安全变体命中`() {
        listOf(
            "跳过 | 3s",
            "跳过·5s",
            "5秒跳过",
            "3s 跳过",
            "点击跳过 3",
            "关闭广告 5s",
            "关闭广告3",
            "跳过5秒广告",
        ).forEach {
            assertTrue("应命中: $it", SkipLabelMatcher.isSkipLabel(it))
        }
    }

    @Test
    fun `单独关闭不点`() {
        assertFalse(SkipLabelMatcher.isSkipLabel("关闭"))
        assertFalse(SkipLabelMatcher.isSkipLabel("Close"))
    }

    @Test
    fun `互动广告文案不点`() {
        listOf("摇一摇跳转详情", "点击查看详情", "滑动查看", "扭一扭", "抖一抖", "摇一摇跳过").forEach {
            assertFalse("不应命中: $it", SkipLabelMatcher.isSkipLabel(it))
        }
    }

    @Test
    fun `带附加文案的跳过不点`() {
        assertFalse(SkipLabelMatcher.isSkipLabel("跳过广告5s领奖"))
        assertFalse(SkipLabelMatcher.isSkipLabel("跳过去"))
        assertFalse(SkipLabelMatcher.isSkipLabel("跳过吧"))
        assertFalse(SkipLabelMatcher.isSkipLabel("跳过有奖"))
    }

    @Test
    fun `空串和超长串不点`() {
        assertFalse(SkipLabelMatcher.isSkipLabel(""))
        assertFalse(SkipLabelMatcher.isSkipLabel("跳过" + "0".repeat(20)))
    }
}
