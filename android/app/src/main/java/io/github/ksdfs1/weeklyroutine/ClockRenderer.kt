package io.github.ksdfs1.weeklyroutine

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import java.util.Calendar

/** Today's 24h donut for the widget — a small version of renderClockSVG in index.html. */
object ClockRenderer {
    private const val GAP_RING = 0xFF0A0F1E.toInt()
    private const val TICK = 0xFF6D7897.toInt()
    private const val ACCENT = 0xFFF5B95C.toInt()

    fun render(routine: Routine, day: Day, st: NowStatus, sizePx: Int): Bitmap {
        val bmp = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val s = sizePx.toFloat()
        val cx = s / 2f
        val rOuter = s * 0.46f
        val rInner = s * 0.30f
        val outer = RectF(cx - rOuter, cx - rOuter, cx + rOuter, cx + rOuter)
        val inner = RectF(cx - rInner, cx - rInner, cx + rInner, cx + rInner)

        val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
        val edge = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; color = GAP_RING; strokeWidth = s * 0.008f }
        for (seg in Routine.segmentsWithGaps(day.sortedBlocks())) {
            if (seg.end <= seg.start) continue
            val start = seg.start / 1440f * 360f - 90f
            // arcTo draws nothing for a full 360°, so a whole-day segment stops just short of it
            val sweep = minOf((seg.end - seg.start) / 1440f * 360f, 359.9f)
            val path = Path().apply {
                arcTo(outer, start, sweep, true)
                arcTo(inner, start + sweep, -sweep)
                close()
            }
            fill.color = routine.category(seg.catId)?.color ?: Routine.UNASSIGNED
            c.drawPath(path, fill)
            c.drawPath(path, edge)
        }

        // 3-hour ticks
        val tick = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = TICK; strokeWidth = s * 0.012f; strokeCap = Paint.Cap.ROUND }
        for (h in 0 until 24 step 3) {
            val a = Math.toRadians(h * 15.0 - 90.0)
            val r0 = rOuter + s * 0.005f
            val r1 = rOuter + s * 0.03f
            c.drawLine(cx + r0 * cos(a), cx + r0 * sin(a), cx + r1 * cos(a), cx + r1 * sin(a), tick)
        }

        // now hand, from the inner ring outwards (keeps the centre text clear)
        val now = Calendar.getInstance()
        val a = Math.toRadians(Routine.minuteOfDay(now) / 1440.0 * 360.0 - 90.0)
        val hand = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = ACCENT; strokeWidth = s * 0.018f; strokeCap = Paint.Cap.ROUND }
        val ri = rInner - s * 0.02f
        val ro = rOuter + s * 0.02f
        c.drawLine(cx + ri * cos(a), cx + ri * sin(a), cx + ro * cos(a), cx + ro * sin(a), hand)
        c.drawCircle(cx + ri * cos(a), cx + ri * sin(a), s * 0.022f, Paint(hand).apply { style = Paint.Style.FILL })

        // centre: what's on now
        val name = st.name.let { if (it.length > 6) it.take(5) + "…" else it }
        val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = st.cat?.color ?: 0xFFEEF1FB.toInt()
            textSize = s * 0.10f
            textAlign = Paint.Align.CENTER
            typeface = Typeface.DEFAULT_BOLD
        }
        c.drawText(name, cx, cx + text.textSize * 0.35f, text)
        return bmp
    }

    private fun cos(a: Double) = kotlin.math.cos(a).toFloat()
    private fun sin(a: Double) = kotlin.math.sin(a).toFloat()
}
