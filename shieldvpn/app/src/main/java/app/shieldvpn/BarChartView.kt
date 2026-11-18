package app.shieldvpn

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View

/** 24 hourly bars, oldest left, newest right. Counts only. */
class BarChartView @JvmOverloads constructor(c: Context, a: AttributeSet? = null) : View(c, a) {
    private var values = LongArray(24)
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rect = RectF()
    private val dp = c.resources.displayMetrics.density
    private val cBar = c.getColor(R.color.sv_primary)
    private val cPeak = c.getColor(R.color.sv_tertiary)
    private val cBase = c.getColor(R.color.sv_outline_variant)
    private val cText = c.getColor(R.color.sv_on_surface_variant)

    fun setValues(v: LongArray) { values = v; invalidate() }

    override fun onDraw(canvas: Canvas) {
        val labelH = 20 * dp
        val chartH = height - labelH
        val max = (values.maxOrNull() ?: 0L).coerceAtLeast(1L)
        val n = values.size
        val slot = width.toFloat() / n
        val bw = slot * 0.62f
        paint.style = Paint.Style.FILL
        var peakIdx = -1
        if ((values.maxOrNull() ?: 0L) > 0) peakIdx = values.indexOfFirst { it == max }
        for (i in 0 until n) {
            val h = if (values[i] == 0L) 2 * dp else (chartH - 4 * dp) * values[i] / max
            val l = i * slot + (slot - bw) / 2
            rect.set(l, chartH - h, l + bw, chartH)
            paint.color = when { values[i] == 0L -> cBase; i == peakIdx -> cPeak; else -> cBar }
            canvas.drawRoundRect(rect, 3 * dp, 3 * dp, paint)
        }
        paint.color = cBase
        canvas.drawRect(0f, chartH + 1 * dp, width.toFloat(), chartH + 2 * dp, paint)
        paint.color = cText
        paint.textSize = 11 * dp
        paint.textAlign = Paint.Align.LEFT
        canvas.drawText("24h ago", 0f, height - 3 * dp, paint)
        paint.textAlign = Paint.Align.CENTER
        canvas.drawText("12h", width / 2f, height - 3 * dp, paint)
        paint.textAlign = Paint.Align.RIGHT
        canvas.drawText("now", width.toFloat(), height - 3 * dp, paint)
    }
}
