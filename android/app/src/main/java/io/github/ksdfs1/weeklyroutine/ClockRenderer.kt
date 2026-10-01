package io.github.ksdfs1.weeklyroutine

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import java.util.Calendar

/** A day's 24h donut for the widget — a small version of renderClockSVG in index.html. */
object ClockRenderer {
    private const val GAP_RING = 0xFF0A0F1E.toInt()
    private const val TICK = 0xFF6D7897.toInt()
    private const val ACCENT = 0xFFF5B95C.toInt()
    private const val TEXT = 0xFFEEF1FB.toInt()
    private const val TEXT_MUTE = 0xFFA7B2CF.toInt()
    private const val DIM_ALPHA = 80   // the other slices while one is highlighted

    /**
     * [showNow]: today — draw the now hand and what's on now in the middle; other days show the day and case.
     * [selStart]: start minute of a block tapped in the list — its slice pops out, the rest dim, and the
     * middle shows its name, time and memo instead (like clicking a slice in the web app).
     */
    fun render(routine: Routine, day: Day, st: NowStatus, sizePx: Int, showNow: Boolean = true, selStart: Int? = null): Bitmap {
        val bmp = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val s = sizePx.toFloat()
        val cx = s / 2f
        val rOuter = s * 0.44f
        val rInner = s * 0.29f
        val outer = RectF(cx - rOuter, cx - rOuter, cx + rOuter, cx + rOuter)
        val inner = RectF(cx - rInner, cx - rInner, cx + rInner, cx + rInner)

        val blocks = day.sortedBlocks()
        val segs = Routine.segmentsWithGaps(blocks).filter { it.end > it.start }
        val sel = selStart?.let { m -> segs.firstOrNull { it.start == m } ?: segs.firstOrNull { m >= it.start && m < it.end } }

        val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
        val edge = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; color = GAP_RING; strokeWidth = s * 0.008f }
        fun slice(seg: Segment): Path {
            val start = seg.start / 1440f * 360f - 90f
            // arcTo draws nothing for a full 360°, so a whole-day segment stops just short of it
            val sweep = minOf((seg.end - seg.start) / 1440f * 360f, 359.9f)
            return Path().apply {
                arcTo(outer, start, sweep, true)
                arcTo(inner, start + sweep, -sweep)
                close()
            }
        }
        for (seg in segs) {
            if (seg == sel) continue
            val path = slice(seg)
            fill.color = routine.category(seg.catId)?.color ?: Routine.UNASSIGNED
            if (sel != null) fill.alpha = DIM_ALPHA
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

        // the highlighted slice: a little bigger (about the centre), full colour, with a soft shadow
        if (sel != null) {
            val path = slice(sel)
            c.save()
            c.scale(1.07f, 1.07f, cx, cx)
            val pop = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.FILL
                color = routine.category(sel.catId)?.color ?: Routine.UNASSIGNED
                setShadowLayer(s * 0.03f, 0f, 0f, 0xAA000000.toInt())
            }
            c.drawPath(path, pop)
            c.drawPath(path, edge)
            c.restore()
        }

        val now = Calendar.getInstance()
        if (showNow) {
            // now hand, from the inner ring outwards (keeps the centre text clear)
            val a = Math.toRadians(Routine.minuteOfDay(now) / 1440.0 * 360.0 - 90.0)
            val hand = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = ACCENT; strokeWidth = s * 0.018f; strokeCap = Paint.Cap.ROUND }
            val ri = rInner - s * 0.02f
            val ro = rOuter + s * 0.02f
            c.drawLine(cx + ri * cos(a), cx + ri * sin(a), cx + ro * cos(a), cx + ro * sin(a), hand)
            c.drawCircle(cx + ri * cos(a), cx + ri * sin(a), s * 0.022f, Paint(hand).apply { style = Paint.Style.FILL })
        }

        when {
            sel != null -> {
                val cat = routine.category(sel.catId)
                val name = if (sel.catId == null) "미지정" else cat?.name ?: "?"
                val block = blocks.firstOrNull { it.start == sel.start } ?: blocks.firstOrNull { it.start <= sel.start && sel.start < it.end }
                val memo = block?.label?.takeIf { sel.catId != null && it.isNotBlank() && it != name } ?: ""
                drawCentre(c, s, cx, short(name, 6), cat?.color ?: TEXT,
                    Routine.fmtMin(sel.start) + "–" + Routine.fmtMin(sel.end), short(memo, 8))
            }
            showNow -> {
                // what's on now, and how long it has left
                val remaining = st.remaining(Routine.minuteOfDay(now)).coerceAtLeast(0)
                drawCentre(c, s, cx, short(st.name, 6), st.cat?.color ?: TEXT, Routine.fmtDur(remaining) + " 남음")
            }
            else -> drawCentre(c, s, cx, day.label, TEXT, short(day.activeCase?.name ?: "", 8))
        }
        return bmp
    }

    private fun short(t: String, max: Int) = if (t.length > max) t.take(max - 1) + "…" else t

    private fun drawCentre(c: Canvas, s: Float, cx: Float, title: String, titleColor: Int, sub: String, sub2: String = "") {
        // with a third line the block moves up a little so it stays centred
        val lift = if (sub2.isEmpty()) 0f else s * 0.035f
        val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = titleColor
            textSize = s * 0.10f
            textAlign = Paint.Align.CENTER
            typeface = Typeface.DEFAULT_BOLD
        }
        c.drawText(title, cx, cx - s * 0.005f - lift, text)
        val small = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = TEXT_MUTE
            textSize = s * 0.058f
            textAlign = Paint.Align.CENTER
        }
        c.drawText(sub, cx, cx + s * 0.075f - lift, small)
        if (sub2.isNotEmpty()) c.drawText(sub2, cx, cx + s * 0.145f - lift, small.apply { color = TICK; textSize = s * 0.052f })
    }

    private fun cos(a: Double) = kotlin.math.cos(a).toFloat()
    private fun sin(a: Double) = kotlin.math.sin(a).toFloat()
}
