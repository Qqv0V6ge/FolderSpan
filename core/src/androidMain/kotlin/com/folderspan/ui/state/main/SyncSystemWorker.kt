package com.folderspan.ui.state.main

import android.annotation.SuppressLint
import android.app.ForegroundServiceStartNotAllowedException
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.job.JobScheduler
import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.folderspan.service.sync.SyncNetworkType
import com.folderspan.utils.LogKit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import org.koin.mp.KoinPlatformTools
import java.util.concurrent.atomic.AtomicBoolean
import strings.AppStrings
import kotlin.time.Duration.Companion.minutes

internal const val SYNC_PROGRESS_CHANNEL = "sync_progress"

internal fun syncSystemJobLostExecution(reasons: IntArray, wasExecuting: Boolean): Boolean = wasExecuting && reasons.isNotEmpty() &&
    JobScheduler.PENDING_JOB_REASON_EXECUTING !in reasons && JobScheduler.PENDING_JOB_REASON_INVALID_JOB_ID !in reasons

class SyncSystemWorker(context: Context, parameters: WorkerParameters) : CoroutineWorker(context, parameters) {
    private val notificationId = 0x40000000 or (id.hashCode() and 0x0fffffff)
    private val systemJobStopped = AtomicBoolean()

    @SuppressLint("NewApi") // WorkManager stores app cancellation reasons on every API; other JobScheduler reasons require 31.
    private fun stopped(runJob: Job): SyncSystemStop = when {
        isStopped && stopReason in setOf(WorkInfo.STOP_REASON_CANCELLED_BY_APP, WorkInfo.STOP_REASON_USER) -> SyncSystemStop.UserCanceled
        systemJobStopped.get() || isStopped || !runJob.isActive -> SyncSystemStop.Interrupted
        else -> SyncSystemStop.None
    }

