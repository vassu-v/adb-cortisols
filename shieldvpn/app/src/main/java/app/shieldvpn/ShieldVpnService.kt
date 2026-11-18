package app.shieldvpn

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.VpnService
import android.os.ParcelFileDescriptor
import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import android.system.StructPollfd
import app.shieldvpn.core.Engine
import java.io.FileInputStream
import java.io.FileOutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.SocketTimeoutException
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

class ShieldVpnService : VpnService() {
    enum class FilterState { OFF, LOADING, READY, FAILED }

    companion object {
        const val ACTION_START = "app.shieldvpn.START"
        const val ACTION_STOP = "app.shieldvpn.STOP"
        const val ACTION_RELOAD = "app.shieldvpn.RELOAD"
        const val FAKE_DNS = "10.111.222.2"
        const val TUN_ADDR = "10.111.222.1"
        private const val MTU = 1500
        private const val QUERY_DEADLINE_MS = 2000L
        private const val EDNS_MAX = 1232
        private const val CHANNEL = "shieldvpn"
        private const val NOTIF_ID = 1
        private val FAKE_DNS_INT = FAKE_DNS.split('.').fold(0) { a, b -> (a shl 8) or b.toInt() }

        @Volatile var running = false
        /** Whether a usable engine is loaded. The UI must not say "Protected" unless READY. */
        @Volatile var filterState = FilterState.OFF
        /** Queries dropped because the forwarding queue was full (a count, never names). */
        val dropped = AtomicLong(0)
    }

