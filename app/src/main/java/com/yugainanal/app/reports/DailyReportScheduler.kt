package com.yugainanal.app.reports

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.await
import androidx.work.workDataOf
import com.yugainanal.app.MainActivity
import com.yugainanal.app.data.CareZone
import java.time.DateTimeException
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException

const val OPEN_REPORTS_CHILD_ID = "open_reports_child_id"
const val OPEN_REPORT_ID = "open_report_id"

private const val INPUT_CHILD_ID = "child_id"
private const val INPUT_REVISION = "schedule_revision"
private const val INPUT_DATE = "report_date"
private const val NOTIFICATION_CHANNEL_ID = "daily_reports"

internal object DailyReportScheduler {
    suspend fun enqueue(context: Context, row: ReportScheduleRow) {
        val date = row.nextDate?.takeIf { row.enabled } ?: return
        val whenToRun = LocalDate.parse(date).atTime(LocalTime.parse(row.localTime)).atZone(CareZone).toInstant()
        val delayMillis = Duration.between(Instant.now(), whenToRun).toMillis().coerceAtLeast(0)
        val request = OneTimeWorkRequestBuilder<DailyReportWorker>()
            .setInitialDelay(delayMillis, TimeUnit.MILLISECONDS)
            .setInputData(workDataOf(INPUT_CHILD_ID to row.childId, INPUT_REVISION to row.revision, INPUT_DATE to date))
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(workName(row, date), ExistingWorkPolicy.KEEP, request).await()
    }

    suspend fun cancel(context: Context, row: ReportScheduleRow) {
        val date = row.nextDate ?: return
        WorkManager.getInstance(context).cancelUniqueWork(workName(row, date)).await()
    }

    fun notify(context: Context, report: DailyReport) {
        val manager = context.getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= 33 &&
            context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) return
        val channel = NotificationChannel(NOTIFICATION_CHANNEL_ID, "하루 리포트", NotificationManager.IMPORTANCE_DEFAULT)
        manager.createNotificationChannel(channel)
        if (!manager.areNotificationsEnabled()) return

        val intent = Intent(context, MainActivity::class.java)
            .setData(Uri.parse("yugainanal://reports/${report.childId}/${report.id}"))
            .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            .putExtra(OPEN_REPORTS_CHILD_ID, report.childId)
            .putExtra(OPEN_REPORT_ID, report.id)
        val open = PendingIntent.getActivity(context, 0, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val notification = Notification.Builder(context, NOTIFICATION_CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_agenda)
            .setContentTitle("하루 리포트가 준비됐어요")
            .setContentText("육아의나날에서 하루 기록을 확인해 주세요.")
            .setContentIntent(open)
            .setOnlyAlertOnce(true)
            .setAutoCancel(true)
            .setVisibility(Notification.VISIBILITY_PRIVATE)
            .build()
        try {
            manager.notify("care-report-${report.id}", 0, notification)
        } catch (_: SecurityException) {
            // Permission can change between the permission check and the notification call.
        }
    }

    private fun workName(row: ReportScheduleRow, date: String): String =
        "daily-report-${row.childId}-${row.revision}-$date"
}

class DailyReportWorker(context: Context, parameters: WorkerParameters) : CoroutineWorker(context, parameters) {
    override suspend fun doWork(): Result {
        val childId = inputData.getLong(INPUT_CHILD_ID, 0)
        val revision = inputData.getLong(INPUT_REVISION, 0)
        val dateText = inputData.getString(INPUT_DATE)
        if (childId <= 0 || revision <= 0 || dateText == null) return Result.failure()
        val date = try {
            LocalDate.parse(dateText)
        } catch (_: DateTimeException) {
            return Result.failure()
        }
        return try {
            if (ReportStore(applicationContext).runScheduledReport(childId, revision, date)) {
                Result.success()
            } else {
                Result.retry()
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            Result.retry()
        }
    }
}
