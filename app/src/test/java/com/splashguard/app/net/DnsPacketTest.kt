package com.splashguard.app.net

import com.splashguard.app.core.BlockMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream

class DnsPacketTest {

    /** 手工构造一个 A 记录查询报文 */
    private fun buildQuery(
        name: String,
        id: Int = 0xABCD,
        withEdns: Boolean = false
    ): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(byteArrayOf((id shr 8).toByte(), (id and 0xFF).toByte()))
        out.write(byteArrayOf(0x01, 0x00))                                  // flags: RD
        out.write(byteArrayOf(0x00, 0x01))                                  // QDCOUNT = 1
        out.write(byteArrayOf(0x00, 0x00))                                  // ANCOUNT
        out.write(byteArrayOf(0x00, 0x00))                                  // NSCOUNT
        out.write(byteArrayOf(0x00, if (withEdns) 0x01 else 0x00))          // ARCOUNT
        name.split('.').forEach { label ->
            out.write(label.length)
            out.write(label.toByteArray(Charsets.US_ASCII))
        }
        out.write(0x00)                                                     // 根标签结束
        out.write(byteArrayOf(0x00, 0x01))                                  // QTYPE = A
        out.write(byteArrayOf(0x00, 0x01))                                  // QCLASS = IN
        if (withEdns) {
            out.write(0x00)                                                 // 根域名
            out.write(byteArrayOf(0x00, 0x29))                              // TYPE = OPT
            out.write(byteArrayOf(0x10, 0x00))                              // UDP payload 4096
            out.write(byteArrayOf(0x00, 0x00, 0x00, 0x00))                  // TTL(ext rcode/version/flags)
            out.write(byteArrayOf(0x00, 0x00))                              // RDLEN = 0
        }
        return out.toByteArray()
    }

    @Test
    fun `T-DNS-01 解析标准查询`() {
        val q = buildQuery("ads.pangle.io")
        val parsed = DnsPacket.parseQuestion(q, q.size)
        assertNotNull(parsed)
        assertEquals("ads.pangle.io", parsed!!.name)
        assertEquals(DnsPacket.TYPE_A, parsed.qType)
    }

    @Test
    fun `T-DNS-02 解析多级域名`() {
        val q = buildQuery("a.b.c.example.com")
        assertEquals("a.b.c.example.com", DnsPacket.parseQuestion(q, q.size)!!.name)
    }

    @Test
    fun `T-DNS-03 报文过短返回 null`() {
        assertNull(DnsPacket.parseQuestion(ByteArray(8), 8))
    }

    @Test
    fun `T-DNS-04 QDCOUNT 为 0 返回 null`() {
        val q = buildQuery("a.com")
        q[4] = 0
        q[5] = 0
        assertNull(DnsPacket.parseQuestion(q, q.size))
    }

    @Test
    fun `T-DNS-05 构造 0_0_0_0 响应`() {
        val q = buildQuery("ads.pangle.io")
        val qEnd = DnsPacket.parseQuestion(q, q.size)!!.endOffset
        val resp = DnsPacket.buildBlockResponse(q, q.size, BlockMode.ZERO)

        assertEquals(qEnd + 16, resp.size)
        assertEquals(0, DnsPacket.rcode(resp))
        assertEquals(1, DnsPacket.answerCount(resp))
        assertEquals(0, DnsPacket.additionalCount(resp))
        assertEquals("0.0.0.0", DnsPacket.firstAnswerIpv4(resp, resp.size))
        // QR 位必须置 1
        assertTrue((resp[2].toInt() and 0x80) != 0)
    }

    @Test
    fun `T-DNS-06 构造 NXDOMAIN 响应`() {
        val q = buildQuery("ads.pangle.io")
        val qEnd = DnsPacket.parseQuestion(q, q.size)!!.endOffset
        val resp = DnsPacket.buildBlockResponse(q, q.size, BlockMode.NXDOMAIN)

        assertEquals(qEnd, resp.size)
        assertEquals(3, DnsPacket.rcode(resp))
        assertEquals(0, DnsPacket.answerCount(resp))
        assertEquals(0, DnsPacket.additionalCount(resp))
    }

    @Test
    fun `T-DNS-07 响应保留查询 ID`() {
        val q = buildQuery("x.com", id = 0x1234)
        val resp = DnsPacket.buildBlockResponse(q, q.size, BlockMode.ZERO)
        assertEquals(0x1234, DnsPacket.queryId(resp))
    }

    @Test
    fun `T-DNS-08 带 EDNS 的查询也能正确构造`() {
        val q = buildQuery("ads.pangle.io", withEdns = true)
        val qEnd = DnsPacket.parseQuestion(q, q.size)!!.endOffset
        val resp = DnsPacket.buildBlockResponse(q, q.size, BlockMode.ZERO)

        assertEquals(qEnd + 16, resp.size)          // OPT 被丢弃
        assertEquals(0, DnsPacket.additionalCount(resp))
        assertEquals("0.0.0.0", DnsPacket.firstAnswerIpv4(resp, resp.size))
    }

    @Test
    fun `T-DNS-09 响应可回读域名且大小写归一`() {
        val q = buildQuery("ADS.Pangle.IO")
        val parsed = DnsPacket.parseQuestion(q, q.size)!!
        assertEquals("ads.pangle.io", parsed.name)
        val resp = DnsPacket.buildBlockResponse(q, q.size, BlockMode.ZERO)
        assertEquals("ads.pangle.io", DnsPacket.parseQuestion(resp, resp.size)!!.name)
    }

    @Test
    fun `畸形报文不抛异常`() {
        val garbage = byteArrayOf(0, 1, 2, 3, 0, 1, 0, 1, 0, 0, 0, 0, 0xFF.toByte())
        assertNull(DnsPacket.parseQuestion(garbage, garbage.size))
        assertEquals(0, DnsPacket.buildBlockResponse(garbage, garbage.size, BlockMode.ZERO).size)
    }
}
