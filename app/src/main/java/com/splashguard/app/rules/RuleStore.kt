package com.splashguard.app.rules

import android.content.Context
import com.splashguard.app.core.Prefs

/**
 * 规则仓库：内置种子库（assets）+ 用户自定义规则（SharedPreferences）。
 * 热更新：保存规则后调用 reload() 即可生效，不需要重启隧道。
 */
object RuleStore {

    @Volatile
    private var matcher = DomainMatcher(emptyList())

    @Volatile
    var builtinCount: Int = 0
        private set

    @Volatile
    var ruleCount: Int = 0
        private set

    fun reload(context: Context) {
        val set = LinkedHashSet<String>(256)

        runCatching {
            context.assets.open("rules/splash_ads.txt").bufferedReader().useLines { lines ->
                lines.forEach { line -> RuleParser.normalize(line)?.let { set.add(it) } }
            }
        }
        builtinCount = set.size

        Prefs(context).userRules.lineSequence().forEach { line ->
            RuleParser.normalize(line)?.let { set.add(it) }
        }

        matcher = DomainMatcher(set)
        ruleCount = set.size
    }

    /** 返回命中的规则，未命中返回 null */
    fun match(host: String): String? = matcher.match(host)
}
