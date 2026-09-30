package io.github.ksdfs1.weeklyroutine

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.RingtoneManager
import android.net.Uri
import android.os.Build

/**
 * "지금" status notifications. A channel's sound/vibration can't be changed by the app once it
 * exists, so there is one channel per combination and the user's toggles pick which one is used.
 */
object Notifier {
    private const val NOTIFICATION_ID = 1
    private val VIBRATION = longArrayOf(0, 250, 150, 250)

    private enum class Channel(val id: String, val title: String, val sound: Boolean, val vibrate: Boolean) {
        SOUND_VIBRATE("status_sound_vibrate", "상태 변경 (소리+진동)", true, true),
        SOUND("status_sound", "상태 변경 (소리)", true, false),
        VIBRATE("status_vibrate", "상태 변경 (진동)", false, true),
        SILENT("status_silent", "상태 변경 (무음)", false, false),
    }

    fun createChannels(ctx: Context) {
        val nm = ctx.getSystemService(NotificationManager::class.java)
        val audio = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_NOTIFICATION)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .build()
        for (c in Channel.entries) {
            val ch = NotificationChannel(c.id, c.title, NotificationManager.IMPORTANCE_HIGH)
            ch.description = "루틴상 지금 해야 할 일이 바뀌면 알려줘요"
            ch.setSound(if (c.sound) RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION) else null, if (c.sound) audio else null)
            ch.enableVibration(c.vibrate)
            ch.vibrationPattern = if (c.vibrate) VIBRATION else null
            nm.createNotificationChannel(ch)
        }
    }

    private fun channelFor(ctx: Context): Channel {
        val p = Prefs.of(ctx)
        val sound = p.getBoolean(Prefs.SOUND, true)
        val vibrate = p.getBoolean(Prefs.VIBRATE, true)
        return when {
            sound && vibrate -> Channel.SOUND_VIBRATE
            sound -> Channel.SOUND
            vibrate -> Channel.VIBRATE
            else -> Channel.SILENT
        }
    }

    fun canPost(ctx: Context): Boolean =
        Build.VERSION.SDK_INT < 33 ||
            ctx.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    fun openAppIntent(ctx: Context): PendingIntent = PendingIntent.getActivity(
        ctx, 0, Intent(Intent.ACTION_VIEW, Uri.parse(RoutineRepository.APP_URL)),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
    )

    /** e.g. title "지금: 업무 · 팀 회의", text "09:00–12:00 · 다음: 점심식사 12:00" */
    fun postStatus(ctx: Context, routine: Routine, st: NowStatus, test: Boolean = false) {
        if (!canPost(ctx)) return
        val memo = st.cur?.label?.takeIf { it.isNotBlank() && it != st.name }
        val title = "지금: " + st.name + (memo?.let { " · $it" } ?: "")
        var text = Routine.fmtMin(st.start) + "–" + Routine.fmtMin(st.end)
        st.next?.let { n ->
            val nextName = n.label.ifBlank { routine.category(n.catId)?.name ?: "?" }
            text += " · 다음: " + nextName + " " + (if (st.nextTomorrow) "내일 " else "") + Routine.fmtMin(n.start)
        }
        val color = st.cat?.color ?: Routine.UNASSIGNED
        val builder = Notification.Builder(ctx, channelFor(ctx).id)
            .setSmallIcon(R.drawable.ic_stat_routine)
            .setContentTitle(if (test) "[테스트] $title" else title)
            .setContentText(text)
            .setColor(color)
            .setContentIntent(openAppIntent(ctx))
            .setAutoCancel(true)
            .setShowWhen(true)
            .setCategory(Notification.CATEGORY_REMINDER)
        // drop the notification once its block is over (the next one replaces it anyway)
        val remainingMin = st.end - Routine.minuteOfDay(java.util.Calendar.getInstance())
        if (!test && remainingMin > 0) builder.setTimeoutAfter(remainingMin * 60_000L)
        ctx.getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, builder.build())
    }
}
