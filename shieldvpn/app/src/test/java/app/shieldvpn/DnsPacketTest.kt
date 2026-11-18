package app.shieldvpn

import org.junit.Assert.*
import org.junit.Test

class DnsPacketTest {
    private fun name(vararg labels: String): ByteArray {
        val o = java.io.ByteArrayOutputStream()
        for (l in labels) { o.write(l.length); o.write(l.toByteArray()) }
        o.write(0)
        return o.toByteArray()
    }

    private fun dnsQuery(nameBytes: ByteArray, qtype: Int = 1, flags: Int = 0x0100, qd: Int = 1): ByteArray {
        val h = byteArrayOf(0x12, 0x34, (flags shr 8).toByte(), flags.toByte(), 0, qd.toByte(), 0, 0, 0, 0, 0, 0)
        return h + nameBytes + byteArrayOf((qtype shr 8).toByte(), qtype.toByte(), 0, 1)
    }

    private fun packet(dns: ByteArray, ihl: Int = 20, frag: Int = 0, proto: Int = 17, dport: Int = 53): ByteArray {
        val total = ihl + 8 + dns.size
        val p = ByteArray(total)
        p[0] = (0x40 or (ihl / 4)).toByte()
        p[2] = (total shr 8).toByte(); p[3] = total.toByte()
        p[6] = (frag shr 8).toByte(); p[7] = frag.toByte()
        p[8] = 64; p[9] = proto.toByte()
        p[12] = 10; p[13] = 111; p[14] = 222.toByte(); p[15] = 1
        p[16] = 10; p[17] = 111; p[18] = 222.toByte(); p[19] = 2
        val u = ihl
        p[u] = 0x9C.toByte(); p[u + 1] = 0x40 // sport 40000
        p[u + 2] = (dport shr 8).toByte(); p[u + 3] = dport.toByte()
        val ul = 8 + dns.size
        p[u + 4] = (ul shr 8).toByte(); p[u + 5] = ul.toByte()
        System.arraycopy(dns, 0, p, u + 8, dns.size)
        return p
    }

    @Test fun parsesSimpleQuery() {
        val p = packet(dnsQuery(name("Ads", "Example", "com")))
        val q = DnsPacket.parse(p, p.size)!!
        assertEquals("ads.example.com", q.qname)
        assertEquals(1, q.qtype); assertEquals(0x1234, q.id); assertEquals(40000, q.srcPort)
    }

    @Test fun acceptsIpOptions() {
        val p = packet(dnsQuery(name("a", "com")), ihl = 24)
        assertEquals("a.com", DnsPacket.parse(p, p.size)!!.qname)
    }

    @Test fun rejectsFragmentsAndWrongProto() {
        val d = dnsQuery(name("a", "com"))
        assertNull(packet(d, frag = 0x2000).let { DnsPacket.parse(it, it.size) })
        assertNull(packet(d, frag = 0x0010).let { DnsPacket.parse(it, it.size) })
        assertNull(packet(d, proto = 6).let { DnsPacket.parse(it, it.size) })
        assertNull(packet(d, dport = 80).let { DnsPacket.parse(it, it.size) })
    }

    @Test fun rejectsTruncatedAtEveryLength() {
        val p = packet(dnsQuery(name("ads", "example", "com")))
        for (n in 0 until p.size) assertNull("len $n", DnsPacket.parse(p, n))
    }

    @Test fun rejectsBadHeaders() {
        val d = dnsQuery(name("a", "com"))
        val p = packet(d)
        val bad = p.copyOf(); bad[0] = 0x65 // version 6
        assertNull(DnsPacket.parse(bad, bad.size))
        val ihl = p.copyOf(); ihl[0] = 0x43
        assertNull(DnsPacket.parse(ihl, ihl.size))
        val tl = p.copyOf(); tl[3] = (tl[3] + 5).toByte()
        assertNull(DnsPacket.parse(tl, tl.size))
        assertNull(packet(dnsQuery(name("a", "com"), flags = 0x8100)).let { DnsPacket.parse(it, it.size) })
        assertNull(packet(dnsQuery(name("a", "com"), qd = 2)).let { DnsPacket.parse(it, it.size) })
    }

