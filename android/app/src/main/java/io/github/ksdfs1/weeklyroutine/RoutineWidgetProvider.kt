package io.github.ksdfs1.weeklyroutine

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
 * 4x2 home-screen widget: today's donut, the "지금" line and today's blocks.
 * Redrawn at every block boundary (by TransitionReceiver) and after each sync — not every
 * minute, which is why it says "12:00까지" rather than a countdown.
 */
class RoutineWidgetProvider : AppWidgetProvider() {

    override fun onUpdate(ctx: Context, mgr: AppWidgetManager, ids: IntArray) {
        updateAll(ctx)
        TransitionScheduler.scheduleNext(ctx)
    }

    override fun onEnabled(ctx: Context) {
        SyncWorker.syncNow(ctx)
    }

    override fun onReceive(ctx: Context, intent: Intent) {
        super.onReceive(ctx, intent)
        if (intent.action == ACTION_REFRESH) {
            SyncWorker.syncNow(ctx)
            updateAll(ctx)
        }
    }

    companion object {
        const val ACTION_REFRESH = "io.github.ksdfs1.weeklyroutine.REFRESH"

        fun updateAll(ctx: Context) {
            val mgr = AppWidgetManager.getInstance(ctx)
            val ids = mgr.getAppWidgetIds(ComponentName(ctx, RoutineWidgetProvider::class.java))
            if (ids.isEmpty()) return
            for (id in ids) mgr.updateAppWidget(id, build(ctx))
            @Suppress("DEPRECATION")
            mgr.notifyAppWidgetViewDataChanged(ids, R.id.list)
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

            v.setOnClickPendingIntent(R.id.day_title, PendingIntent.getActivity(
                ctx, 2,
                Intent(ctx, CasePickerActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            ))

            val routine = RoutineRepository.load(ctx)
            if (routine == null) {
                v.setTextViewText(R.id.day_title, "Weekly Routine")
                v.setTextViewText(R.id.now_title, "루틴을 아직 불러오지 못했어요")
                v.setTextViewText(R.id.now_sub, "인터넷 연결 후 ↻를 눌러주세요")
                v.setViewVisibility(R.id.clock, View.GONE)
                v.setViewVisibility(R.id.list, View.GONE)
                return v
            }

            val now = Calendar.getInstance()
            val day = routine.days[Routine.dayIndex(now)]
            val st = routine.nowStatus(now)
            v.setTextViewText(R.id.day_title, day.short + " · " + (day.activeCase?.name ?: "") + " ▾")

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
            v.setImageViewBitmap(R.id.clock, ClockRenderer.render(routine, day, st, px))
            v.setViewVisibility(R.id.clock, View.VISIBLE)

            val adapter = Intent(ctx, RoutineListService::class.java)
            adapter.data = Uri.parse(adapter.toUri(Intent.URI_INTENT_SCHEME))
            @Suppress("DEPRECATION")
            v.setRemoteAdapter(R.id.list, adapter)
            v.setViewVisibility(R.id.list, View.VISIBLE)
            // rows add nothing to the intent, so the template can stay immutable (Android 14+ refuses
            // a mutable PendingIntent around an implicit intent like this URL one)
            v.setPendingIntentTemplate(R.id.list, Notifier.openAppIntent(ctx))
            val blocks = day.sortedBlocks()
            val idx = blocks.indexOf(st.cur).takeIf { it >= 0 } ?: blocks.indexOfFirst { it.start >= st.end }
            if (idx > 0) v.setScrollPosition(R.id.list, idx - 1)
            return v
        }
    }
}

/** rows of today's blocks for the widget's list; the current one is highlighted */
class RoutineListService : RemoteViewsService() {
    override fun onGetViewFactory(intent: Intent): RemoteViewsFactory = Factory(applicationContext)

    private class Factory(val ctx: Context) : RemoteViewsFactory {
        private var routine: Routine? = null
        private var blocks: List<Block> = emptyList()
        private var current: Block? = null

        override fun onCreate() {}
        override fun onDestroy() {}

        override fun onDataSetChanged() {
            val now = Calendar.getInstance()
            routine = RoutineRepository.load(ctx)
            blocks = routine?.days?.get(Routine.dayIndex(now))?.sortedBlocks() ?: emptyList()
            current = routine?.nowStatus(now)?.cur
        }

        override fun getCount() = blocks.size

        override fun getViewAt(position: Int): RemoteViews {
            val b = blocks[position]
            val cat = routine?.category(b.catId)
            val row = RemoteViews(ctx.packageName, R.layout.widget_row)
            row.setTextColor(R.id.row_dot, cat?.color ?: Routine.UNASSIGNED)
            row.setTextViewText(R.id.row_time, Routine.fmtMin(b.start) + "–" + Routine.fmtMin(b.end))
            row.setTextViewText(R.id.row_label, b.label.ifBlank { cat?.name ?: "?" })
            row.setInt(R.id.row, "setBackgroundResource", if (b == current) R.drawable.widget_row_now else 0)
            row.setOnClickFillInIntent(R.id.row, Intent())
            return row
        }

        override fun getLoadingView(): RemoteViews? = null
        override fun getViewTypeCount() = 1
        override fun getItemId(position: Int) = position.toLong()
        override fun hasStableIds() = false
    }
}
