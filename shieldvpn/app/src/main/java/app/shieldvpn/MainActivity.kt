package app.shieldvpn

import android.content.Intent
import android.net.VpnService
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.text.format.DateUtils
import android.text.format.Formatter
import android.view.LayoutInflater
import android.view.View
import android.widget.EditText
import android.widget.ProgressBar
import android.widget.RadioButton
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SwitchCompat
import com.google.android.material.bottomnavigation.BottomNavigationView
import java.text.NumberFormat
import java.util.Calendar

class MainActivity : AppCompatActivity() {
    private val REQ_VPN = 1
    private val ui = Handler(Looper.getMainLooper())
    private lateinit var lm: ListManager
    private lateinit var pages: List<View>
    private lateinit var power: PowerRingView
    private val rowDetail = HashMap<String, TextView>()

    private val tick = object : Runnable {
        override fun run() { refresh(); ui.postDelayed(this, 1000) }
    }

    override fun onCreate(s: Bundle?) {
        super.onCreate(s)
        Prefs.init(this)
        Stats.load(this)
        lm = ListManager(filesDir.resolve("lists"))
        setContentView(R.layout.activity_main)

        pages = listOf(R.id.page_home, R.id.page_lists, R.id.page_activity, R.id.page_settings)
            .map { findViewById<View>(it) }
        val nav = findViewById<BottomNavigationView>(R.id.nav)
        nav.setOnNavigationItemSelectedListener { item ->
            val idx = when (item.itemId) {
                R.id.nav_home -> 0
                R.id.nav_lists -> 1
                R.id.nav_activity -> 2
                else -> 3
            }
            pages.forEachIndexed { i, v -> v.visibility = if (i == idx) View.VISIBLE else View.GONE }
            refresh()
            true
        }

        power = findViewById(R.id.power)
        power.setActive(ShieldVpnService.running, false)
        power.setOnClickListener { onToggle() }

        buildListRows()
        findViewById<View>(R.id.btn_update).setOnClickListener { updateLists() }
        findViewById<View>(R.id.btn_reset).setOnClickListener { Stats.reset(); refresh() }
        setupSettings()
    }

    override fun onResume() { super.onResume(); updatePrivateDnsNote(); tick.run() }
    override fun onPause() { ui.removeCallbacks(tick); Stats.flush(); super.onPause() }

    // ---- toggle ----
    private fun onToggle() {
        if (ShieldVpnService.running) {
            Prefs.enabled = false
            startService(Intent(this, ShieldVpnService::class.java).setAction(ShieldVpnService.ACTION_STOP))
            power.setActive(false)
        } else {
            val i = VpnService.prepare(this)
            if (i != null) startActivityForResult(i, REQ_VPN) else startVpn()
        }
    }

    override fun onActivityResult(req: Int, res: Int, data: Intent?) {
        if (req == REQ_VPN && res == RESULT_OK) startVpn()
    }

    private fun startVpn() {
        Prefs.enabled = true
        startForegroundService(Intent(this, ShieldVpnService::class.java).setAction(ShieldVpnService.ACTION_START))
    }

    private fun reloadService() {
        if (ShieldVpnService.running) {
            startService(Intent(this, ShieldVpnService::class.java).setAction(ShieldVpnService.ACTION_RELOAD))
        }
    }

    // ---- lists ----
    private fun buildListRows() {
        val box = findViewById<android.widget.LinearLayout>(R.id.list_container)
        val inf = LayoutInflater.from(this)
        ListManager.SOURCES.forEachIndexed { i, src ->
            if (i > 0) {
                val d = View(this)
                d.setBackgroundColor(getColor(R.color.sv_outline_variant))
                box.addView(d, android.widget.LinearLayout.LayoutParams(-1, 1))
            }
            val row = inf.inflate(R.layout.item_list, box, false)
            row.findViewById<TextView>(R.id.item_title).text = src.title
            rowDetail[src.file] = row.findViewById(R.id.item_detail)
            val sw = row.findViewById<SwitchCompat>(R.id.item_switch)
            sw.isChecked = Prefs.listEnabled(src.file)
            sw.setOnCheckedChangeListener { _, on -> Prefs.setListEnabled(src.file, on); reloadService() }
            box.addView(row)
        }
    }

    private fun updateLists() {
        Updater.start(this) // process-wide worker; this Activity is not captured
        refresh()
    }

