package app.shieldvpn

/** Strict IPv4/UDP/DNS parsing and building. Pure JVM, no Android deps. */
object DnsPacket {
    const val MAX_DNS = 4096
    private const val MAX_NAME_WIRE = 255
    private const val MAX_HOPS = 8

    class Query(
        val srcAddr: Int, val dstAddr: Int,
        val srcPort: Int, val dstPort: Int,
        /** Offset/length of the DNS message within the packet buffer. */
        val dnsOffset: Int, val dnsLen: Int,
        val id: Int, val flags: Int,
        val qname: String, val qtype: Int, val qclass: Int,
        /** Question section end, relative to dnsOffset. */
        val questionEnd: Int,
    )

    private fun u8(b: ByteArray, i: Int) = b[i].toInt() and 0xFF
    private fun u16(b: ByteArray, i: Int) = (u8(b, i) shl 8) or u8(b, i + 1)
    private fun u32(b: ByteArray, i: Int) =
        (u8(b, i) shl 24) or (u8(b, i + 1) shl 16) or (u8(b, i + 2) shl 8) or u8(b, i + 3)

    private fun put16(b: ByteArray, i: Int, v: Int) {
        b[i] = (v shr 8).toByte(); b[i + 1] = v.toByte()
    }
    private fun put32(b: ByteArray, i: Int, v: Int) {
        b[i] = (v ushr 24).toByte(); b[i + 1] = (v ushr 16).toByte()
        b[i + 2] = (v ushr 8).toByte(); b[i + 3] = v.toByte()
    }

    /** Ones-complement sum helper; returns the folded, inverted 16-bit checksum. */
    fun checksum(b: ByteArray, off: Int, len: Int, initial: Long = 0): Int {
        var sum = initial
        var i = off
        val end = off + len
        while (i + 1 < end) { sum += u16(b, i); i += 2 }
        if (i < end) sum += u8(b, i) shl 8
        while (sum ushr 16 != 0L) sum = (sum and 0xFFFF) + (sum ushr 16)
        return (sum.toInt().inv()) and 0xFFFF
    }

    /**
     * Parse an IPv4/UDP datagram to port 53 holding a standard DNS query with
     * exactly one question. Returns null for anything else (caller drops it).
     */
    fun parse(buf: ByteArray, len: Int): Query? {
        if (len < 0 || len > buf.size || len < 20) return null
        val vihl = u8(buf, 0)
        if (vihl ushr 4 != 4) return null
        val ihl = (vihl and 0x0F) * 4
        if (ihl < 20 || ihl > len) return null
        val total = u16(buf, 2)
        if (total < ihl + 8 || total > len) return null
        // Reject fragments (MF set or non-zero offset); options are skipped via IHL.
        val frag = u16(buf, 6)
        if ((frag and 0x2000) != 0 || (frag and 0x1FFF) != 0) return null
        if (u8(buf, 9) != 17) return null
        val udp = ihl
        val srcPort = u16(buf, udp)
        val dstPort = u16(buf, udp + 2)
        if (dstPort != 53) return null
        val udpLen = u16(buf, udp + 4)
        if (udpLen < 8 + 12 || udpLen > total - ihl) return null
        val dnsOff = udp + 8
        val dnsLen = udpLen - 8
        if (dnsLen > MAX_DNS) return null

        val flags = u16(buf, dnsOff + 2)
        if (flags and 0x8000 != 0) return null            // must be a query
        if ((flags shr 11) and 0xF != 0) return null      // opcode QUERY only
        if (u16(buf, dnsOff + 4) != 1) return null        // qdcount
        if (u16(buf, dnsOff + 6) != 0) return null        // ancount

        val end = dnsOff + dnsLen
        val nr = readName(buf, dnsOff, dnsOff + 12, end) ?: return null
        if (nr.next + 4 > end) return null
        val qtype = u16(buf, nr.next)
        val qclass = u16(buf, nr.next + 2)
        return Query(
            u32(buf, 12), u32(buf, 16), srcPort, dstPort, dnsOff, dnsLen,
            u16(buf, dnsOff), flags, nr.name, qtype, qclass, nr.next + 4 - dnsOff,
        )
    }

    private class NameResult(val name: String, val next: Int)

