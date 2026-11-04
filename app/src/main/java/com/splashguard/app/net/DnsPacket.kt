package com.splashguard.app.net

import com.splashguard.app.core.BlockMode

/**
 * 纯函数式 DNS 报文处理，无 Android 依赖，可直接单元测试。
 *
 * 只处理 Question 段（Question 段不允许压缩指针，所以不需要跟随 0xC0 指针）。
 */
object DnsPacket {

    const val TYPE_A = 1
    const val CLASS_IN = 1
    private const val TTL_SECONDS = 60
    private const val HEADER_LEN = 12

    data class Question(
        val name: String,
        /** Question 段结束位置（= QTYPE + QCLASS 之后），也是应答记录起始位置 */
        val endOffset: Int,
        val qType: Int
    )

    private fun u16(buf: ByteArray, pos: Int): Int =
        ((buf[pos].toInt() and 0xFF) shl 8) or (buf[pos + 1].toInt() and 0xFF)

    /**
     * 解析首个 Question。格式非法返回 null（绝不抛异常——畸形包是常态，不能把服务搞崩）。
     */
    fun parseQuestion(packet: ByteArray, length: Int): Question? {
        if (length < HEADER_LEN) return null
        if (u16(packet, 4) < 1) return null

        val sb = StringBuilder(64)
        var pos = HEADER_LEN
        while (true) {
            if (pos >= length) return null
            val labelLen = packet[pos].toInt() and 0xFF
            if (labelLen == 0) {
                pos++
                break
            }
            // 0xC0 压缩指针不允许出现在 Question 段
            if ((labelLen and 0xC0) != 0) return null
            if (pos + 1 + labelLen > length) return null
            if (sb.isNotEmpty()) sb.append('.')
            for (i in 1..labelLen) {
                val c = packet[pos + i].toInt() and 0xFF
                sb.append(if (c in 0x21..0x7E) c.toChar() else '?')
            }
            pos += 1 + labelLen
            if (sb.length > 255) return null
        }
        if (sb.isEmpty()) return null
        if (pos + 4 > length) return null

        val qType = u16(packet, pos)
        return Question(sb.toString().lowercase(), pos + 4, qType)
    }

    /**
     * 构造拦截响应。
     *
     * - ZERO 模式：NOERROR + 一条 0.0.0.0 的 A 记录
     * - NXDOMAIN 模式：RCODE=3，无应答记录
     *
     * 原查询的 Additional 段（EDNS0 OPT）会被丢弃，ARCOUNT 置 0。
     * 返回空数组表示报文畸形，调用方应直接丢弃。
     */
    fun buildBlockResponse(query: ByteArray, queryLen: Int, mode: BlockMode): ByteArray {
        val q = parseQuestion(query, queryLen) ?: return ByteArray(0)
        val qEnd = q.endOffset
        if (qEnd > queryLen) return ByteArray(0)

        val nx = mode == BlockMode.NXDOMAIN
        val answerLen = if (nx) 0 else 16
        val out = ByteArray(qEnd + answerLen)
        System.arraycopy(query, 0, out, 0, qEnd)

        // Flags: QR=1, RD 沿用原查询, RA=1, RCODE
        var flags = 0x8000 or 0x0080
        if ((query[2].toInt() and 0x01) != 0) flags = flags or 0x0100
        if (nx) flags = flags or 0x0003
        out[2] = ((flags shr 8) and 0xFF).toByte()
        out[3] = (flags and 0xFF).toByte()

        out[4] = 0; out[5] = 1                              // QDCOUNT = 1
        out[6] = 0; out[7] = if (nx) 0 else 1               // ANCOUNT
        out[8] = 0; out[9] = 0                              // NSCOUNT
        out[10] = 0; out[11] = 0                            // ARCOUNT

        if (!nx) {
            var p = qEnd
            out[p++] = 0xC0.toByte(); out[p++] = 0x0C        // NAME 指针 → offset 12
            out[p++] = 0; out[p++] = TYPE_A.toByte()          // TYPE A
            out[p++] = 0; out[p++] = CLASS_IN.toByte()        // CLASS IN
            out[p++] = 0; out[p++] = 0                        // TTL 高位
            out[p++] = 0; out[p++] = TTL_SECONDS.toByte()     // TTL = 60
            out[p++] = 0; out[p++] = 4                        // RDLENGTH = 4
            out[p++] = 0; out[p++] = 0; out[p++] = 0; out[p++] = 0   // 0.0.0.0
        }
        return out
    }

    /** 从响应里读回首条 A 记录，仅测试与自检用 */
    fun firstAnswerIpv4(packet: ByteArray, length: Int): String? {
        val q = parseQuestion(packet, length) ?: return null
        if (u16(packet, 6) < 1) return null
        var p = q.endOffset
        // NAME 可能是 0xC0 指针（2 字节）或完整域名
        if (p < length && (packet[p].toInt() and 0xC0) == 0xC0) {
            p += 2
        } else {
            while (p < length && packet[p].toInt() != 0) p += 1 + (packet[p].toInt() and 0xFF)
            p += 1
        }
        if (p + 10 > length) return null
        p += 8                                   // TYPE(2) CLASS(2) TTL(4)
        val rdLen = u16(packet, p)
        p += 2
        if (rdLen != 4 || p + 4 > length) return null
        return "${packet[p].toInt() and 0xFF}.${packet[p + 1].toInt() and 0xFF}." +
            "${packet[p + 2].toInt() and 0xFF}.${packet[p + 3].toInt() and 0xFF}"
    }

    /** RCODE（低 4 位） */
    fun rcode(packet: ByteArray): Int = packet[3].toInt() and 0x0F

    fun answerCount(packet: ByteArray): Int = u16(packet, 6)

    fun additionalCount(packet: ByteArray): Int = u16(packet, 10)

    fun queryId(packet: ByteArray): Int = u16(packet, 0)
}
