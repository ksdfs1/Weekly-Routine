package io.github.ksdfs1.weeklyroutine

import android.app.AlarmManager
import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.view.View
import android.widget.RemoteViews
import android.widget.RemoteViewsService
import java.util.Calendar

/**
 * 4x2 home-screen widget: the time and a day's donut, the "지금" line and that day's blocks.
 * Shows today; ◀ ▶ and the 월~일 strip show another day for a couple of minutes (ViewDay).
 * Tapping a block in the list highlights its slice on the donut for a minute (Highlight).
 * Redrawn at every block boundary (by TransitionReceiver), after each sync, and once a minute
 * while the phone is awake (a non-waking alarm) so the donut's hand and "○분 남음" keep up.
 */
class RoutineWidgetProvider : AppWidgetProvider() {

    override fun onUpdate(ctx: Context, mgr: AppWidgetManager, ids: IntArray) {
        updateAll(ctx)
        TransitionScheduler.scheduleNext(ctx)
    }

    override fun onEnabled(ctx: Context) {
        SyncWorker.syncNow(ctx)
    }

    override fun onDisabled(ctx: Context) {
        ctx.getSystemService(AlarmManager::class.java).cancel(tickIntent(ctx))
    }

    override fun onReceive(ctx: Context, intent: Intent) {
        super.onReceive(ctx, intent)
        when (intent.action) {
            ACTION_REFRESH -> {
                SyncWorker.syncNow(ctx)
                updateAll(ctx)
            }
            ACTION_TICK -> updateAll(ctx)
            ACTION_PREV -> { ViewDay.set(ctx, (ViewDay.get(ctx) + 6) % 7); updateAll(ctx) }
            ACTION_NEXT -> { ViewDay.set(ctx, (ViewDay.get(ctx) + 1) % 7); updateAll(ctx) }
            ACTION_DAY -> { ViewDay.set(ctx, intent.getIntExtra(EXTRA_DAY, 0).coerceIn(0, 6)); updateAll(ctx) }
            ACTION_SELECT -> {
                val start = intent.getIntExtra(EXTRA_START, -1)
                if (start >= 0) { Highlight.toggle(ctx, ViewDay.get(ctx), start); updateAll(ctx) }
            }
        }
    }