    /**
     * Everything tied to one TUN lifetime. The reader thread is the only closer of [fd],
     * so a stop followed by a quick start can never touch the new session's descriptor.
     */
    private inner class Session(val fd: ParcelFileDescriptor) {
        @Volatile var stopping = false
        @Volatile private var closed = false
        private val writeLock = Any()
        private val out = FileOutputStream(fd.fileDescriptor)
        val pool = ThreadPoolExecutor(
            3, 3, 30, TimeUnit.SECONDS, ArrayBlockingQueue(32),
            { _, _ -> dropped.incrementAndGet() }, // explicit drop: client retries
        ).also { it.allowCoreThreadTimeOut(true) }
        val thread = Thread({ run() }, "tun-reader")

        fun stop() {
            stopping = true
            pool.shutdownNow()
        }

        fun write(p: ByteArray) {
            synchronized(writeLock) { if (!stopping && !closed) try { out.write(p) } catch (_: Exception) {} }
        }

        /**
         * poll() with a 500 ms timeout so the loop re-checks `stopping` even without traffic;
         * read() only runs when poll reports data, so it never blocks.
         */
        private fun run() {
            val input = FileInputStream(fd.fileDescriptor)
            val buf = ByteArray(4096 + 64)
            val pfd = StructPollfd()
            pfd.fd = fd.fileDescriptor
            pfd.events = OsConstants.POLLIN.toShort()
            val arr = arrayOf(pfd)
            val bad = OsConstants.POLLERR or OsConstants.POLLHUP or OsConstants.POLLNVAL
            try {
                while (!stopping) {
                    pfd.revents = 0
                    val r = try { Os.poll(arr, 500) } catch (e: ErrnoException) {
                        if (e.errno == OsConstants.EINTR) continue else break
                    }
                    if (r <= 0) continue
                    val ev = pfd.revents.toInt()
                    if (ev and OsConstants.POLLIN == 0 && ev and bad != 0) break
                    val n = input.read(buf)
                    if (n < 0) break
                    if (n == 0) continue
                    dispatch(buf, n)
                }
            } catch (_: Exception) {
            } finally {
                stopping = true
                pool.shutdownNow()
                synchronized(writeLock) {
                    closed = true
                    try { fd.close() } catch (_: Exception) {}
                }
                onSessionEnded(this)
            }
        }

        private fun dispatch(buf: ByteArray, n: Int) {
            val q = DnsPacket.parse(buf, n)
            if (q == null) {
                val t = DnsPacket.parseTcp(buf, n)
                if (t != null && t.dstAddr == FAKE_DNS_INT) DnsPacket.buildTcpRst(t)?.let { write(it) }
                return
            }
            if (q.dstAddr != FAKE_DNS_INT) return
            // Blocked names are answered inline: cheap, and never queued behind slow upstream queries.
            if (!ListManager.isListHost(q.qname)) {
                val eng = engineRef.get()
                if (eng != null && eng.shouldBlock(q.qname)) {
                    Stats.recordBlocked()
                    write(DnsPacket.buildNxdomain(q, buf))
                    return
                }
            }
            val pkt = buf.copyOf(n) // buf is reused
            try {
                pool.execute { forwardAndReply(q, pkt) }
            } catch (_: RejectedExecutionException) { dropped.incrementAndGet() }
        }

        private fun forwardAndReply(q: DnsPacket.Query, pkt: ByteArray) {
            try {
                val reply = forward(q, pkt) ?: return
                write(reply)
            } catch (_: Exception) {
                // drop; client retries. Never log queried names.
            }
        }

        private fun forward(q: DnsPacket.Query, pkt: ByteArray): ByteArray? {
            val dns = pkt.copyOfRange(q.dnsOffset, q.dnsOffset + q.dnsLen)
            DnsPacket.clampEdns(dns, 0, dns.size, q.questionEnd, EDNS_MAX)
            DatagramSocket().use { s ->
                if (!protect(s)) return null
                val addr = upstream
                s.connect(addr, 53) // kernel drops datagrams from other sources
                s.send(DatagramPacket(dns, dns.size))
                val rbuf = ByteArray(DnsPacket.MAX_DNS)
                val dp = DatagramPacket(rbuf, rbuf.size)
                val deadline = System.nanoTime() + QUERY_DEADLINE_MS * 1_000_000
                while (!stopping) {
                    val left = (deadline - System.nanoTime()) / 1_000_000
                    if (left <= 0) return null
                    s.soTimeout = left.toInt()
                    dp.length = rbuf.size
                    try { s.receive(dp) } catch (_: SocketTimeoutException) { return null }
                    if (!DnsPacket.validateReply(q, pkt, rbuf, dp.length)) continue // junk: keep waiting
                    if (28 + dp.length > MTU) return DnsPacket.buildTruncated(q, pkt)
                    return DnsPacket.buildReplyPacket(q, rbuf, dp.length)
                }
                return null
            }
        }
    }

    private val slot = SessionSlot<Session>()
    private val engineRef = AtomicReference<Engine?>(null)
    @Volatile private var upstream: InetAddress = InetAddress.getByAddress(byteArrayOf(1, 1, 1, 1))

