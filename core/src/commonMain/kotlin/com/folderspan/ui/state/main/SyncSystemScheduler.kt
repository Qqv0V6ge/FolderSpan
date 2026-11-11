package com.folderspan.ui.state.main

import kotlinx.coroutines.CoroutineScope
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext

internal interface SyncSystemScheduler {
    val managesTimedRuns: Boolean
    fun start()
    fun refresh()
}

internal object NoSyncSystemScheduler : SyncSystemScheduler {
    override val managesTimedRuns = false
    override fun start() = Unit
    override fun refresh() = Unit
}

internal expect fun createSyncSystemScheduler(state: SyncState, scope: CoroutineScope): SyncSystemScheduler

internal enum class SyncSystemStop { None, UserCanceled, Interrupted }

internal class SyncSystemRun(val stopped: () -> SyncSystemStop) : AbstractCoroutineContextElement(Key) {
    companion object Key : CoroutineContext.Key<SyncSystemRun>
}

internal fun syncSystemScheduleSignature(task: SyncTask, previewRequired: Boolean): String =
    "${syncPreviewFingerprint(task)}:${task.nextRunAt}:$previewRequired"

internal fun syncSystemScheduleEligible(task: SyncTask, owned: Boolean): Boolean =
    owned && task.enabled && task.scheduleType != SyncScheduleType.Manual && task.nextRunAt > 0
