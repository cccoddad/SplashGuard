package com.splashguard.app.net

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class PacketBuilderTest {

    /** 构造一个 IPv4 + UDP 报文：10.111.222.1:40000 → 10.111.222.2:53 */
    private fun buildRequest(payload: ByteArray): ByteArray {
        val total = 20 + 8 + payload.size
        val p = ByteArray(total)
        p[0] = 0x45
        p[2] = ((total shr 8) and 0xFF).toByte()
        p[3] = (total and 0xFF).toByte()
        p[8] = 64
        p[9] = 17
        // src 10.111.222.1
        p[12] = 10; p[13] = 111; p[14] = 222.toByte(); p[15] = 1
        // dst 10.111.222.2
        p[16] = 10; p[17] = 111; p[18] = 222.toByte(); p[19] = 2
        // UDP
        p[20] = 0x9C.toByte(); p[21] = 0x40            // srcPort 40000
        p[22] = 0; p[23] = 53                          // dstPort 53
        val udpLen = 8 + payload.size
        p[24] = ((udpLen shr 8) and 0xFF).toByte()
        p[25] = (udpLen and 0xFF).toByte()
        System.arraycopy(payload, 0, p, 28, payload.size)
        return p
    }

    @Test
    fun `T-PKT-01 地址与端口互换`() {
        val payload = byteArrayOf(1, 2, 3, 4)
        val req = buildRequest(payload)
        val info = PacketBuilder.udpInfo(req, req.size)
        assertNotNull(info)
        assertEquals(40000, info!!.srcPort)
        assertEquals(53, info.dstPort)
        assertEquals("10.111.222.1", PacketBuilder.ipToString(info.srcIp))
        assertEquals("10.111.222.2", PacketBuilder.ipToString(info.dstIp))

        val resp = PacketBuilder.wrapUdpResponse(req, req.size, byteArrayOf(9, 9))
        assertEquals("10.111.222.2", PacketBuilder.ipToString(resp.copyOfRange(12, 16)))
        assertEquals("10.111.222.1", PacketBuilder.ipToString(resp.copyOfRange(16, 20)))
        assertEquals(53, ((resp[20].toInt() and 0xFF) shl 8) or (resp[21].toInt() and 0xFF))
        assertEquals(40000, ((resp[22].toInt() and 0xFF) shl 8) or (resp[23].toInt() and 0xFF))
    }

    @Test
    fun `T-PKT-02 IPv4 头字段正确`() {
        val req = buildRequest(byteArrayOf(1, 2, 3))
        val resp = PacketBuilder.wrapUdpResponse(req, req.size, byteArrayOf(7))
        assertEquals(4, (resp[0].toInt() and 0xF0) shr 4)
        assertEquals(5, resp[0].toInt() and 0x0F)
        assertEquals(17, resp[9].toInt() and 0xFF)
        assertEquals(64, resp[8].toInt() and 0xFF)
        assertEquals(0, PacketBuilder.ipChecksum(resp, 0, 20))   // 校验和自洽
    }

    @Test
    fun `T-PKT-03 总长度字段`() {
        val payload = ByteArray(30) { it.toByte() }
        val req = buildRequest(payload)
        val dnsResp = ByteArray(16)
        val resp = PacketBuilder.wrapUdpResponse(req, req.size, dnsResp)

        val total = ((resp[2].toInt() and 0xFF) shl 8) or (resp[3].toInt() and 0xFF)
        assertEquals(20 + 8 + 16, total)
        assertEquals(20 + 8 + 16, resp.size)
    }

    @Test
    fun `非 UDP 报文返回 null`() {
        val req = buildRequest(byteArrayOf(1))
        req[9] = 6   // TCP
        assertNull(PacketBuilder.udpInfo(req, req.size))
    }

    @Test
    fun `过短报文返回 null`() {
        assertNull(PacketBuilder.udpInfo(ByteArray(10), 10))
    }
}
