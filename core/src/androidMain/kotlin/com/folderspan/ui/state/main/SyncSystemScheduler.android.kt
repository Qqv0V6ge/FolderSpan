package com.folderspan.ui.state.main

import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Build
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.await
import androidx.work.workDataOf
import com.folderspan.androidContext
import com.folderspan.utils.LogKit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit

internal const val SYNC_SYSTEM_TAG = "folderspan-sync"
internal const val SYNC_SYSTEM_TASK_TAG = "sync-task:"
internal const val SYNC_SYSTEM_SIGNATURE_TAG = "sync-version:"
internal const val SYNC_SYSTEM_TASK_ID = "taskId"
internal const val SYNC_SYSTEM_DUE = "dueAt"
internal const val SYNC_SYSTEM_DEFINITION = "definition"

internal fun syncSystemConstraints(task: SyncTask, apiLevel: Int = Build.VERSION.SDK_INT): Constraints = Constraints.Builder().apply {
    setRequiresCharging(task.requireCharging)
    if (task.requireWifi) {
        if (Build.VERSION.SDK_INT >= 28 && apiLevel >= 28) setRequiredNetworkRequest(NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .addTransportType(NetworkCapabilities.TRANSPORT_ETHERNET).build(), NetworkType.UNMETERED)
        else setRequiredNetworkType(NetworkType.UNMETERED)
    }
}.build()

internal actual fun createSyncSystemScheduler(state: SyncState, scope: CoroutineScope): SyncSystemScheduler =
    AndroidSyncSystemScheduler(state, scope, WorkManager.getInstance(androidContext()))

internal class AndroidSyncSystemScheduler(
    private val state: SyncState,
    private val scope: CoroutineScope,
    private val manager: WorkManager,
) : SyncSystemScheduler {
    override val managesTimedRuns = true
    private val changes = Channel<Unit>(Channel.CONFLATED)
    private var started = false

    override fun start() {
        if (started) return
        started = true
        scope.launch { manager.getWorkInfosByTagFlow(SYNC_SYSTEM_TAG).collect { refresh() } }
        scope.launch {
            for (change in changes) try { reconcile() }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) { LogKit.w(sanitizeSyncMessage(error.message)) }
        }
        refresh()
    }

    override fun refresh() { changes.trySend(Unit) }

    private suspend fun reconcile() {
        val tasks = withContext(Dispatchers.Main) {
            state.snapshotTasks().filter { syncSystemScheduleEligible(it, state.isTaskOwnedByCurrentDevice(it)) }
                .associateWith { state.requiresSyncPreview(it) }
        }
        val ids = tasks.keys.mapTo(mutableSetOf()) { it.id }
        val work = manager.getWorkInfosByTagFlow(SYNC_SYSTEM_TAG).first()
        for (info in work) {
            val taskId = info.tags.firstOrNull { it.startsWith(SYNC_SYSTEM_TASK_TAG) }?.removePrefix(SYNC_SYSTEM_TASK_TAG)?.toLongOrNull()
            if (!info.state.isFinished && taskId !in ids) manager.cancelWorkById(info.id).await()
        }
        for ((task, previewRequired) in tasks) {
            val taskTag = SYNC_SYSTEM_TASK_TAG + task.id
            val previous = work.filter { taskTag in it.tags }
            // Completion emits another WorkInfo; never replace a Worker from inside its own run.
            if (previous.any { it.state == WorkInfo.State.RUNNING }) continue
            val signature = SYNC_SYSTEM_SIGNATURE_TAG + syncSystemScheduleSignature(task, previewRequired)
            if (previous.any { signature in it.tags }) continue
            val request = OneTimeWorkRequestBuilder<SyncSystemWorker>()
                .setInputData(workDataOf(SYNC_SYSTEM_TASK_ID to task.id, SYNC_SYSTEM_DUE to task.nextRunAt,
                    SYNC_SYSTEM_DEFINITION to syncPreviewFingerprint(task)))
                .setInitialDelay((task.nextRunAt - nowMillis()).coerceAtLeast(0), TimeUnit.MILLISECONDS)
                .setConstraints(syncSystemConstraints(task))
                .addTag(SYNC_SYSTEM_TAG).addTag(taskTag).addTag(signature).build()
            manager.enqueueUniqueWork("sync-task-${task.id}", ExistingWorkPolicy.REPLACE, request).await()
        }
    }
}
