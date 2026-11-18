package app.shieldvpn

import android.content.Context
import android.content.SharedPreferences

/** Pure helpers, JVM-testable. */
object Net {
    /** Parses a dotted-quad IPv4 literal. Returns null if invalid or unusable as an upstream resolver. */
    fun parseUpstream(s: String): ByteArray? {
        val parts = s.trim().split('.')
        if (parts.size != 4) return null
        val b = ByteArray(4)
        for (i in 0..3) {
            val p = parts[i]
            if (p.isEmpty() || p.length > 3 || !p.all { it in '0'..'9' }) return null
            val v = p.toInt()
            if (v > 255) return null
            b[i] = v.toByte()
        }
        val a = b[0].toInt() and 0xFF
        if (a == 0 || a == 127 || a >= 224) return null // unspecified, loopback, multicast/broadcast
        if (a == 10 && (b[1].toInt() and 0xFF) == 111 && (b[2].toInt() and 0xFF) == 222) return null // our TUN
        return b
    }
}

object Prefs {
    const val DEFAULT_UPSTREAM = "1.1.1.1"
    private lateinit var sp: SharedPreferences

    fun init(ctx: Context) {
        if (!::sp.isInitialized) sp = ctx.applicationContext.getSharedPreferences("prefs", Context.MODE_PRIVATE)
    }

    var enabled: Boolean
        get() = sp.getBoolean("enabled", false)
        set(v) { sp.edit().putBoolean("enabled", v).apply() }

    var upstream: String
        get() = sp.getString("upstream", DEFAULT_UPSTREAM)?.takeIf { Net.parseUpstream(it) != null } ?: DEFAULT_UPSTREAM
        set(v) { sp.edit().putString("upstream", v).apply() }

    var rulesLoaded: Long
        get() = sp.getLong("rules", 0)
        set(v) { sp.edit().putLong("rules", v).apply() }

    fun listEnabled(file: String) = sp.getBoolean("list_$file", true)
    fun setListEnabled(file: String, on: Boolean) { sp.edit().putBoolean("list_$file", on).apply() }
}
