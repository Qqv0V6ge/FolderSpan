package com.folderspan.notification

import com.folderspan.localization.LocalizedMessage
import com.folderspan.localization.LocalizedMessageKeys
import com.folderspan.ui.state.main.*
import org.koin.mp.KoinPlatformTools

const val SYNC_RUN_NOTIFICATION_KIND = "SyncRun"
private const val TASK_ID = "sync_task_id"
private const val RUN_ID = "sync_run_id"
private const val EVENT = "sync_event"

enum class SyncNotificationEvent { Completed, Failed, PendingDeletions, PreviewRequired }
data class SyncNotificationTarget(val taskId: Long, val runId: Long, val event: SyncNotificationEvent) {
    val opensPreview: Boolean get() = event == SyncNotificationEvent.PreviewRequired || event == SyncNotificationEvent.PendingDeletions
}

fun syncNotificationTarget(metadata: Map<String, String>): SyncNotificationTarget? {
    if (metadata[RequestNotificationFactory.META_NOTIFICATION_KIND] != SYNC_RUN_NOTIFICATION_KIND) return null
    val taskId = metadata[TASK_ID]?.toLongOrNull()?.takeIf { it > 0 } ?: return null
    val runId = metadata[RUN_ID]?.toLongOrNull()?.takeIf { it >= 0 } ?: return null
    if (metadata[RequestNotificationFactory.META_REQUEST_ID] != "$SYNC_RUN_NOTIFICATION_KIND:$taskId") return null
    val event = SyncNotificationEvent.entries.firstOrNull { it.name == metadata[EVENT] } ?: return null
    if (event != SyncNotificationEvent.PreviewRequired && runId == 0L) return null
    return SyncNotificationTarget(taskId, runId, event)
}

internal fun buildSyncRunNotification(
    task: SyncTask, event: SyncNotificationEvent, record: SyncRunRecord? = null, pendingCount: Long = 0,
): RequestNotificationBundle? {
    val confirmation = event == SyncNotificationEvent.PreviewRequired || event == SyncNotificationEvent.PendingDeletions
    if (task.notifyLevel == SyncNotifyLevel.Off || task.notifyLevel == SyncNotifyLevel.FailuresAndConfirmations &&
        !confirmation && event != SyncNotificationEvent.Failed) return null
    require(task.id > 0 && (event == SyncNotificationEvent.PreviewRequired || record != null && record.runId > 0))
    val group = (record?.policySnapshot ?: task).destinations.isNotEmpty()
    val targets = record?.participants.orEmpty().filter { it.id != "source" }.map { sanitizeSyncMessage(it.label).take(120) }
    val body = LocalizedMessage(if (group) when (event) {
        SyncNotificationEvent.PreviewRequired -> LocalizedMessageKeys.NOTIFICATION_SYNC_GROUP_PREVIEW
        SyncNotificationEvent.PendingDeletions -> LocalizedMessageKeys.NOTIFICATION_SYNC_GROUP_PENDING
        else -> LocalizedMessageKeys.NOTIFICATION_SYNC_GROUP_RESULT
    } else when (event) {
        SyncNotificationEvent.PreviewRequired -> LocalizedMessageKeys.NOTIFICATION_SYNC_PREVIEW
        SyncNotificationEvent.PendingDeletions -> LocalizedMessageKeys.NOTIFICATION_SYNC_PENDING
        else -> LocalizedMessageKeys.NOTIFICATION_SYNC_RESULT
    }, mapOf("name" to sanitizeSyncMessage(task.name).take(120), "status" to record?.status?.name.orEmpty(),
        "success" to (record?.successCount ?: 0).toString(), "failure" to (record?.failureCount ?: 0).toString(),
        "pending" to pendingCount.coerceAtLeast(0).toString(), "mode" to (record?.policySnapshot ?: task).mode.name,
        "candidates" to task.destinations.size.toString(), "targets" to targets.joinToString(" · ").take(240),
        "target_count" to (targets.size.takeIf { it > 0 } ?: record?.targets?.size ?: 0).toString()))
    return RequestNotificationFactory.buildLocalizedCustomNotification(
        kind = SYNC_RUN_NOTIFICATION_KIND,
        localizedTitle = LocalizedMessage(LocalizedMessageKeys.NOTIFICATION_SYNC_TITLE), localizedMessage = body,
        localizedCategory = LocalizedMessage(LocalizedMessageKeys.CATEGORY_SYNC),
        type = when {
            confirmation -> NotificationType.Warning
            event == SyncNotificationEvent.Failed -> NotificationType.Error
            record?.status == SyncRunStatus.Canceled -> NotificationType.Info
            else -> NotificationType.Success
        },
        metadata = mapOf(TASK_ID to task.id.toString(), RUN_ID to (record?.runId ?: 0).toString(), EVENT to event.name),
        timestamp = record?.endedAt?.takeIf { it > 0 } ?: nowMillis(), requestId = "$SYNC_RUN_NOTIFICATION_KIND:${task.id}",
        config = NotificationFactoryConfig(showInBell = true, showInBanner = true, sendSystemNotification = true),
    )
}

internal fun SyncState.postSyncNotification(task: SyncTask, event: SyncNotificationEvent, record: SyncRunRecord? = null, pendingCount: Long = 0) {
    val state = KoinPlatformTools.defaultContext().getOrNull()?.getOrNull<NotificationState>() ?: return
    val bundle = buildSyncRunNotification(task, event, record, pendingCount) ?: return
    RequestNotificationDispatcher.post(state, bundle)
}

internal fun SyncState.clearSyncNotification(taskId: Long) {
    val state = KoinPlatformTools.defaultContext().getOrNull()?.getOrNull<NotificationState>() ?: return
    RequestNotificationDispatcher.remove(state, "$SYNC_RUN_NOTIFICATION_KIND:$taskId")
}
