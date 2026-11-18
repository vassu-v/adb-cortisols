package app.shieldvpn

import android.content.Context
import android.content.SharedPreferences
import java.util.Calendar

/**
 * Counters only (never domains). State is bounded: 24 hourly buckets, a day
 * counter and a total, persisted as a few numbers in SharedPreferences.
 */
object Stats {
    private const val HOURS = 24
    private const val FLUSH_MS = 15_000L
    private val count = LongArray(HOURS)
    private val hour = LongArray(HOURS) { -1 }
    private var total = 0L
    private var today = 0L
    private var dayKey = 0
    private var lastFlush = 0L
    private var sp: SharedPreferences? = null

    class Snapshot(val perHour: LongArray, val today: Long, val total: Long)

    @Synchronized fun load(ctx: Context) {
        if (sp != null) return
        val p = ctx.applicationContext.getSharedPreferences("stats", Context.MODE_PRIVATE)
        sp = p
        total = p.getLong("total", 0).coerceAtLeast(0)
        today = p.getLong("today", 0).coerceAtLeast(0)
        dayKey = p.getInt("day", 0)
        val parts = (p.getString("b", "") ?: "").split(';')
        if (parts.size == HOURS) {
            for (i in 0 until HOURS) {
                val kv = parts[i].split(':')
                if (kv.size == 2) {
                    hour[i] = kv[0].toLongOrNull() ?: -1
                    count[i] = (kv[1].toLongOrNull() ?: 0).coerceAtLeast(0)
                }
            }
        }
    }

    private fun nowHour() = System.currentTimeMillis() / 3_600_000L

    private fun todayKey(): Int {
        val c = Calendar.getInstance()
        return c.get(Calendar.YEAR) * 1000 + c.get(Calendar.DAY_OF_YEAR)
    }

    private fun rollDay() {
        val k = todayKey()
        if (k != dayKey) { dayKey = k; today = 0 }
    }

    @Synchronized fun recordBlocked() {
        val h = nowHour()
        val i = (h % HOURS).toInt()
        if (hour[i] != h) { hour[i] = h; count[i] = 0 }
        count[i]++
        total++
        rollDay()
        today++
        val t = System.currentTimeMillis()
        if (t - lastFlush > FLUSH_MS) flushLocked(t)
    }

    /** Buckets ordered oldest to newest; the last element is the current hour. */
    @Synchronized fun snapshot(): Snapshot {
        rollDay()
        val h = nowHour()
        val out = LongArray(HOURS)
        for (k in 0 until HOURS) {
            val hh = h - (HOURS - 1 - k)
            val i = (hh % HOURS).toInt()
            out[k] = if (hour[i] == hh) count[i] else 0
        }
        return Snapshot(out, today, total)
    }

    @Synchronized fun flush() = flushLocked(System.currentTimeMillis())

    private fun flushLocked(t: Long) {
        lastFlush = t
        val sb = StringBuilder()
        for (i in 0 until HOURS) { if (i > 0) sb.append(';'); sb.append(hour[i]).append(':').append(count[i]) }
        sp?.edit()?.putLong("total", total)?.putLong("today", today)?.putInt("day", dayKey)
            ?.putString("b", sb.toString())?.apply()
    }

    @Synchronized fun reset() {
        count.fill(0); hour.fill(-1); total = 0; today = 0
        flushLocked(System.currentTimeMillis())
    }
}