    override suspend fun doWork(): Result {
        val taskId = inputData.getLong(SYNC_SYSTEM_TASK_ID, 0)
        val dueAt = inputData.getLong(SYNC_SYSTEM_DUE, 0)
        val definition = inputData.getString(SYNC_SYSTEM_DEFINITION) ?: return Result.failure()
        val state = KoinPlatformTools.defaultContext().getOrNull()?.getOrNull<SyncState>() ?: return Result.retry()
        state.start(backgroundOnly = true)
        val task = withContext(Dispatchers.Main) { state.getTask(taskId) } ?: return Result.success()
        if (!syncSystemScheduleEligible(task, state.isTaskOwnedByCurrentDevice(task)) || task.nextRunAt != dueAt ||
            syncPreviewFingerprint(task) != definition) return Result.success()
        if (task.sourceType == SyncEndpointType.Network || task.targetType == SyncEndpointType.Network ||
            task.destinations.any { it.type == SyncEndpointType.Network }) {
            val loaded = withContext(Dispatchers.Main) { state.networkState.loadPersisted() }
            if (!loaded) return Result.retry()
            state.refreshSyncAttention(taskId)
        }
        val foreground = try { setForeground(foregroundInfo(task.name, 0, 0)); true }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (error: Exception) {
            if (Build.VERSION.SDK_INT >= 31 && error is ForegroundServiceStartNotAllowedException) false
            else { LogKit.w(sanitizeSyncMessage(error.message)); return Result.retry() }
        }
        suspend fun execute() = coroutineScope {
            val runJob = checkNotNull(currentCoroutineContext()[Job])
            val scheduler = if (Build.VERSION.SDK_INT >= 36) applicationContext.getSystemService(JobScheduler::class.java)
                .forNamespace("androidx.work.systemjobscheduler") else null
            // WorkManager 2.11.2's public JobInfo extras identify this work; foreground work ignores its ordinary stop callback.
            val systemJobId = scheduler?.allPendingJobs?.firstOrNull { it.extras.getString("EXTRA_WORK_SPEC_ID") == id.toString() }?.id
            var systemJobWasExecuting = systemJobId != null && JobScheduler.PENDING_JOB_REASON_EXECUTING in
                checkNotNull(scheduler).getPendingJobReasons(systemJobId)
            val systemLease = if (systemJobId != null) launch {
                while (isActive) {
                    delay(250)
                    val reasons = checkNotNull(scheduler).getPendingJobReasons(systemJobId)
                    if (syncSystemJobLostExecution(reasons, systemJobWasExecuting)) {
                        systemJobStopped.set(true)
                        runJob.cancel()
                    }
                    systemJobWasExecuting = systemJobWasExecuting || JobScheduler.PENDING_JOB_REASON_EXECUTING in reasons
                }
            } else null
            val progress = launch {
                while (true) {
                    delay(1000)
                    val totals = withContext(Dispatchers.Main) {
                        val operation = state.activeOperationTasks(taskId).firstOrNull()
                        (operation?.currentProgressCur()?.toLong() ?: 0L) to (operation?.currentProgressMax()?.toLong() ?: 0L)
                    }
                    setProgress(workDataOf("completed" to totals.first, "total" to totals.second))
                    if (foreground) setForeground(foregroundInfo(task.name, totals.first, totals.second))
                }
            }
            try {
                withTimeoutOrNull(5000) { state.conditionsMonitor.state.first {
                    (!task.requireWifi || it.network != SyncNetworkType.Unknown) && (!task.requireCharging || it.charging != null)
                } }
                if (state.unmetRunConditions(task).isEmpty() && (task.sourceType == SyncEndpointType.Device ||
                    task.targetType == SyncEndpointType.Device || task.destinations.any { it.type == SyncEndpointType.Device })) state.deviceState.start()
                withContext(SyncSystemRun { stopped(runJob) }) {
                    state.runTaskInternal(task, "schedule", scheduledAt = dueAt, systemScheduled = true)
                }
            } finally { withContext(NonCancellable) {
                progress.cancelAndJoin()
                systemLease?.cancelAndJoin()
            } }
        }
        try {
            if (foreground) execute() else withTimeout(8.minutes) { execute() }
        } catch (timeout: TimeoutCancellationException) { return Result.retry() }
        catch (cancelled: CancellationException) {
            if (systemJobStopped.get() && currentCoroutineContext().isActive && !isStopped) return Result.retry()
            throw cancelled
        }
        catch (error: Exception) { LogKit.w(sanitizeSyncMessage(error.message)); return Result.retry() }
        return if (withContext(Dispatchers.Main) {
            state.getTask(taskId)?.let { current -> syncSystemScheduleEligible(current, state.isTaskOwnedByCurrentDevice(current)) &&
                current.nextRunAt == dueAt && syncPreviewFingerprint(current) == definition && !state.requiresSyncPreview(current) } == true
        }) Result.retry() else Result.success()
    }

    private fun foregroundInfo(name: String, completed: Long, total: Long): ForegroundInfo {
        val manager = applicationContext.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(SYNC_PROGRESS_CHANNEL, AppStrings.sync_progress_channel,
            NotificationManager.IMPORTANCE_LOW).apply { description = AppStrings.sync_progress_channel_description })
        val notification = Notification.Builder(applicationContext, SYNC_PROGRESS_CHANNEL)
            .setSmallIcon(android.R.drawable.stat_sys_upload)
            .setContentTitle(sanitizeSyncMessage(name).take(120).ifBlank { AppStrings.ui_sync_tasks })
            .setContentText(if (total > 0) "$completed / $total" else AppStrings.ui_synchronously_executing)
            .setOnlyAlertOnce(true).setOngoing(true)
            .setProgress(100, if (total > 0) (completed.coerceIn(0, total) * 100.0 / total).toInt() else 0, total <= 0)
            .addAction(Notification.Action.Builder(null, AppStrings.ui_cancel,
                WorkManager.getInstance(applicationContext).createCancelPendingIntent(id)).build())
            .build()
        return if (Build.VERSION.SDK_INT >= 29) ForegroundInfo(notificationId, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
            else ForegroundInfo(notificationId, notification)
    }
}