    // ---- settings ----
    private fun setupSettings() {
        val cf = findViewById<RadioButton>(R.id.rb_cf)
        val q9 = findViewById<RadioButton>(R.id.rb_q9)
        val custom = findViewById<RadioButton>(R.id.rb_custom)
        val et = findViewById<EditText>(R.id.et_custom)
        when (val u = Prefs.upstream) {
            "1.1.1.1" -> cf.isChecked = true
            "9.9.9.9" -> q9.isChecked = true
            else -> { custom.isChecked = true; et.setText(u) }
        }
        et.isEnabled = custom.isChecked
        findViewById<android.widget.RadioGroup>(R.id.dns_group).setOnCheckedChangeListener { _, id ->
            et.isEnabled = id == R.id.rb_custom
            if (id != R.id.rb_custom) et.error = null
        }
        findViewById<View>(R.id.btn_save).setOnClickListener {
            val ip = when {
                cf.isChecked -> "1.1.1.1"
                q9.isChecked -> "9.9.9.9"
                else -> et.text.toString().trim()
            }
            if (Net.parseUpstream(ip) == null) {
                et.error = "Enter a valid IPv4 address (not 0.x, 127.x or 224 and up)"
                return@setOnClickListener
            }
            Prefs.upstream = ip
            reloadService()
            Toast.makeText(this, "Upstream DNS set to $ip", Toast.LENGTH_SHORT).show()
        }
    }

    private fun updatePrivateDnsNote() {
        val mode = try { Settings.Global.getString(contentResolver, "private_dns_mode") } catch (_: Exception) { null }
        findViewById<TextView>(R.id.tv_private_dns).text = when (mode) {
            "hostname" -> "On, with a provider hostname. Android sends DNS straight to that provider and bypasses ShieldVPN, so nothing is blocked. Set Private DNS to Off or Automatic in system Network settings."
            "off" -> "Off. ShieldVPN receives all DNS queries from this device."
            else -> "Automatic. ShieldVPN's resolver does not support encrypted DNS, so Android falls back to plain DNS and filtering works."
        }
    }

    // ---- rendering ----
    private fun refresh() {
        val on = ShieldVpnService.running
        val state = ShieldVpnService.filterState
        val ready = on && state == ShieldVpnService.FilterState.READY
        if (power.active != ready) power.setActive(ready)
        val nf = NumberFormat.getInstance()
        val snap = Stats.snapshot()

        val (status, sub) = when {
            !on -> "Off" to "Tap the ring to start filtering"
            state == ShieldVpnService.FilterState.READY -> "Protected" to "Filtering DNS, forwarding to ${Prefs.upstream}"
            state == ShieldVpnService.FilterState.FAILED -> "Not filtering" to
                "Filters failed to load, so DNS is forwarded unfiltered. Download or enable lists in the Lists tab."
            else -> "Starting" to "Loading filter lists..."
        }
        findViewById<TextView>(R.id.home_status).text = status
        findViewById<TextView>(R.id.home_sub).text = sub
        val bar = findViewById<ProgressBar>(R.id.list_progress)
        val txt = findViewById<TextView>(R.id.list_progress_text)
        val btn = findViewById<View>(R.id.btn_update)
        if (Updater.running) {
            bar.max = Updater.total; bar.progress = Updater.done
            bar.visibility = View.VISIBLE; txt.visibility = View.VISIBLE
            txt.text = "Downloading ${Updater.current}"
            btn.isEnabled = false
        } else {
            bar.visibility = View.GONE; btn.isEnabled = true
            if (Updater.lastOk >= 0) {
                txt.visibility = View.VISIBLE
                txt.text = "Updated ${Updater.lastOk} of ${ListManager.SOURCES.size} lists"
            } else txt.visibility = View.GONE
        }
        findViewById<TextView>(R.id.stat_today).text = nf.format(snap.today)
        findViewById<TextView>(R.id.stat_total).text = nf.format(snap.total)
        findViewById<TextView>(R.id.stat_rules).text = nf.format(Prefs.rulesLoaded)
        val last = lm.lastUpdated()
        findViewById<TextView>(R.id.stat_updated).text =
            if (last == 0L) "Never" else DateUtils.getRelativeTimeSpanString(last, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS).toString()

        for (src in ListManager.SOURCES) {
            val size = lm.sizeOf(src)
            rowDetail[src.file]?.text = if (size < 0) "Not downloaded"
            else Formatter.formatShortFileSize(this, size) + "  |  updated " +
                DateUtils.getRelativeTimeSpanString(lm.updatedAt(src), System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS)
        }

        findViewById<BarChartView>(R.id.chart).setValues(snap.perHour)
        findViewById<TextView>(R.id.act_24h).text = nf.format(snap.perHour.sum())
        findViewById<TextView>(R.id.act_total).text = nf.format(snap.total)
        val max = snap.perHour.maxOrNull() ?: 0L
        findViewById<TextView>(R.id.act_peak).text = if (max == 0L) "No blocks yet" else {
            val idx = snap.perHour.indexOfLast { it == max }
            val c = Calendar.getInstance()
            c.add(Calendar.HOUR_OF_DAY, -(23 - idx))
            "%02d:00, %s blocked".format(c.get(Calendar.HOUR_OF_DAY), nf.format(max))
        }
    }
}
