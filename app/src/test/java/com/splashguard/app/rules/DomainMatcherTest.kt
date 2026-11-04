package com.splashguard.app.rules

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DomainMatcherTest {

    private val matcher = DomainMatcher(
        listOf(
            "example.com",
            "ads.pangle.io",
            "gdt.qq.com",
            "adkwai.com"
        )
    )

    @Test
    fun `T-RULE-01 精确命中`() {
        assertEquals("example.com", matcher.match("example.com"))
    }

    @Test
    fun `T-RULE-02 子域后缀命中`() {
        assertEquals("example.com", matcher.match("a.b.example.com"))
    }

    @Test
    fun `T-RULE-03 不做子串误伤`() {
        assertNull(matcher.match("notexample.com"))
    }

    @Test
    fun `T-RULE-04 不反向命中父域`() {
        assertNull(matcher.match("com"))
        assertNull(matcher.match("qq.com"))
    }

    @Test
    fun `T-RULE-08 大小写不敏感`() {
        assertEquals("ads.pangle.io", matcher.match("ADS.Pangle.IO"))
    }

    @Test
    fun `T-RULE-10 尾点归一化`() {
        assertEquals("example.com", matcher.match("example.com."))
    }

    @Test
    fun `T-RULE-05 AdBlock 写法归一化后可命中`() {
        val r = RuleParser.normalize("||gdt.qq.com^")
        assertEquals("gdt.qq.com", r)
        assertTrue(DomainMatcher(listOf(r!!)).matches("gdt.qq.com"))
    }

    @Test
    fun `T-RULE-06 行内注释被剥离`() {
        assertEquals("gdt.qq.com", RuleParser.normalize("gdt.qq.com   # 优量汇"))
    }

    @Test
    fun `T-RULE-07 空行与注释行被忽略`() {
        assertNull(RuleParser.normalize(""))
        assertNull(RuleParser.normalize("   "))
        assertNull(RuleParser.normalize("# 这是注释"))
        assertNull(RuleParser.normalize("! adblock 注释"))
    }

    @Test
    fun `T-RULE-09 URL 型输入可归一化`() {
        assertEquals("a.com", RuleParser.normalize("https://a.com/path?x=1"))
        assertEquals("a.com", RuleParser.normalize("*.a.com"))
    }

    @Test
    fun `非法规则被拒绝`() {
        assertNull(RuleParser.normalize("not a domain"))
        assertNull(RuleParser.normalize("localhost"))
        assertNull(RuleParser.normalize("例子.com"))
    }

    @Test
    fun `空规则集不崩溃`() {
        val empty = DomainMatcher(emptyList())
        assertFalse(empty.matches("example.com"))
        assertEquals(0, empty.size)
    }
}
