package com.folderspan.notification

import com.folderspan.test.ChineseLocalizationTest
import com.folderspan.ui.state.main.*
import kotlin.test.*

class SyncRunNotificationTest : ChineseLocalizationTest() {
    private fun task(level: SyncNotifyLevel = SyncNotifyLevel.FailuresAndConfirmations) = SyncTask(7, "Photos", sourceType = SyncEndpointType.Local,
        sourcePath = "/private-source", targetType = SyncEndpointType.Local, targetPath = "/private-target", notifyLevel = level)
    private fun record(status: SyncRunStatus = SyncRunStatus.Success) = SyncRunRecord(11, 7, "schedule", 10, 20, status, 3, 2, 1,
        "https://admin:record-secret@example.test/?token=record-token")

    @AfterTest fun resetForeground() { RequestNotificationDispatcher.setAppInForeground(false) }

    @Test fun notificationLevelsKeepFailuresAndConfirmationsAndSuppressAllWhenOff() {
        for (event in SyncNotificationEvent.entries) {
            assertNull(buildSyncRunNotification(task(SyncNotifyLevel.Off), event, record()))
            assertNotNull(buildSyncRunNotification(task(SyncNotifyLevel.All), event, record()))
        }
        assertNull(buildSyncRunNotification(task(), SyncNotificationEvent.Completed, record()))
        for (event in SyncNotificationEvent.entries.filter { it != SyncNotificationEvent.Completed }) {
            assertNotNull(buildSyncRunNotification(task(), event, record()))
        }
    }

    @Test fun foregroundDeliveryReplacesTheSameTaskAndActionsOpenOnlyTheRelevantPage() {
        RequestNotificationDispatcher.setAppInForeground(true)
        val state = NotificationState()
        val preview = buildSyncRunNotification(task(), SyncNotificationEvent.PreviewRequired)!!
        val first = RequestNotificationDispatcher.post(state, preview)
        assertTrue(first.inAppVisible)
        assertTrue(first.systemNotificationSuppressed)
        assertFalse(first.systemNotificationPosted)
        assertEquals(SyncNotificationTarget(7, 0, SyncNotificationEvent.PreviewRequired), syncNotificationTarget(preview.notification.metadata))
        assertEquals(listOf(NotificationActionKeys.ACTION_VIEW), notificationActionsFor(preview.notification.metadata).map { it.id })
        val pending = buildSyncRunNotification(task(), SyncNotificationEvent.PendingDeletions, record(), 101)!!
        RequestNotificationDispatcher.post(state, pending)
        assertEquals(1, state.notifications.size)
        assertEquals(preview.notification.id, pending.notification.id)
        assertEquals(SyncNotificationEvent.PendingDeletions, syncNotificationTarget(state.notifications.single().metadata)?.event)
        val failed = buildSyncRunNotification(task(), SyncNotificationEvent.Failed, record(SyncRunStatus.Failure))!!
        RequestNotificationDispatcher.post(state, failed)
        assertEquals(1, state.notifications.size)
        assertEquals(listOf(NotificationActionKeys.ACTION_OPEN), notificationActionsFor(failed.notification.metadata).map { it.id })
        assertFalse(syncNotificationTarget(failed.notification.metadata)!!.opensPreview)
    }

    @Test fun messagesAndPayloadsContainNoEndpointDetailsOrFailureCredentials() {
        val task = task().copy(name = "Photos https://admin:name-secret@example.test/?token=name-token")
        val bundle = buildSyncRunNotification(task, SyncNotificationEvent.Failed, record(SyncRunStatus.Failure))!!
        val text = bundle.notification.displayMessage() + bundle.notification.metadata + bundle.systemNotification!!.displayBody() + bundle.systemNotification.payloadData
        for (secret in listOf("name-secret", "name-token", "record-secret", "record-token", "/private-source", "/private-target")) assertFalse(secret in text, secret)
        assertEquals(setOf("sync_task_id", "sync_run_id", "sync_event", RequestNotificationFactory.META_REQUEST_ID,
            RequestNotificationFactory.META_NOTIFICATION_KIND), bundle.notification.metadata.keys)
    }

    @Test fun groupNotificationsUseTheRunModeAndActualTargetsWithoutExportingCandidateDetails() {
        for (mode in SyncMode.entries) {
            val group = task(SyncNotifyLevel.All).withMode(mode).copy(destinations = listOf(
                StorageDestination(ref = "https://user:candidate-secret@unused.test/", path = "/private-unused"),
                StorageDestination(ref = "chosen", path = "/private-chosen")), selectRecommended = true)
            val result = record().copy(policySnapshot = group, participants = listOf(
                SyncRunParticipant("source", "Private source", SyncEndpointType.Local, "", listOf("/private-source")),
                SyncRunParticipant("target-0", "Archive B", SyncEndpointType.Network, "chosen", listOf("/private-chosen"))))
            val bundle = buildSyncRunNotification(group, SyncNotificationEvent.Completed, result)!!
            val text = bundle.notification.displayMessage()
            val modeLabel = when (mode) {
                SyncMode.Update -> strings.AppStrings.sync_mode_update
                SyncMode.Mirror -> strings.AppStrings.sync_mode_mirror
                SyncMode.BackupThenClean -> strings.AppStrings.sync_mode_backup_clean
                SyncMode.Bidirectional -> strings.AppStrings.sync_mode_bidirectional
            }
            assertTrue(modeLabel in text)
            assertTrue("Archive B" in text)
            val payload = text + bundle.systemNotification!!.payloadData
            for (secret in listOf("candidate-secret", "unused.test", "/private-unused", "/private-chosen", "/private-source", "Private source")) {
                assertFalse(secret in payload, secret)
            }
            assertEquals(SyncNotificationTarget(7, 11, SyncNotificationEvent.Completed), syncNotificationTarget(bundle.notification.metadata))
            val preview = buildSyncRunNotification(group, SyncNotificationEvent.PreviewRequired)!!.notification.displayMessage()
            assertTrue(modeLabel in preview)
            assertFalse("Archive B" in preview)
        }
    }

    @Test fun malformedRoutesCannotTurnNotificationActionsIntoApprovalOrExecution() {
        val metadata = buildSyncRunNotification(task(), SyncNotificationEvent.PendingDeletions, record(), 101)!!.notification.metadata
        for (change in listOf(mapOf("sync_task_id" to "-7"), mapOf("sync_run_id" to "0"), mapOf("sync_event" to "runNow"),
            mapOf(RequestNotificationFactory.META_REQUEST_ID to "SyncRun:other"))) {
            val malformed = metadata + change + mapOf(RequestNotificationFactory.META_REQUEST_KIND to RequestNotificationKind.DeviceShare.name,
                RequestNotificationFactory.META_DEVICE_ID to "device")
            assertNull(syncNotificationTarget(malformed))
            assertTrue(notificationActionsFor(malformed).isEmpty())
        }
    }
}
