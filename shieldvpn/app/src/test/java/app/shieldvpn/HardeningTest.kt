package app.shieldvpn

import org.junit.Assert.*
import org.junit.Test

class HardeningTest {
    private fun qname(vararg l: String): ByteArray {
        val o = java.io.ByteArrayOutputStream()
        for (x in l) { o.write(x.length); o.write(x.toByteArray()) }
        o.write(0); return o.toByteArray()
    }

    /** DNS query bytes with an optional OPT record advertising [opt] as UDP payload size. */
    private fun dns(opt: Int? = null): ByteArray {
        val h = byteArrayOf(0x12, 0x34, 0x01, 0x00, 0, 1, 0, 0, 0, 0, 0, if (opt != null) 1 else 0)
        var d = h + qname("ads", "example", "com") + byteArrayOf(0, 1, 0, 1)
        if (opt != null) d += byteArrayOf(0, 0, 41, (opt shr 8).toByte(), opt.toByte(), 0, 0, 0, 0, 0, 0)
        return d
    }

    private fun udp(d: ByteArray): ByteArray {
        val total = 28 + d.size
        val p = ByteArray(total)
        p[0] = 0x45; p[2] = (total shr 8).toByte(); p[3] = total.toByte(); p[8] = 64; p[9] = 17
        p[12] = 10; p[13] = 111; p[14] = 222.toByte(); p[15] = 1; p[16] = 10; p[17] = 111; p[18] = 222.toByte(); p[19] = 2
        p[20] = 0x9C.toByte(); p[21] = 0x40.toByte(); p[23] = 53
        val ul = 8 + d.size; p[24] = (ul shr 8).toByte(); p[25] = ul.toByte()
        System.arraycopy(d, 0, p, 28, d.size)
        return p
    }

    private fun reply(q: DnsPacket.Query, pkt: ByteArray): ByteArray {
        val r = ByteArray(12 + q.questionEnd - 12 + 16)
        r[0] = pkt[q.dnsOffset]; r[1] = pkt[q.dnsOffset + 1]
        r[2] = 0x81.toByte(); r[3] = 0x80.toByte(); r[5] = 1; r[7] = 1
        System.arraycopy(pkt, q.dnsOffset + 12, r, 12, q.questionEnd - 12)
        return r
    }

    @Test fun replyValidation() {
        val p = udp(dns()); val q = DnsPacket.parse(p, p.size)!!
        val r = reply(q, p)
        assertTrue(DnsPacket.validateReply(q, p, r, r.size))
        assertFalse("short", DnsPacket.validateReply(q, p, r, 11))
        assertFalse("truncated question", DnsPacket.validateReply(q, p, r, 12 + 5))
        val notResp = r.copyOf(); notResp[2] = 0x01; notResp[3] = 0
        assertFalse("QR=0", DnsPacket.validateReply(q, p, notResp, notResp.size))
        val wrongId = r.copyOf(); wrongId[1] = (wrongId[1] + 1).toByte()
        assertFalse("txid", DnsPacket.validateReply(q, p, wrongId, wrongId.size))
        val wrongQ = r.copyOf(); wrongQ[14] = (wrongQ[14] + 1).toByte()
        assertFalse("question", DnsPacket.validateReply(q, p, wrongQ, wrongQ.size))
        val noQ = r.copyOf(); noQ[5] = 0
        assertFalse("qdcount 0 with NOERROR", DnsPacket.validateReply(q, p, noQ, noQ.size))
        val refused = r.copyOf(12); refused[5] = 0; refused[7] = 0; refused[3] = 0x85.toByte()
        assertTrue("REFUSED without question", DnsPacket.validateReply(q, p, refused, 12))
        val badId = refused.copyOf(); badId[0] = (badId[0] + 1).toByte()
        assertFalse(DnsPacket.validateReply(q, p, badId, 12))
    }

    @Test fun ednsClamp() {
        val p = udp(dns(4096)); val q = DnsPacket.parse(p, p.size)!!
        val d = p.copyOfRange(q.dnsOffset, q.dnsOffset + q.dnsLen)
        assertTrue(DnsPacket.clampEdns(d, 0, d.size, q.questionEnd, 1232))
        val size = ((d[d.size - 8].toInt() and 0xFF) shl 8) or (d[d.size - 7].toInt() and 0xFF)
        assertEquals(1232, size)
        assertFalse(DnsPacket.clampEdns(d, 0, d.size, q.questionEnd, 1232)) // already small
        val small = dns(1000)
        assertFalse(DnsPacket.clampEdns(small, 0, small.size, 33, 1232))
        val none = dns(); // no OPT
        assertFalse(DnsPacket.clampEdns(none, 0, none.size, 33, 1232))
        // malformed (OPT cut short) must not throw or change anything
        val cut = dns(4096).copyOf(dns(4096).size - 4)
        assertFalse(DnsPacket.clampEdns(cut, 0, cut.size, 33, 1232))
    }

    @Test fun combinedCap() {
        val cap = ListManager.COMBINED_CAP
        assertEquals(listOf(0, 2), ListManager.fitWithinCap(listOf(20L shl 20, 20L shl 20, 1L shl 20)))
        assertEquals(listOf(0), ListManager.fitWithinCap(listOf(cap - 1)))
        assertEquals(emptyList<Int>(), ListManager.fitWithinCap(listOf(cap)))
        assertEquals(listOf(1), ListManager.fitWithinCap(listOf(-1L, 5L)))
    }