    /**
     * Read a (possibly compressed) name at [start]. Pointers must point strictly
     * backwards, at or after the 12-byte header, and hops are limited.
     */
    private fun readName(b: ByteArray, msgStart: Int, start: Int, end: Int): NameResult? {
        val sb = StringBuilder()
        var pos = start
        var next = -1
        var hops = 0
        var wire = 0
        while (true) {
            if (pos >= end) return null
            val l = u8(b, pos)
            when {
                l == 0 -> {
                    if (next < 0) next = pos + 1
                    wire++
                    if (wire > MAX_NAME_WIRE) return null
                    return NameResult(sb.toString().lowercase(), next)
                }
                l and 0xC0 == 0xC0 -> {
                    if (pos + 1 >= end) return null
                    if (++hops > MAX_HOPS) return null
                    val target = msgStart + (((l and 0x3F) shl 8) or u8(b, pos + 1))
                    if (target < msgStart + 12 || target >= pos) return null
                    if (next < 0) next = pos + 2
                    pos = target
                }
                l and 0xC0 != 0 -> return null
                else -> {
                    if (pos + 1 + l > end) return null
                    wire += l + 1
                    if (wire > MAX_NAME_WIRE) return null
                    if (sb.isNotEmpty()) sb.append('.')
                    for (i in 1..l) sb.append((u8(b, pos + i)).toChar())
                    pos += 1 + l
                }
            }
        }
    }

    /** Wrap a DNS payload in IPv4+UDP, replying to [q] (addresses/ports swapped). */
    fun buildReplyPacket(q: Query, dns: ByteArray, dnsLen: Int): ByteArray {
        val total = 20 + 8 + dnsLen
        require(dnsLen in 0..MAX_DNS && total <= 65535)
        val p = ByteArray(total)
        p[0] = 0x45
        put16(p, 2, total)
        put16(p, 6, 0x4000) // DF
        p[8] = 64
        p[9] = 17
        put32(p, 12, q.dstAddr)
        put32(p, 16, q.srcAddr)
        put16(p, 10, checksum(p, 0, 20))
        put16(p, 20, q.dstPort)
        put16(p, 22, q.srcPort)
        put16(p, 24, 8 + dnsLen)
        System.arraycopy(dns, 0, p, 28, dnsLen)
        // UDP checksum with pseudo header
        var pseudo = 0L
        pseudo += (q.dstAddr ushr 16) and 0xFFFF; pseudo += q.dstAddr and 0xFFFF
        pseudo += (q.srcAddr ushr 16) and 0xFFFF; pseudo += q.srcAddr and 0xFFFF
        pseudo += 17; pseudo += 8 + dnsLen
        var c = checksum(p, 20, 8 + dnsLen, pseudo)
        if (c == 0) c = 0xFFFF
        put16(p, 26, c)
        return p
    }

    /** DNS response with no answers: header + echoed question. */
    private fun buildHeaderOnly(q: Query, orig: ByteArray, rcode: Int, tc: Boolean): ByteArray {
        val qLen = q.questionEnd - 12
        val d = ByteArray(12 + qLen)
        put16(d, 0, q.id)
        var f = 0x8000 or (q.flags and 0x0100) or 0x0080 or (rcode and 0xF)
        if (tc) f = f or 0x0200
        put16(d, 2, f)
        put16(d, 4, 1)
        System.arraycopy(orig, q.dnsOffset + 12, d, 12, qLen)
        return d
    }

    fun buildNxdomain(q: Query, orig: ByteArray): ByteArray {
        val d = buildHeaderOnly(q, orig, 3, false)
        return buildReplyPacket(q, d, d.size)
    }

    /** Truncated (TC) empty reply, used when a real answer would exceed the TUN MTU. */
    fun buildTruncated(q: Query, orig: ByteArray): ByteArray {
        val d = buildHeaderOnly(q, orig, 0, true)
        return buildReplyPacket(q, d, d.size)
    }

    // ---- upstream reply validation ----

    /**
     * Accept an upstream reply only if it is a response (QR=1), carries the
     * query's id, and echoes the question section byte for byte.
     */
    fun validateReply(q: Query, orig: ByteArray, reply: ByteArray, len: Int): Boolean {
        if (len < 12 || len > reply.size) return false
        if (u16(reply, 0) != q.id) return false
        val flags = u16(reply, 2)
        if (flags and 0x8000 == 0) return false
        val qd = u16(reply, 4)
        // FORMERR/REFUSED etc. may legitimately carry no question section.
        if (qd == 0) return (flags and 0xF) != 0
        if (qd != 1) return false
        val qLen = q.questionEnd - 12
        if (len < 12 + qLen) return false
        for (i in 0 until qLen) if (reply[12 + i] != orig[q.dnsOffset + 12 + i]) return false
        return true
    }

