package app.shieldvpn

import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.atomic.AtomicBoolean
import javax.net.ssl.HttpsURLConnection

class ListManager(private val dir: File) {
    class Source(val file: String, val title: String, val url: String)

    /** Concatenated enabled lists; [skipped] counts lists left out by the combined cap. */
    class Loaded(val bytes: ByteArray, val skipped: Int)

    /** Streaming scanner used to sanity check a download without buffering it. */
    class Scanner {
        val head = ByteArray(1024)
        var headLen = 0; private set
        var total = 0L; private set
        var rules = 0L; private set
        private var atLineStart = true
        private var counted = false   // current line was counted as a rule
        private var p1 = 0            // previous two chars of the current line
        private var p2 = 0

        fun feed(b: ByteArray, n: Int) {
            val take = minOf(n, head.size - headLen)
            if (take > 0) { System.arraycopy(b, 0, head, headLen, take); headLen += take }
            total += n
            for (i in 0 until n) {
                val c = b[i].toInt()
                if (atLineStart) {
                    atLineStart = false
                    p1 = 0; p2 = 0
                    // comments, headers, blanks and indented lines are not rules
                    counted = !(c == '!'.code || c == '['.code || c == '\n'.code || c == '\r'.code ||
                        c == ' '.code || c == '\t'.code)
                    if (counted) rules++
                }
                if (counted && c == '#'.code &&
                    (p1 == '#'.code || (p1 == '@'.code && p2 == '#'.code) || (p1 == '?'.code && p2 == '#'.code))) {
                    counted = false; rules-- // cosmetic rule (## #@# #?#): not a network rule
                }
                p2 = p1; p1 = c
                if (c == '\n'.code) atLineStart = true
            }
        }
    }

    companion object {
        const val MAX_BYTES = 20L * 1024 * 1024
        /** Must match MAX_RULES_BYTES in core/src/lib.rs. */
        const val COMBINED_CAP = 32L * 1024 * 1024
        const val MIN_BYTES = 2048L
        const val MIN_RULES = 100L
        private const val MAX_REDIRECTS = 3
        private const val DEADLINE_NS = 60_000_000_000L
        private val busy = AtomicBoolean(false)

        val SOURCES = listOf(
            Source("easylist.txt", "EasyList", "https://easylist.to/easylist/easylist.txt"),
            Source("easyprivacy.txt", "EasyPrivacy", "https://easylist.to/easylist/easyprivacy.txt"),
            Source("ublock-filters.txt", "uBlock filters", "https://ublockorigin.github.io/uAssets/filters/filters.txt"),
            Source("peterlowe.txt", "Peter Lowe's list", "https://pgl.yoyo.org/adservers/serverlist.php?hostformat=adblockplus&showintro=0&mimetype=plaintext"),
        )

        /**
         * Exactly the download hosts of SOURCES (plus their subdomains, matched on label
         * boundaries), so a list update cannot lock itself out. Deliberately not broad GitHub
         * domains: those would let anything hosted there bypass blocking.
         */
        private val EXEMPT: Set<String> by lazy {
            val h = HashSet<String>()
            for (s in SOURCES) h.add(URL(s.url).host.lowercase())
            h
        }

        fun isListHost(name: String): Boolean {
            var n = name.lowercase().trimEnd('.')
            while (true) {
                if (n in EXEMPT) return true
                val dot = n.indexOf('.')
                if (dot < 0) return false
                n = n.substring(dot + 1)
            }
        }

        /** Indices (in order) of lists that fit under [cap], counting one separator byte each. */
        fun fitWithinCap(sizes: List<Long>, cap: Long = COMBINED_CAP): List<Int> {
            val out = ArrayList<Int>()
            var sum = 0L
            for ((i, s) in sizes.withIndex()) {
                if (s < 0) continue
                if (sum + s + 1 <= cap) { out.add(i); sum += s + 1 }
            }
            return out
        }

        /** True if the scanned body looks like an Adblock-syntax list rather than an error page. */
        fun looksLikeList(sc: Scanner): Boolean {
            if (sc.total < MIN_BYTES || sc.rules < MIN_RULES) return false
            val head = String(sc.head, 0, sc.headLen, Charsets.ISO_8859_1).trimStart('﻿', ' ', '\t', '\r', '\n')
            val lower = head.lowercase()
            if (lower.startsWith("<!doctype") || lower.startsWith("<html") || lower.contains("<html") || lower.contains("<head")) return false
            return head.lineSequence().any {
                val t = it.trim()
                t.startsWith("!") || t.startsWith("[adblock", ignoreCase = true)
            }
        }
    }

    init { dir.mkdirs() }

    /** Downloads every list; returns the number that succeeded, or -1 if an update is already running. */
    fun updateAll(progress: ((done: Int, total: Int, current: String) -> Unit)? = null): Int {
        if (!busy.compareAndSet(false, true)) return -1
        try {
            dir.listFiles { f -> f.name.endsWith(".tmp") }?.forEach { it.delete() }
            var ok = 0
            SOURCES.forEachIndexed { i, src ->
                progress?.invoke(i, SOURCES.size, src.title)
                if (runCatching { download(src) }.isSuccess) ok++
            }
            progress?.invoke(SOURCES.size, SOURCES.size, "")
            return ok
        } finally {
            busy.set(false)
        }
    }

