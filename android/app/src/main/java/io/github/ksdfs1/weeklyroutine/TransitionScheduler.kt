package io.github.ksdfs1.weeklyroutine

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import java.util.Calendar

/**
 * One alarm at a time, set for the next moment "지금" can change (a block starting or ending,
 * or midnight when the next day's routine takes over). Each firing notifies, redraws the
 * widget and sets the following alarm.
 */
object TransitionScheduler {

    fun scheduleNext(ctx: Context) {
        val routine = RoutineRepository.load(ctx) ?: return
        val now = Calendar.getInstance()
        val boundary = routine.nextBoundary(now)
        val at = (now.clone() as Calendar).apply {
            set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
            add(Calendar.MINUTE, boundary)
            add(Calendar.SECOND, 1)   // land just past the boundary so "now" is already the new block
        }.timeInMillis

        val am = ctx.getSystemService(AlarmManager::class.java)
        val pi = pendingIntent(ctx)
        if (canExact(ctx)) {
            am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
        } else {
            // without exact-alarm permission Android may deliver it a little late
            am.setWindow(AlarmManager.RTC_WAKEUP, at, 60_000L, pi)
        }
    }

    fun canExact(ctx: Context): Boolean =
        Build.VERSION.SDK_INT < 31 || ctx.getSystemService(AlarmManager::class.java).canScheduleExactAlarms()

    private fun pendingIntent(ctx: Context): PendingIntent = PendingIntent.getBroadcast(
        ctx, 0, Intent(ctx, TransitionReceiver::class.java),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
    )
}

class TransitionReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        val pending = goAsync()
        Thread {
            try {
                // the hourly sync can be held back by Doze; catch up now if the cache is old
                if (RoutineRepository.isStale(ctx)) RoutineRepository.fetch(ctx)
                handleTransition(ctx)
            } finally {
                pending.finish()
            }
        }.start()
    }

    private fun handleTransition(ctx: Context) {
        val routine = RoutineRepository.load(ctx)
        if (routine != null) {
            val prefs = Prefs.of(ctx)
            val st = routine.nowStatus()
            val lastKey = prefs.getString(Prefs.LAST_KEY, null)
            if (st.key != lastKey) {
                prefs.edit().putString(Prefs.LAST_KEY, st.key).apply()
                val wanted = prefs.getBoolean(Prefs.NOTIFY, true) &&
                    (st.cur != null || prefs.getBoolean(Prefs.NOTIFY_GAPS, true))
                if (wanted) Notifier.postStatus(ctx, routine, st)
            }
        }
        RoutineWidgetProvider.updateAll(ctx)
        TransitionScheduler.scheduleNext(ctx)
    }
}

/** alarms are wiped by a reboot/app update and go stale when the clock or time zone changes */
class BootReceiver : BroadcastReceiver() {
    private val actions = setOf(
        Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_MY_PACKAGE_REPLACED,
        Intent.ACTION_TIME_CHANGED, Intent.ACTION_TIMEZONE_CHANGED,
        "android.app.action.SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED",
    )

    override fun onReceive(ctx: Context, intent: Intent) {
        if (intent.action !in actions) return
        RoutineRepository.load(ctx)?.let {
            Prefs.of(ctx).edit().putString(Prefs.LAST_KEY, it.nowStatus().key).apply()
        }
        RoutineWidgetProvider.updateAll(ctx)
        TransitionScheduler.scheduleNext(ctx)
    }
}
