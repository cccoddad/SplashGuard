package com.splashguard.app.rules

/**
 * 规则归一化 + 后缀匹配。纯 Kotlin，无 Android 依赖，可直接跑 JVM 单元测试。
 */
object RuleParser {

    private val VALID = Regex("^[a-z0-9]([a-z0-9._-]*[a-z0-9])?$")

    /**
     * 把一行原始文本归一化成可匹配的域名。返回 null 表示这行应被忽略。
     *
     * 支持：
     *  - 注释行 `# xxx` / `! xxx`
     *  - 行内注释 `example.com  # 说明`
     *  - AdBlock 写法 `||example.com^`
     *  - 通配前缀 `*.example.com`
     *  - 误粘贴的 URL `https://example.com/path`
     */
    fun normalize(raw: String): String? {
        var s = raw.trim()
        if (s.isEmpty()) return null
        if (s.startsWith("#") || s.startsWith("!")) return null

        val hash = s.indexOf('#')
        if (hash >= 0) s = s.substring(0, hash).trim()
        if (s.isEmpty()) return null

        s = s.removePrefix("||")
        s = s.removeSuffix("^")
        s = s.removePrefix("*.")
        if (s.contains("://")) s = s.substringAfter("://")
        s = s.substringBefore('/')
        s = s.substringBefore('?')
        s = s.substringBefore(':')
        s = s.trim().trim('.')
        if (s.isEmpty()) return null
        s = s.lowercase()
        if (!s.contains('.')) return null
        if (!VALID.matches(s)) return null
        return s
    }
}

/**
 * 后缀匹配：规则 `example.com` 命中 `example.com` 与 `a.b.example.com`，
 * 但不命中 `notexample.com`（域名标签边界），也不反向命中 `com`。
 */
class DomainMatcher(rules: Collection<String>) {

    private val exact: MutableSet<String> = HashSet(rules.size.coerceAtLeast(1))

    init {
        for (rule in rules) exact.add(rule.lowercase())
    }

    val size: Int get() = exact.size

    /** 返回命中的规则文本，未命中返回 null */
    fun match(hostRaw: String): String? {
        val host = hostRaw.trim().trimEnd('.').lowercase()
        if (host.isEmpty() || !host.contains('.')) return null
        var start = 0
        while (start < host.length) {
            val cand = host.substring(start)
            if (exact.contains(cand)) return cand
            val dot = host.indexOf('.', start)
            if (dot < 0) break
            start = dot + 1
        }
        return null
    }

    fun matches(host: String): Boolean = match(host) != null
}
