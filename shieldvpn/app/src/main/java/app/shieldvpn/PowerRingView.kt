package app.shieldvpn

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import android.view.animation.DecelerateInterpolator
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/** Gauge-style power control: tick ring that fills clockwise when active. */
class PowerRingView @JvmOverloads constructor(c: Context, a: AttributeSet? = null) : View(c, a) {
    private var progress = 0f
    private var anim: ValueAnimator? = null
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rect = RectF()
    private val cPrimary = c.getColor(R.color.sv_primary)
    private val cTrack = c.getColor(R.color.sv_outline_variant)
    private val cFillOff = c.getColor(R.color.sv_surface_container_high)
    private val cFillOn = c.getColor(R.color.sv_primary_container)
    private val cGlyphOff = c.getColor(R.color.sv_on_surface_variant)
    private val cGlyphOn = c.getColor(R.color.sv_on_primary_container)

    var active = false
        private set

    fun setActive(on: Boolean, animate: Boolean = true) {
        active = on
        anim?.cancel()
        anim = null
        val target = if (on) 1f else 0f
        if (!animate || progress == target) { progress = target; invalidate(); return }
        anim = ValueAnimator.ofFloat(progress, target).apply {
            duration = 700
            interpolator = DecelerateInterpolator()
            addUpdateListener { progress = it.animatedValue as Float; invalidate() }
            start()
        }
    }

    private fun mix(a: Int, b: Int, t: Float): Int {
        fun ch(s: Int) = (((a shr s) and 0xFF) * (1 - t) + ((b shr s) and 0xFF) * t).toInt()
        return (ch(24) shl 24) or (ch(16) shl 16) or (ch(8) shl 8) or ch(0)
    }

    override fun onDetachedFromWindow() { anim?.cancel(); super.onDetachedFromWindow() }

    override fun onDraw(canvas: Canvas) {
        val size = min(width, height).toFloat()
        val cx = width / 2f
        val cy = height / 2f
        val stroke = size * 0.05f
        val r = size / 2f - stroke
        paint.style = Paint.Style.STROKE
        paint.strokeCap = Paint.Cap.ROUND
        paint.strokeWidth = stroke

        // inner disc
        paint.style = Paint.Style.FILL
        paint.color = mix(cFillOff, cFillOn, progress)
        canvas.drawCircle(cx, cy, r - stroke * 2.6f, paint)

        // outer track and progress arc
        paint.style = Paint.Style.STROKE
        paint.color = cTrack
        canvas.drawCircle(cx, cy, r, paint)
        if (progress > 0f) {
            paint.color = cPrimary
            rect.set(cx - r, cy - r, cx + r, cy + r)
            canvas.drawArc(rect, -90f, 360f * progress, false, paint)
        }

        // gauge ticks between arc and disc
        val n = 60
        val tr1 = r - stroke * 1.4f
        val tr2 = r - stroke * 2.0f
        paint.strokeWidth = stroke * 0.28f
        for (i in 0 until n) {
            val frac = i / n.toFloat()
            val ang = Math.toRadians((frac * 360.0) - 90.0)
            paint.color = if (frac < progress) cPrimary else cTrack
            val x1 = cx + (tr1 * cos(ang)).toFloat(); val y1 = cy + (tr1 * sin(ang)).toFloat()
            val x2 = cx + (tr2 * cos(ang)).toFloat(); val y2 = cy + (tr2 * sin(ang)).toFloat()
            canvas.drawLine(x1, y1, x2, y2, paint)
        }

        // power glyph: open ring with a tick
        paint.color = mix(cGlyphOff, cGlyphOn, progress)
        paint.strokeWidth = stroke * 1.1f
        val g = r * 0.34f
        rect.set(cx - g, cy - g, cx + g, cy + g)
        canvas.drawArc(rect, -90f + 40f, 360f - 80f, false, paint)
        canvas.drawLine(cx, cy - g * 1.25f, cx, cy - g * 0.1f, paint)
    }
}
