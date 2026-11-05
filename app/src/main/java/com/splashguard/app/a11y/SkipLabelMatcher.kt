package com.splashguard.app.a11y

/**
 * 「跳过」文案匹配。纯 Kotlin，无 Android 依赖，可直接单测。
 *
 * 设计原则：宁缺毋滥。只接受用户本来就会手动点的「跳过 / 跳过广告 / Skip」类文案，
 * 明确拒绝「摇一摇 / 点击查看」这类互动广告词，也不接受单独一个「关闭」——
 * 后者太容易点到对话框、引导页或广告本体的关闭按钮。
 *
 * v0.1.8 补充（仅放宽「跳过」本身的安全变体，不放开「关闭」）：
 *  - 「点击跳过」「跳过 | 3s」「跳过·5s」「5秒跳过」「关闭广告 5s」「跳过5秒广告」
 *  - 仍然拒绝任何包含「摇一摇 / 扭一扭 / 点击查看」的文案
 */
object SkipLabelMatcher {

    /** 归一化后的最大长度；覆盖「跳过广告 10s」「Skip in 5s」这类倒计时变体 */
    private const val MAX_LENGTH = 16

    private val WHITESPACE = "\\s".toRegex()

    /** 只清理分隔符，不删除普通汉字，避免把「跳过领奖」误归一成倒计时 */
    private val SEPARATOR = "[·•|｜丨>›》\\-—_/\\\\]".toRegex()

    /** 不带倒计时的固定文案 */
    private val EXACT = setOf(
        "跳过", "跳过广告", "关闭广告", "跳过广告5",
        "点击跳过",
        "skip", "skipad", "skipads", "skipadvert"
    )

    /** 互动广告文案：出现这些词一律不点 */
    private val INTERACTIVE = listOf(
        "摇一摇", "摇动", "扭一扭", "抖一抖", "点击查看", "滑动查看", "晃动"
    )

    /** 去掉前缀后，只允许「数字 + 秒/s/second」 */
    private val COUNTDOWN = Regex("^[0-9]{0,3}(s|秒|sec|second|seconds)?$")

    /** 是否包含「摇一摇 / 扭一扭 / 点击查看」这类互动广告文案（只用于诊断，不点击） */
    fun isInteractive(raw: String): Boolean {
        val s = raw.replace(WHITESPACE, "").lowercase()
        return INTERACTIVE.any { s.contains(it) }
    }

    fun isSkipLabel(raw: String): Boolean {
        val s = raw.replace(WHITESPACE, "")
            .replace(SEPARATOR, "")
            .trim()
            .lowercase()
        if (s.isEmpty() || s.length > MAX_LENGTH) return false
        if (INTERACTIVE.any { s.contains(it) }) return false
        if (s in EXACT) return true

        // 「跳过 3s」「跳过广告 5秒」「关闭广告 3」「点击跳过 3」
        val rest = when {
            s.startsWith("跳过") -> s.removePrefix("跳过").removePrefix("广告")
            s.startsWith("点击跳过") -> s.removePrefix("点击跳过").removePrefix("广告")
            s.startsWith("关闭广告") -> s.removePrefix("关闭广告")
            s.startsWith("skipad") -> s.removePrefix("skipad")
            s.startsWith("skipin") -> s.removePrefix("skipin")
            s.startsWith("skip") -> s.removePrefix("skip")
            else -> null
        }
        if (rest != null) {
            val trimmed = rest.removeSuffix("广告")
            if (COUNTDOWN.matches(trimmed)) return true
        }

        // 「5秒跳过」「3s 跳过」
        val prefixRest = when {
            s.endsWith("跳过广告") -> s.removeSuffix("跳过广告")
            s.endsWith("跳过") -> s.removeSuffix("跳过")
            else -> null
        }
        return prefixRest != null && COUNTDOWN.matches(prefixRest)
    }
}