    // Engine loading: one dedicated thread, coalesced requests, stale generations discarded.
    private val loader = Executors.newSingleThreadExecutor()
    private val gen = AtomicInteger(0)
    private val dirty = AtomicBoolean(false)
    private val loading = AtomicBoolean(false)

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Prefs.init(applicationContext)
        Stats.load(applicationContext)
        when (intent?.action) {
            ACTION_STOP -> { stopVpn(); return START_NOT_STICKY }
            ACTION_RELOAD -> {
                if (running) { loadUpstream(); requestLoad() } else stopSelf()
                return START_NOT_STICKY
            }
        }
        startForeground(NOTIF_ID, buildNotification())
        if (!running) {
            if (!startVpn()) { stopVpn(); return START_NOT_STICKY }
        }
        return START_STICKY
    }

    private fun startVpn(): Boolean {
        // Detach the old session first so its late "ended" callback cannot stop this new one,
        // then wait for it to release its TUN. The join is bounded (2 s) and normally takes
        // <= 500 ms (the reader's poll timeout); it runs on the main thread, which is
        // acceptable for a user-initiated toggle and keeps start/stop strictly ordered.
        val old = slot.swap(null)
        old?.stop()
        try { old?.thread?.join(2000) } catch (_: InterruptedException) {}
        val fd = try {
            Builder()
                .setSession("ShieldVPN")
                .setMtu(MTU)
                .addAddress(TUN_ADDR, 32)
                .addRoute(FAKE_DNS, 32)
                .addDnsServer(FAKE_DNS)
                .setBlocking(true)
                .establish()
        } catch (e: Exception) { null } ?: return false
        gen.incrementAndGet()
        loadUpstream()
        filterState = FilterState.LOADING
        val s = Session(fd)
        slot.swap(s)
        running = true
        requestLoad()
        s.thread.start()
        return true
    }

    private fun loadUpstream() {
        val b = Net.parseUpstream(Prefs.upstream) ?: byteArrayOf(1, 1, 1, 1)
        upstream = InetAddress.getByAddress(b)
    }

    private fun requestLoad() {
        dirty.set(true)
        if (!loading.compareAndSet(false, true)) return // running loader will see `dirty`
        try {
            loader.execute {
                try {
                    while (dirty.getAndSet(false)) {
                        try { loadOnce() } catch (_: Throwable) {
                            if (engineRef.get() == null) filterState = FilterState.FAILED
                        }
                    }
                } finally { loading.set(false) }
                if (dirty.get()) requestLoad() // request raced with loader exit
            }
        } catch (_: RejectedExecutionException) { loading.set(false) }
    }

    private class Built(val engine: Engine?, val rules: Long)

    /** Byte arrays live only inside this function, so they are collectable before the swap. */
    private fun build(lm: ListManager): Built? {
        val l = lm.loadCombined() ?: return null
        val rules = lm.countRules(l.bytes)
        val eng = try { Engine.create(l.bytes) } catch (_: Throwable) { null }
        return Built(eng, rules)
    }

    private fun loadOnce() {
        val g = gen.get()
        val built = try { build(ListManager(filesDir.resolve("lists"))) } catch (_: Throwable) { Built(null, 0) }
        if (g != gen.get() || !running) { built?.engine?.close(); return } // stale
        if (built == null) { // no usable lists: really not filtering
            engineRef.getAndSet(null)?.close()
            Prefs.rulesLoaded = 0
            filterState = FilterState.FAILED
            return
        }
        val fresh = built.engine
        if (fresh == null) { // rebuild failed: keep the previous engine if there is one
            if (engineRef.get() == null) filterState = FilterState.FAILED
            return
        }
        engineRef.getAndSet(fresh)?.close() // Engine's own lock makes in-flight queries safe
        if (g != gen.get() || !running) engineRef.getAndSet(null)?.close()
        else { Prefs.rulesLoaded = built.rules; filterState = FilterState.READY }
    }

    private fun onSessionEnded(s: Session) {
        if (slot.isCurrent(s)) stopVpn() // only an unexpected TUN death (e.g. revoke)
    }

    private fun stopVpn() {
        running = false
        filterState = FilterState.OFF
        gen.incrementAndGet()
        slot.swap(null)?.stop() // its reader closes the TUN within ~500 ms
        engineRef.getAndSet(null)?.close()
        Stats.flush()
        stopForeground(true)
        stopSelf()
    }

    override fun onRevoke() { stopVpn() }

    override fun onDestroy() {
        if (running) stopVpn()
        loader.shutdownNow()
        super.onDestroy()
    }

    private fun buildNotification(): Notification {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL, "ShieldVPN", NotificationManager.IMPORTANCE_LOW)
        )
        val pi = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        return Notification.Builder(this, CHANNEL)
            .setContentTitle("ShieldVPN active")
            .setContentText("Blocking ads and trackers via DNS")
            .setSmallIcon(R.drawable.ic_stat)
            .setContentIntent(pi)
            .setOngoing(true)
            .build()
    }
}