    // ---- EDNS(0) payload clamp ----

    /** Returns the offset after a (possibly compressed) name, or -1. */
    private fun skipName(b: ByteArray, start: Int, end: Int): Int {
        var p = start
        while (true) {
            if (p >= end) return -1
            val l = u8(b, p)
            when {
                l == 0 -> return p + 1
                l and 0xC0 == 0xC0 -> return if (p + 2 <= end) p + 2 else -1
                l and 0xC0 != 0 -> return -1
                else -> p += 1 + l
            }
        }
    }

    /**
     * In-place: if the DNS message at [off] carries an EDNS OPT record advertising a UDP
     * payload larger than [max], lower it to [max]. Returns true if it was changed.
     * Malformed messages are left untouched.
     */
    fun clampEdns(b: ByteArray, off: Int, len: Int, questionEnd: Int, max: Int = 1232): Boolean {
        val end = off + len
        if (len < 12 || end > b.size) return false
        val records = u16(b, off + 6) + u16(b, off + 8) + u16(b, off + 10)
        var p = off + questionEnd
        for (n in 0 until records) {
            val nameEnd = skipName(b, p, end)
            if (nameEnd < 0 || nameEnd + 10 > end) return false
            val type = u16(b, nameEnd)
            val rdlen = u16(b, nameEnd + 8)
            if (type == 41 && b[p].toInt() == 0 && nameEnd == p + 1) {
                if (u16(b, nameEnd + 2) > max) { put16(b, nameEnd + 2, max); return true }
                return false
            }
            p = nameEnd + 10 + rdlen
            if (p > end) return false
        }
        return false
    }

    // ---- TCP to the fake DNS address: answer with RST so clients fail fast ----

    class TcpSeg(
        val srcAddr: Int, val dstAddr: Int, val srcPort: Int, val dstPort: Int,
        val seq: Long, val ack: Long, val flags: Int, val payloadLen: Int,
    )

    fun parseTcp(buf: ByteArray, len: Int): TcpSeg? {
        if (len < 20 || len > buf.size) return null
        val vihl = u8(buf, 0)
        if (vihl ushr 4 != 4) return null
        val ihl = (vihl and 0x0F) * 4
        if (ihl < 20 || ihl + 20 > len) return null
        val total = u16(buf, 2)
        if (total < ihl + 20 || total > len) return null
        val frag = u16(buf, 6)
        if ((frag and 0x2000) != 0 || (frag and 0x1FFF) != 0) return null
        if (u8(buf, 9) != 6) return null
        val t = ihl
        if (u16(buf, t + 2) != 53) return null
        val doff = (u8(buf, t + 12) ushr 4) * 4
        if (doff < 20 || t + doff > total) return null
        return TcpSeg(
            u32(buf, 12), u32(buf, 16), u16(buf, t), u16(buf, t + 2),
            u32(buf, t + 4).toLong() and 0xFFFFFFFFL, u32(buf, t + 8).toLong() and 0xFFFFFFFFL,
            u8(buf, t + 13), total - t - doff,
        )
    }

    /** RST reply per RFC 793, or null if the segment is itself a RST. */
    fun buildTcpRst(s: TcpSeg): ByteArray? {
        val rst = 0x04; val syn = 0x02; val fin = 0x01; val ackF = 0x10
        if (s.flags and rst != 0) return null
        val p = ByteArray(40)
        p[0] = 0x45
        put16(p, 2, 40)
        put16(p, 6, 0x4000)
        p[8] = 64
        p[9] = 6
        put32(p, 12, s.dstAddr)
        put32(p, 16, s.srcAddr)
        put16(p, 10, checksum(p, 0, 20))
        put16(p, 20, s.dstPort)
        put16(p, 22, s.srcPort)
        if (s.flags and ackF != 0) {
            put32(p, 24, s.ack.toInt())
            p[33] = rst.toByte()
        } else {
            val a = s.seq + s.payloadLen + (if (s.flags and syn != 0) 1 else 0) + (if (s.flags and fin != 0) 1 else 0)
            put32(p, 28, a.toInt())
            p[33] = (rst or ackF).toByte()
        }
        p[32] = 0x50 // data offset 5 words
        var ps = 0L
        ps += (s.dstAddr ushr 16) and 0xFFFF; ps += s.dstAddr and 0xFFFF
        ps += (s.srcAddr ushr 16) and 0xFFFF; ps += s.srcAddr and 0xFFFF
        ps += 6; ps += 20
        put16(p, 36, checksum(p, 20, 20, ps))
        return p
    }
}
