package io.github.ksdfs1.weeklyroutine

import android.graphics.Color
import org.json.JSONObject
import java.util.Calendar

// Kotlin port of the routine logic in ../../index.html (activeCase, sortedBlocks,
// segmentsWithGaps, nowStatus, nowStatusName). Keep the two in step.

data class Category(val id: String, val name: String, val color: Int)
data class Block(val start: Int, val end: Int, val catId: String, val label: String)
data class RoutineCase(val id: String, val name: String, val blocks: List<Block>)
data class Day(val label: String, val short: String, val cases: List<RoutineCase>, val activeCaseId: String) {
    val activeCase: RoutineCase?
        get() = cases.firstOrNull { it.id == activeCaseId } ?: cases.firstOrNull()
    fun sortedBlocks(): List<Block> = activeCase?.blocks?.sortedBy { it.start } ?: emptyList()
}

/** a slice of the 24h ring; catId == null means "미지정" */
data class Segment(val start: Int, val end: Int, val catId: String?)

data class NowStatus(
    val cur: Block?,
    val cat: Category?,
    val next: Block?,
    val nextTomorrow: Boolean,
    val start: Int,
    val end: Int,
) {
    val name: String get() = if (cur != null) (cat?.name ?: "?") else "미지정 시간"
    /** identifies "what you're doing" — two back-to-back blocks with the same key are one state */
    val key: String get() = if (cur != null) "${cur.catId}|${cur.label}" else "gap"
}

class Routine(val categories: List<Category>, val days: List<Day>) {

    fun category(id: String?): Category? = categories.firstOrNull { it.id == id }

    /** cases picked on this device only ({dayIndex: caseId}); ids the day doesn't have are ignored */
    fun withCaseOverrides(overrides: Map<Int, String>): Routine {
        if (overrides.isEmpty()) return this
        return Routine(categories, days.mapIndexed { i, d ->
            val id = overrides[i]
            if (id != null && d.cases.any { it.id == id }) d.copy(activeCaseId = id) else d
        })
    }

    fun nowStatus(now: Calendar = Calendar.getInstance()): NowStatus {
        val nowMin = minuteOfDay(now)
        val ti = dayIndex(now)
        val blocks = days[ti].sortedBlocks()
        var cur: Block? = null
        var prevEnd = 0
        var next: Block? = null
        for (b in blocks) {
            if (cur == null && b.start <= nowMin && nowMin < b.end) cur = b
            if (b.end <= nowMin && b.end > prevEnd) prevEnd = b.end
            if (next == null && b.start > nowMin) next = b
        }
        var nextTomorrow = false
        if (next == null) {
            next = days[(ti + 1) % 7].sortedBlocks().firstOrNull()
            nextTomorrow = next != null
        }
        return if (cur != null) {
            NowStatus(cur, category(cur.catId), next, nextTomorrow, cur.start, cur.end)
        } else {
            NowStatus(null, null, next, nextTomorrow, prevEnd, if (next != null && !nextTomorrow) next.start else 1440)
        }
    }

    /** minute of today (1..1440) at which the status may change next; 1440 = midnight */
    fun nextBoundary(now: Calendar = Calendar.getInstance()): Int {
        val nowMin = minuteOfDay(now)
        var best = 1440
        for (b in days[dayIndex(now)].sortedBlocks()) {
            if (b.start in (nowMin + 1) until best) best = b.start
            if (b.end in (nowMin + 1) until best) best = b.end
        }
        return best
    }

    companion object {
        fun parse(json: String): Routine? = try {
            val o = JSONObject(json)
            val cats = o.getJSONArray("categories").let { a ->
                (0 until a.length()).map { i ->
                    val c = a.getJSONObject(i)
                    Category(c.getString("id"), c.optString("name", "?"), parseColor(c.optString("color")))
                }
            }
            val days = o.getJSONArray("days").let { a ->
                (0 until a.length()).map { i ->
                    val d = a.getJSONObject(i)
                    val cases = d.getJSONArray("cases").let { ca ->
                        (0 until ca.length()).map { j ->
                            val cs = ca.getJSONObject(j)
                            val blocks = cs.getJSONArray("blocks").let { ba ->
                                (0 until ba.length()).map { k ->
                                    val b = ba.getJSONObject(k)
                                    Block(b.getInt("start"), b.getInt("end"), b.optString("catId"), b.optString("label", ""))
                                }
                            }
                            RoutineCase(cs.getString("id"), cs.optString("name", ""), blocks)
                        }
                    }
                    Day(d.optString("label"), d.optString("short"), cases, d.optString("activeCaseId"))
                }
            }
            if (days.size == 7) Routine(cats, days) else null
        } catch (e: Exception) {
            null
        }

        private fun parseColor(s: String): Int = try { Color.parseColor(s) } catch (e: Exception) { UNASSIGNED }

        const val UNASSIGNED = 0xFF243354.toInt()

        /** Monday = 0 … Sunday = 6, same as the web app */
        fun dayIndex(c: Calendar): Int = (c.get(Calendar.DAY_OF_WEEK) + 5) % 7
        fun minuteOfDay(c: Calendar): Int = c.get(Calendar.HOUR_OF_DAY) * 60 + c.get(Calendar.MINUTE)

        fun segmentsWithGaps(blocks: List<Block>): List<Segment> {
            val segs = mutableListOf<Segment>()
            var cursor = 0
            for (b in blocks) {
                if (b.start > cursor) segs += Segment(cursor, b.start, null)
                segs += Segment(maxOf(b.start, cursor), b.end, b.catId)
                cursor = maxOf(cursor, b.end)
            }
            if (cursor < 1440) segs += Segment(cursor, 1440, null)
            return segs
        }

        fun fmtMin(min: Int): String {
            if (min >= 1440) return "24:00"
            return "%02d:%02d".format(min / 60, min % 60)
        }
    }
}