    @Test fun rejectsPointerLoopAndForwardPointer() {
        // name at offset 12 of dns: pointer to itself
        val self = dnsQuery(byteArrayOf(0xC0.toByte(), 12, 0))
        assertNull(packet(self).let { DnsPacket.parse(it, it.size) })
        val fwd = dnsQuery(byteArrayOf(0xC0.toByte(), 20, 0))
        assertNull(packet(fwd).let { DnsPacket.parse(it, it.size) })
        val toHeader = dnsQuery(byteArrayOf(0xC0.toByte(), 2, 0))
        assertNull(packet(toHeader).let { DnsPacket.parse(it, it.size) })
    }

    @Test fun rejectsOverlongLabelAndReservedBits() {
        val long = ByteArray(1 + 64 + 1).also { it[0] = 64 }
        assertNull(packet(dnsQuery(long)).let { DnsPacket.parse(it, it.size) })
        assertNull(packet(dnsQuery(byteArrayOf(0x41, 0))).let { DnsPacket.parse(it, it.size) })
        val l = name(*Array(5) { "a".repeat(62) }) // > 255 wire
        assertNull(packet(dnsQuery(l)).let { DnsPacket.parse(it, it.size) })
    }

    @Test fun nxdomainReplyIsWellFormed() {
        val p = packet(dnsQuery(name("ads", "example", "com"), qtype = 28))
        val q = DnsPacket.parse(p, p.size)!!
        val r = DnsPacket.buildNxdomain(q, p)
        // IP checksum verifies to zero
        assertEquals(0, DnsPacket.checksum(r, 0, 20))
        // UDP checksum with pseudo header verifies to zero
        var ps = 0L
        for (i in 12 until 20 step 2) ps += ((r[i].toInt() and 0xFF) shl 8) or (r[i + 1].toInt() and 0xFF)
        ps += 17 + (r.size - 20)
        assertEquals(0, DnsPacket.checksum(r, 20, r.size - 20, ps))
        // addresses and ports swapped
        assertEquals(10, r[12].toInt()); assertEquals(2, r[15].toInt())
        assertEquals(1, r[19].toInt())
        assertEquals(53, ((r[20].toInt() and 0xFF) shl 8) or (r[21].toInt() and 0xFF))
        // DNS: id, QR+RD+RA, rcode 3, qdcount 1, question echoed
        assertEquals(0x12, r[28].toInt()); assertEquals(0x34, r[29].toInt())
        assertEquals(0x81, r[30].toInt() and 0xFF)
        assertEquals(0x83, r[31].toInt() and 0xFF)
        assertEquals(1, r[33].toInt())
        // the reply itself is not a query
        assertNull(DnsPacket.parse(r, r.size))
        assertEquals(28 + 12 + q.questionEnd - 12, r.size)
    }

    @Test fun truncatedReplySetsTc() {
        val p = packet(dnsQuery(name("a", "com")))
        val q = DnsPacket.parse(p, p.size)!!
        val r = DnsPacket.buildTruncated(q, p)
        assertEquals(0x02, r[30].toInt() and 0x02)
        assertEquals(0, r[31].toInt() and 0x0F)
    }
}

class NetTest {
    @org.junit.Test fun upstreamValidation() {
        org.junit.Assert.assertNotNull(Net.parseUpstream("9.9.9.9"))
        org.junit.Assert.assertNotNull(Net.parseUpstream(" 94.140.14.14 "))
        for (bad in listOf("", "1.1.1", "1.1.1.1.1", "256.1.1.1", "a.b.c.d", "127.0.0.1", "0.0.0.0",
            "224.0.0.1", "10.111.222.2", "1.1.1.-1", "1..1.1"))
            org.junit.Assert.assertNull(bad, Net.parseUpstream(bad))
    }
}