    fun file(src: Source) = File(dir, src.file)
    fun sizeOf(src: Source): Long = file(src).let { if (it.isFile) it.length() else -1L }
    fun updatedAt(src: Source): Long = file(src).let { if (it.isFile) it.lastModified() else 0L }

    fun lastUpdated(): Long = SOURCES.maxOfOrNull { updatedAt(it) } ?: 0L

    /** Concatenation of enabled, cached lists (<= COMBINED_CAP), or null if there are none. */
    fun loadCombined(): Loaded? {
        // Snapshot every length once; never read past it, so a concurrent rename can't break us.
        val cand = SOURCES.filter { Prefs.listEnabled(it.file) }.map { File(dir, it.file) }
        val files = ArrayList<File>(); val lens = ArrayList<Long>()
        for (f in cand) {
            val l = f.length() // 0 if missing
            if (l in 1..MAX_BYTES) { files.add(f); lens.add(l) }
        }
        if (files.isEmpty()) return null
        val keep = fitWithinCap(lens)
        if (keep.isEmpty()) return null
        val total = keep.sumOf { lens[it] + 1 }.toInt()
        val out = ByteArray(total) // single allocation, no builder copy
        var pos = 0
        for (i in keep) {
            val end = pos + lens[i].toInt()
            try {
                FileInputStream(files[i]).use { ins ->
                    while (pos < end) {
                        val n = ins.read(out, pos, end - pos)
                        if (n < 0) break // file shrank; keep what we read
                        pos += n
                    }
                }
            } catch (_: java.io.IOException) { /* file vanished: keep what we read */ }
            if (pos < total) out[pos++] = '\n'.code.toByte()
        }
        return Loaded(if (pos == total) out else out.copyOf(pos), files.size - keep.size)
    }

    /** Counts network rules: skips comments, blanks, indented lines and cosmetic (##, #@#, #?#) rules. */
    fun countRules(b: ByteArray): Long {
        val sc = Scanner()
        sc.feed(b, b.size)
        return sc.rules
    }

    private fun download(src: Source) {
        val deadline = System.nanoTime() + DEADLINE_NS
        var url = URL(src.url)
        var redirects = 0
        while (true) {
            require(url.protocol == "https") { "non-https" }
            if (System.nanoTime() > deadline) error("deadline")
            val c = url.openConnection() as HttpsURLConnection
            c.instanceFollowRedirects = false
            c.connectTimeout = 15_000
            c.readTimeout = 15_000
            c.requestMethod = "GET"
            try {
                val code = c.responseCode
                if (code in 301..308 && code != 304) {
                    if (++redirects > MAX_REDIRECTS) error("too many redirects")
                    val loc = c.getHeaderField("Location") ?: error("no location")
                    url = URL(url, loc) // re-checked as https at loop top
                    continue
                }
                if (code != HttpURLConnection.HTTP_OK) error("http $code")
                if (c.contentLengthLong > MAX_BYTES) error("too large")
                val tmp = File.createTempFile(src.file + ".", ".tmp", dir)
                try {
                    val sc = Scanner()
                    c.inputStream.use { ins ->
                        FileOutputStream(tmp).use { out ->
                            val buf = ByteArray(16 * 1024)
                            while (true) {
                                if (System.nanoTime() > deadline) error("deadline")
                                val n = ins.read(buf)
                                if (n < 0) break
                                sc.feed(buf, n)
                                if (sc.total > MAX_BYTES) error("too large")
                                out.write(buf, 0, n)
                            }
                            out.fd.sync()
                        }
                    }
                    if (!looksLikeList(sc)) error("not a filter list")
                    if (!tmp.renameTo(File(dir, src.file))) error("rename failed")
                } finally {
                    if (tmp.exists()) tmp.delete() // old list stays on any failure
                }
                return
            } finally {
                c.disconnect()
            }
        }
    }
}

/** Process-wide list updater: state lives here, so no Activity is captured by the worker. */
object Updater {
    private val exec = java.util.concurrent.Executors.newSingleThreadExecutor()
    @Volatile var running = false; private set
    @Volatile var done = 0; private set
    @Volatile var total = ListManager.SOURCES.size; private set
    @Volatile var current = ""; private set
    /** Result of the last finished run: number of lists updated, -1 = none yet or another update was already running. */
    @Volatile var lastOk = -1; private set

    fun start(ctx: android.content.Context) {
        if (running) return
        running = true
        done = 0
        val app = ctx.applicationContext
        exec.execute {
            try {
                val lm = ListManager(app.filesDir.resolve("lists"))
                lastOk = lm.updateAll { d, t, cur -> done = d; total = t; current = cur }
                if (lastOk != 0 && ShieldVpnService.running) {
                    app.startService(
                        android.content.Intent(app, ShieldVpnService::class.java).setAction(ShieldVpnService.ACTION_RELOAD)
                    )
                }
            } catch (_: Throwable) {
                lastOk = 0
            } finally {
                running = false
            }
        }
    }
}
