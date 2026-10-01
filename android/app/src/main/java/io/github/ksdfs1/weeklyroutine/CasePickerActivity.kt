package io.github.ksdfs1.weeklyroutine

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import java.util.Calendar

/**
 * Opened by tapping the "요일 · 케이스 ▾" pill on the widget: a small list of the shown day's cases.
 * The pick shows on this device at once; with an edit token it is then saved to the server
 * (CaseSaveWorker) so the web app and the PC widget follow.
 */
class CasePickerActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val routine = RoutineRepository.load(this)
        if (routine == null) {
            Toast.makeText(this, "루틴을 아직 불러오지 못했어요", Toast.LENGTH_SHORT).show()
            finish()
            return
        }
        val dayIndex = intent.getIntExtra(EXTRA_DAY, Routine.dayIndex(Calendar.getInstance())).coerceIn(0, 6)
        val day = routine.days[dayIndex]
        val cases = day.cases
        val checked = cases.indexOfFirst { it.id == day.activeCase?.id }

        AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle(day.label + " 케이스")
            .setSingleChoiceItems(cases.map { it.name.ifBlank { "(이름 없음)" } }.toTypedArray(), checked) { dlg, which ->
                dlg.dismiss()
                if (which != checked) pick(dayIndex, cases[which].id)
            }
            .setNegativeButton("취소", null)
            .setOnDismissListener { finish() }
            .show()
    }

    companion object {
        const val EXTRA_DAY = "day"
    }

    private fun pick(dayIndex: Int, caseId: String) {
        RoutineRepository.setCaseOverride(this, dayIndex, caseId)
        SyncWorker.afterSync(this)   // redraw the widget and reschedule for the new case
        if (RoutineRepository.token(this).isEmpty()) {
            Toast.makeText(this, "이 기기에만 적용했어요. 앱에서 편집 잠금을 풀어두면(편집 토큰) 모든 기기에 반영돼요.", Toast.LENGTH_LONG).show()
        } else {
            CaseSaveWorker.enqueue(this, dayIndex, caseId)
        }
    }
}

/** Saves a case picked on the widget to the server; retries a few times while offline. */
class CaseSaveWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result {
        val ctx = applicationContext
        val day = inputData.getInt(KEY_DAY, -1)
        val caseId = inputData.getString(KEY_CASE) ?: return Result.failure()
        if (day !in 0..6) return Result.failure()
        return when (RoutineRepository.saveCase(ctx, day, caseId)) {
            RoutineRepository.SaveResult.SAVED -> {
                SyncWorker.afterSync(ctx)
                toast(ctx, "저장했어요. 모든 기기에 반영돼요.")
                Result.success()
            }
            RoutineRepository.SaveResult.BAD_TOKEN -> {
                toast(ctx, "편집 토큰이 올바르지 않아 이 기기에만 적용했어요.")
                Result.success()
            }
            RoutineRepository.SaveResult.NO_TOKEN -> Result.success()
            RoutineRepository.SaveResult.FAILED ->
                if (runAttemptCount < 3) Result.retry() else {
                    toast(ctx, "서버에 저장하지 못해 이 기기에만 적용했어요.")
                    Result.success()
                }
        }
    }

    companion object {
        private const val KEY_DAY = "day"
        private const val KEY_CASE = "case"

        fun enqueue(ctx: Context, dayIndex: Int, caseId: String) {
            val req = OneTimeWorkRequestBuilder<CaseSaveWorker>()
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .setInputData(workDataOf(KEY_DAY to dayIndex, KEY_CASE to caseId))
                .build()
            // a newer pick for the same day replaces one that hasn't gone out yet
            WorkManager.getInstance(ctx).enqueueUniqueWork("save-case-$dayIndex", ExistingWorkPolicy.REPLACE, req)
        }

        private fun toast(ctx: Context, msg: String) {
            Handler(Looper.getMainLooper()).post { Toast.makeText(ctx, msg, Toast.LENGTH_SHORT).show() }
        }
    }
}
