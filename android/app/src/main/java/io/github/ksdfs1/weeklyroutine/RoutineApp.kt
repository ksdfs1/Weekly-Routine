package io.github.ksdfs1.weeklyroutine

import android.app.Application
import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import java.util.concurrent.TimeUnit

class RoutineApp : Application() {
    override fun onCreate() {
        super.onCreate()
        Notifier.createChannels(this)
        SyncWorker.schedulePeriodic(this)
    }
}

/** Pulls the latest routine (e.g. after it was edited on another device), then redraws and reschedules. */
class SyncWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result {
        val ok = RoutineRepository.fetch(applicationContext)
        if (ok) afterSync(applicationContext)
        return if (ok) Result.success() else Result.retry()
    }

    companion object {
        private val online = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()

        fun schedulePeriodic(ctx: Context) {
            val req = PeriodicWorkRequestBuilder<SyncWorker>(1, TimeUnit.HOURS).setConstraints(online).build()
            WorkManager.getInstance(ctx).enqueueUniquePeriodicWork("sync", ExistingPeriodicWorkPolicy.KEEP, req)
        }

        fun syncNow(ctx: Context) {
            val req = OneTimeWorkRequestBuilder<SyncWorker>().setConstraints(online).build()
            WorkManager.getInstance(ctx).enqueueUniqueWork("sync-now", ExistingWorkPolicy.REPLACE, req)
        }

        /** new data may change what "now" is: redraw, and remember the current state without notifying about it */
        fun afterSync(ctx: Context) {
            RoutineRepository.load(ctx)?.let {
                Prefs.of(ctx).edit().putString(Prefs.LAST_KEY, it.nowStatus().key).apply()
            }
            RoutineWidgetProvider.updateAll(ctx)
            TransitionScheduler.scheduleNext(ctx)
        }
    }
}