    companion object {
        const val ACTION_REFRESH = "io.github.ksdfs1.weeklyroutine.REFRESH"
        const val ACTION_TICK = "io.github.ksdfs1.weeklyroutine.TICK"
        const val ACTION_PREV = "io.github.ksdfs1.weeklyroutine.PREV_DAY"
        const val ACTION_NEXT = "io.github.ksdfs1.weeklyroutine.NEXT_DAY"
        const val ACTION_DAY = "io.github.ksdfs1.weeklyroutine.SHOW_DAY"
        const val ACTION_SELECT = "io.github.ksdfs1.weeklyroutine.SELECT_BLOCK"
        const val EXTRA_DAY = "day"
        const val EXTRA_START = "start"
        private val DAY_CELLS = intArrayOf(R.id.day_0, R.id.day_1, R.id.day_2, R.id.day_3, R.id.day_4, R.id.day_5, R.id.day_6)

        private fun broadcast(ctx: Context, requestCode: Int, intent: Intent): PendingIntent = PendingIntent.getBroadcast(
            ctx, requestCode, intent.setClass(ctx, RoutineWidgetProvider::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        private fun tickIntent(ctx: Context): PendingIntent = PendingIntent.getBroadcast(
            ctx, 3,
            Intent(ctx, RoutineWidgetProvider::class.java).setAction(ACTION_TICK),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        /** next redraw at the top of the coming minute. RTC (not _WAKEUP): while the screen is
         *  off and the phone sleeps it simply waits, so this costs nothing in standby. */
        private fun scheduleTick(ctx: Context) {
            val am = ctx.getSystemService(AlarmManager::class.java)
            val at = (System.currentTimeMillis() / 60_000L + 1) * 60_000L + 500L
            if (TransitionScheduler.canExact(ctx)) am.setExact(AlarmManager.RTC, at, tickIntent(ctx))
            else am.set(AlarmManager.RTC, at, tickIntent(ctx))
        }

        fun updateAll(ctx: Context) {
            val mgr = AppWidgetManager.getInstance(ctx)
            val ids = mgr.getAppWidgetIds(ComponentName(ctx, RoutineWidgetProvider::class.java))
            if (ids.isEmpty()) return
            for (id in ids) mgr.updateAppWidget(id, build(ctx))
            @Suppress("DEPRECATION")
            mgr.notifyAppWidgetViewDataChanged(ids, R.id.list)
            scheduleTick(ctx)
        }

        private fun build(ctx: Context): RemoteViews {
            val v = RemoteViews(ctx.packageName, R.layout.widget_routine)
            val openApp = Notifier.openAppIntent(ctx)
            v.setOnClickPendingIntent(R.id.root, openApp)
            v.setOnClickPendingIntent(R.id.refresh, PendingIntent.getBroadcast(
                ctx, 1,
                Intent(ctx, RoutineWidgetProvider::class.java).setAction(ACTION_REFRESH),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            ))

            val shown = ViewDay.get(ctx)
            v.setOnClickPendingIntent(R.id.day_title, PendingIntent.getActivity(
                ctx, 2,
                Intent(ctx, CasePickerActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    .putExtra(CasePickerActivity.EXTRA_DAY, shown),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            ))
            v.setOnClickPendingIntent(R.id.prev_day, broadcast(ctx, 4, Intent(ACTION_PREV)))
            v.setOnClickPendingIntent(R.id.next_day, broadcast(ctx, 5, Intent(ACTION_NEXT)))
            val todayIdx = Routine.dayIndex(Calendar.getInstance())
            for ((i, cell) in DAY_CELLS.withIndex()) {
                v.setOnClickPendingIntent(cell, broadcast(ctx, 10 + i, Intent(ACTION_DAY).putExtra(EXTRA_DAY, i)))
                v.setInt(cell, "setBackgroundResource", if (i == shown) R.drawable.widget_day_active else 0)
                v.setTextColor(cell, ctx.getColor(when {
                    i == todayIdx -> R.color.accent
                    i == shown -> R.color.text
                    else -> R.color.text_mute
                }))
            }

            val routine = RoutineRepository.load(ctx)
            if (routine == null) {
                v.setTextViewText(R.id.day_title, "Weekly Routine")
                v.setTextViewText(R.id.now_title, "루틴을 아직 불러오지 못했어요")
                v.setTextViewText(R.id.now_sub, "인터넷 연결 후 ↻를 눌러주세요")
                v.setViewVisibility(R.id.clock, View.GONE)
                v.setViewVisibility(R.id.list, View.GONE)
                return v
            }

            for ((i, cell) in DAY_CELLS.withIndex()) v.setTextViewText(cell, routine.days[i].short)
            val now = Calendar.getInstance()
            val isToday = shown == todayIdx
            val day = routine.days[shown]
            val st = routine.nowStatus(now)   // "지금" is always about today
            v.setTextViewText(R.id.day_title, day.short + (if (isToday) "(오늘)" else "") + " · " + (day.activeCase?.name ?: "") + " ▾")

            val memo = st.cur?.label?.takeIf { it.isNotBlank() && it != st.name }
            v.setTextViewText(R.id.now_title, st.name + (memo?.let { " · $it" } ?: ""))
            var sub = Routine.fmtMin(st.end) + "까지"
            st.next?.let { n ->
                val nextName = n.label.ifBlank { routine.category(n.catId)?.name ?: "?" }
                sub += " · 다음: " + nextName + " " + (if (st.nextTomorrow) "내일 " else "") + Routine.fmtMin(n.start)
            }
            v.setTextViewText(R.id.now_sub, sub)
            v.setInt(R.id.now_bar, "setBackgroundColor", st.cat?.color ?: Routine.UNASSIGNED)

            // the left 40% column is roughly 120–200dp wide; draw at its upper end and let it scale down
            val px = (180 * ctx.resources.displayMetrics.density).toInt().coerceIn(180, 560)
            v.setImageViewBitmap(R.id.clock, ClockRenderer.render(routine, day, st, px, showNow = isToday, selStart = Highlight.get(ctx, shown)))
            v.setViewVisibility(R.id.clock, View.VISIBLE)

            val adapter = Intent(ctx, RoutineListService::class.java)
            adapter.data = Uri.parse(adapter.toUri(Intent.URI_INTENT_SCHEME))
            @Suppress("DEPRECATION")
            v.setRemoteAdapter(R.id.list, adapter)
            v.setViewVisibility(R.id.list, View.VISIBLE)
            // tapping a row highlights its slice: each row fills in its block's start minute, so the
            // template has to be mutable — fine here, as it's an explicit intent to this receiver
            v.setPendingIntentTemplate(R.id.list, PendingIntent.getBroadcast(
                ctx, 6,
                Intent(ctx, RoutineWidgetProvider::class.java).setAction(ACTION_SELECT),
                PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            ))
            // scroll to a row above the current block — only when the day or block changes, so the
            // minute redraws don't keep yanking back a list the user scrolled
            val blocks = day.sortedBlocks()
            val idx = if (!isToday) 0 else
                blocks.indexOf(st.cur).takeIf { it >= 0 } ?: blocks.indexOfFirst { it.start >= st.end }
            val scrollKey = "$shown|${day.activeCase?.id}|$idx"
            val prefs = Prefs.of(ctx)
            if (prefs.getString(Prefs.LIST_SCROLL_KEY, null) != scrollKey) {
                v.setScrollPosition(R.id.list, (idx - 1).coerceAtLeast(0))
                prefs.edit().putString(Prefs.LIST_SCROLL_KEY, scrollKey).apply()
            }
            return v
        }
    }
}

/** rows of the shown day's blocks for the widget's list; on today the current one is highlighted,
 *  and the one tapped (shown on the donut) is marked too */
class RoutineListService : RemoteViewsService() {
    override fun onGetViewFactory(intent: Intent): RemoteViewsFactory = Factory(applicationContext)

    private class Factory(val ctx: Context) : RemoteViewsFactory {
        private var routine: Routine? = null
        private var blocks: List<Block> = emptyList()
        private var current: Block? = null
        private var selStart: Int? = null

        override fun onCreate() {}
        override fun onDestroy() {}

        override fun onDataSetChanged() {
            val now = Calendar.getInstance()
            routine = RoutineRepository.load(ctx)
            val shown = ViewDay.get(ctx)
            blocks = routine?.days?.get(shown)?.sortedBlocks() ?: emptyList()
            current = if (shown == Routine.dayIndex(now)) routine?.nowStatus(now)?.cur else null
            selStart = Highlight.get(ctx, shown)
        }

        override fun getCount() = blocks.size

        override fun getViewAt(position: Int): RemoteViews {
            val b = blocks[position]
            val cat = routine?.category(b.catId)
            val row = RemoteViews(ctx.packageName, R.layout.widget_row)
            row.setTextColor(R.id.row_dot, cat?.color ?: Routine.UNASSIGNED)
            row.setTextViewText(R.id.row_time, Routine.fmtMin(b.start) + "–" + Routine.fmtMin(b.end))
            row.setTextViewText(R.id.row_label, b.label.ifBlank { cat?.name ?: "?" })
            row.setInt(R.id.row, "setBackgroundResource", when {
                b.start == selStart -> R.drawable.widget_row_sel
                b == current -> R.drawable.widget_row_now
                else -> 0
            })
            row.setOnClickFillInIntent(R.id.row, Intent().putExtra(RoutineWidgetProvider.EXTRA_START, b.start))
            return row
        }

        override fun getLoadingView(): RemoteViews? = null
        override fun getViewTypeCount() = 1
        override fun getItemId(position: Int) = position.toLong()
        override fun hasStableIds() = false
    }
}

/** which day the widget shows: today, unless ◀ ▶ / the day strip picked another in the last 2 minutes */
object ViewDay {
    private const val TIMEOUT_MS = 2 * 60 * 1000L

    fun get(ctx: Context): Int {
        val p = Prefs.of(ctx)
        val day = p.getInt(Prefs.VIEW_DAY, -1)
        val age = System.currentTimeMillis() - p.getLong(Prefs.VIEW_DAY_AT, 0L)
        return if (day in 0..6 && age in 0 until TIMEOUT_MS) day else Routine.dayIndex(Calendar.getInstance())
    }

    fun set(ctx: Context, day: Int) {
        Prefs.of(ctx).edit().putInt(Prefs.VIEW_DAY, day).putLong(Prefs.VIEW_DAY_AT, System.currentTimeMillis()).apply()
    }
}

/** the block tapped in the list (by start minute) on the shown day; tap it again, or wait a minute, to clear */
object Highlight {
    private const val TIMEOUT_MS = 60 * 1000L

    fun get(ctx: Context, day: Int): Int? {
        val p = Prefs.of(ctx)
        val age = System.currentTimeMillis() - p.getLong(Prefs.SEL_AT, 0L)
        if (p.getInt(Prefs.SEL_DAY, -1) != day || age !in 0 until TIMEOUT_MS) return null
        return p.getInt(Prefs.SEL_START, -1).takeIf { it >= 0 }
    }

    fun toggle(ctx: Context, day: Int, start: Int) {
        val e = Prefs.of(ctx).edit()
        if (get(ctx, day) == start) e.putLong(Prefs.SEL_AT, 0L)
        else e.putInt(Prefs.SEL_DAY, day).putInt(Prefs.SEL_START, start).putLong(Prefs.SEL_AT, System.currentTimeMillis())
        e.apply()
    }
}
