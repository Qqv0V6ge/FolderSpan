package com.folderspan.ui.screen.sync

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.*
import androidx.compose.ui.test.v2.runComposeUiTest
import com.folderspan.notification.*
import com.folderspan.test.ChineseLocalizationTest
import com.folderspan.ui.notification.toRequestInfo
import kotlin.test.*

@OptIn(ExperimentalTestApi::class)
class SyncNotificationNavigationTest : ChineseLocalizationTest() {
    @Test fun confirmationRoutesOpenPreviewAndCompletedOrFailedRoutesOpenDetails() {
        assertEquals("SyncRunPreview:7:false", SyncNotificationTarget(7, 0, SyncNotificationEvent.PreviewRequired).screen().routeKey)
        assertEquals("SyncRunPreview:7:true", SyncNotificationTarget(7, 11, SyncNotificationEvent.PendingDeletions).screen().routeKey)
        for (event in listOf(SyncNotificationEvent.Completed, SyncNotificationEvent.Failed)) {
            assertEquals("SyncRunDetail:7", SyncNotificationTarget(7, 11, event).screen().routeKey)
        }
        assertNotEquals(SyncRunDetailScreen(7).routeKey, SyncRunDetailScreen(8).routeKey)
        val malformed = RequestNotificationFactory.buildCustomNotification("SyncRun", "task", "invalid",
            metadata = mapOf(RequestNotificationFactory.META_REQUEST_KIND to RequestNotificationKind.DeviceShare.name,
                RequestNotificationFactory.META_DEVICE_ID to "device"))
        assertNull(malformed.notification.toRequestInfo())
    }

    @Test fun viewButtonOnlyOpensThePageAndMalformedMetadataOffersNoAction() = runComposeUiTest {
        var opened = 0
        val bundle = RequestNotificationFactory.buildCustomNotification("SyncRun", "task", "pending",
            metadata = mapOf("sync_task_id" to "7", "sync_run_id" to "11", "sync_event" to "PendingDeletions"), requestId = "SyncRun:7")
        setContent { MaterialTheme { SyncNotificationActionRow(bundle.notification.metadata, onOpen = { opened++ }) } }
        onNodeWithTag("sync-notification-view").performClick()
        assertEquals(1, opened)
    }

    @Test fun invalidMetadataDoesNotShowViewButton() = runComposeUiTest {
        setContent { MaterialTheme { SyncNotificationActionRow(mapOf("sync_task_id" to "7"), onOpen = { error("invalid notification") }) } }
        onNodeWithTag("sync-notification-view").assertDoesNotExist()
    }
}
