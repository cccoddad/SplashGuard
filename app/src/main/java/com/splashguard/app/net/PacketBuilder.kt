package com.splashguard.app.net

import java.net.InetAddress
import java.net.InetSocketAddress

/**
 * IPv4 + UDP 封包工具。纯 Kotlin，可单元测试。
 */
object PacketBuilder {

    const val IPV4_HEADER_LEN = 20
    const val UDP_HEADER_LEN = 8
    private const val PROTO_UDP = 17
    private const val DEFAULT_TTL = 64

    data class UdpInfo(
        val srcIp: ByteArray,
        val dstIp: ByteArray,
        val srcPort: Int,
        val dstPort: Int,
        val payloadOffset: Int,
        val payloadLength: Int
    )

    /** 解析出一个 UDP 报文的信息；不是合法 IPv4/UDP 则返回 null */
    fun udpInfo(buf: ByteArray, len: Int): UdpInfo? {
        if (len < IPV4_HEADER_LEN + UDP_HEADER_LEN) return null
        if ((buf[0].toInt() and 0xF0) != 0x40) return null           // 只处理 IPv4
        val ihl = (buf[0].toInt() and 0x0F) * 4
        if (ihl < IPV4_HEADER_LEN || len < ihl + UDP_HEADER_LEN) return null
        if ((buf[9].toInt() and 0xFF) != PROTO_UDP) return null

        val totalLen = ((buf[2].toInt() and 0xFF) shl 8) or (buf[3].toInt() and 0xFF)
        if (totalLen > len) return null

        val srcPort = ((buf[ihl].toInt() and 0xFF) shl 8) or (buf[ihl + 1].toInt() and 0xFF)
        val dstPort = ((buf[ihl + 2].toInt() and 0xFF) shl 8) or (buf[ihl + 3].toInt() and 0xFF)
        val udpLen = ((buf[ihl + 4].toInt() and 0xFF) shl 8) or (buf[ihl + 5].toInt() and 0xFF)
        val payloadLen = udpLen - UDP_HEADER_LEN
        if (payloadLen <= 0 || ihl + UDP_HEADER_LEN + payloadLen > totalLen) return null

        return UdpInfo(
            srcIp = buf.copyOfRange(12, 16),
            dstIp = buf.copyOfRange(16, 20),
            srcPort = srcPort,
            dstPort = dstPort,
            payloadOffset = ihl + UDP_HEADER_LEN,
            payloadLength = payloadLen
        )
    }

    /**
     * 把 DNS 响应用原请求的 IP/UDP 头封装回 tun：源/目的地址与端口互换。
     * UDP 校验和填 0（IPv4 下合法，表示不校验）。
     */
    fun wrapUdpResponse(request: ByteArray, requestLen: Int, payload: ByteArray): ByteArray {
        val info = udpInfo(request, requestLen) ?: return ByteArray(0)
        val total = IPV4_HEADER_LEN + UDP_HEADER_LEN + payload.size
        if (total > 65535) return ByteArray(0)
        val out = ByteArray(total)

        out[0] = 0x45
        out[1] = 0x00
        out[2] = ((total shr 8) and 0xFF).toByte()
        out[3] = (total and 0xFF).toByte()
        out[4] = 0; out[5] = 0
        out[6] = 0x40; out[7] = 0x00                       // DF
        out[8] = DEFAULT_TTL.toByte()
        out[9] = PROTO_UDP.toByte()
        out[10] = 0; out[11] = 0
        System.arraycopy(info.dstIp, 0, out, 12, 4)         // 源 = 原目的
        System.arraycopy(info.srcIp, 0, out, 16, 4)         // 目的 = 原源

        val csum = ipChecksum(out, 0, IPV4_HEADER_LEN)
        out[10] = ((csum shr 8) and 0xFF).toByte()
        out[11] = (csum and 0xFF).toByte()

        out[20] = ((info.dstPort shr 8) and 0xFF).toByte()
        out[21] = (info.dstPort and 0xFF).toByte()
        out[22] = ((info.srcPort shr 8) and 0xFF).toByte()
        out[23] = (info.srcPort and 0xFF).toByte()
        val udpLen = UDP_HEADER_LEN + payload.size
        out[24] = ((udpLen shr 8) and 0xFF).toByte()
        out[25] = (udpLen and 0xFF).toByte()
        out[26] = 0; out[27] = 0                            // checksum 0 = 不校验

        System.arraycopy(payload, 0, out, 28, payload.size)
        return out
    }

    /** IPv4 头校验和（RFC 1071） */
    fun ipChecksum(buf: ByteArray, offset: Int, len: Int): Int {
        var sum = 0L
        var i = offset
        while (i + 1 < offset + len) {
            sum += ((buf[i].toInt() and 0xFF) shl 8) or (buf[i + 1].toInt() and 0xFF)
            i += 2
        }
        if (i < offset + len) sum += (buf[i].toInt() and 0xFF) shl 8
        while (sum shr 16 != 0L) sum = (sum and 0xFFFF) + (sum shr 16)
        return (sum.inv() and 0xFFFF).toInt()
    }

    fun ipToString(b: ByteArray): String =
        "${b[0].toInt() and 0xFF}.${b[1].toInt() and 0xFF}.${b[2].toInt() and 0xFF}.${b[3].toInt() and 0xFF}"

    fun socketAddress(ip: ByteArray, port: Int): InetSocketAddress =
        InetSocketAddress(InetAddress.getByAddress(ip), port)
}