    private fun scan(s: String): ListManager.Scanner =
        ListManager.Scanner().also { val b = s.toByteArray(); it.feed(b, b.size) }

    private fun rules(n: Int) = (1..n).joinToString("\n") { "||ads$it.example.com^" }

    @Test fun listBodySanity() {
        assertTrue(ListManager.looksLikeList(scan("[Adblock Plus 2.0]\n! Title: x\n" + rules(300))))
        assertTrue(ListManager.looksLikeList(scan("! Title: x\n" + rules(300))))
        assertFalse("html", ListManager.looksLikeList(scan("<!DOCTYPE html><html><body>" + "x".repeat(4000) + "</body></html>")))
        assertFalse("tiny", ListManager.looksLikeList(scan("! Title\n||a.com^\n")))
        assertFalse("no header", ListManager.looksLikeList(scan(rules(300))))
        assertFalse("few rules", ListManager.looksLikeList(scan("! Title\n" + rules(10) + "\n!" + "x".repeat(3000))))
        assertFalse("empty", ListManager.looksLikeList(scan("")))
        val sc = scan("! c\n||a.com^\n\n[x]\n||b.com^")
        assertEquals(2L, sc.rules)
    }

    @Test fun listHostsExempt() {
        assertTrue(ListManager.isListHost("easylist.to"))
        assertTrue(ListManager.isListHost("CDN.easylist.to."))
        assertFalse(ListManager.isListHost("raw.githubusercontent.com"))
        assertTrue(ListManager.isListHost("pgl.yoyo.org"))
        assertTrue(ListManager.isListHost("ublockorigin.github.io"))
        assertFalse(ListManager.isListHost("evileasylist.to"))
        assertFalse(ListManager.isListHost("random-user.github.io"))
        assertFalse(ListManager.isListHost("github.com"))
        assertFalse(ListManager.isListHost("githubusercontent.com"))
        assertFalse(ListManager.isListHost("ads.example.com"))
    }

    private fun tcp(flags: Int, seq: Long, ack: Long, payload: Int = 0): ByteArray {
        val total = 40 + payload
        val p = ByteArray(total)
        p[0] = 0x45; p[2] = (total shr 8).toByte(); p[3] = total.toByte(); p[8] = 64; p[9] = 6
        p[12] = 10; p[13] = 111; p[14] = 222.toByte(); p[15] = 1; p[16] = 10; p[17] = 111; p[18] = 222.toByte(); p[19] = 2
        p[20] = 0x9C.toByte(); p[21] = 0x40.toByte(); p[23] = 53
        for (i in 0..3) { p[24 + i] = (seq shr (24 - 8 * i)).toByte(); p[28 + i] = (ack shr (24 - 8 * i)).toByte() }
        p[32] = 0x50; p[33] = flags.toByte()
        return p
    }

    @Test fun tcpRst() {
        val syn = tcp(0x02, 1000, 0); val s = DnsPacket.parseTcp(syn, syn.size)!!
        val r = DnsPacket.buildTcpRst(s)!!
        assertEquals(40, r.size)
        assertEquals(0, DnsPacket.checksum(r, 0, 20))
        var ps = 0L
        for (i in 12 until 20 step 2) ps += ((r[i].toInt() and 0xFF) shl 8) or (r[i + 1].toInt() and 0xFF)
        ps += 6 + 20
        assertEquals(0, DnsPacket.checksum(r, 20, 20, ps))
        assertEquals(0x14, r[33].toInt() and 0xFF)          // RST|ACK
        assertEquals(1001, ((r[28].toInt() and 0xFF) shl 24) or ((r[29].toInt() and 0xFF) shl 16) or
            ((r[30].toInt() and 0xFF) shl 8) or (r[31].toInt() and 0xFF)) // ack = seq + 1
        assertEquals(10, r[12].toInt()); assertEquals(2, r[15].toInt()); assertEquals(1, r[19].toInt())
        val ackSeg = tcp(0x10, 5, 777, 3).let { DnsPacket.parseTcp(it, it.size)!! }
        val r2 = DnsPacket.buildTcpRst(ackSeg)!!
        assertEquals(0x04, r2[33].toInt() and 0xFF)         // plain RST, seq = their ack
        assertEquals(777 and 0xFF, r2[27].toInt() and 0xFF)
        val rstIn = tcp(0x04, 1, 1).let { DnsPacket.parseTcp(it, it.size)!! }
        assertNull(DnsPacket.buildTcpRst(rstIn))            // never answer a RST
        val u = udp(dns())
        assertNull(DnsPacket.parseTcp(u, u.size))
    }

    @Test fun cosmeticAndIndentedLinesNotCounted() {
        val sc = scan("! c\n||a.com^\n##.banner\nexample.com#@#.x\nexample.com#?#div\n  ||indented.com^\n\t||tab.com^\n||b.com^$ image\nsite.com###ad")
        assertEquals(2L, sc.rules)
    }

    @Test fun sessionSlotDoesNotKillSuccessor() {
        val slot = SessionSlot<Any>()
        val a = Any(); val b = Any()
        slot.swap(a)
        assertSame(a, slot.swap(null))      // stop: detach old
        slot.swap(b)                        // quick restart
        assertFalse("old session ending must be ignored", slot.isCurrent(a))
        assertTrue(slot.isCurrent(b))       // an unexpected end of b would still be handled
        assertSame(b, slot.swap(null))
        assertFalse(slot.isCurrent(b))
    }
}
