package com.folderspan.ui.screen.sync

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.unit.dp
import com.folderspan.ui.state.main.*
import kotlin.test.Test
import kotlin.test.assertEquals
import strings.AppStrings

@OptIn(ExperimentalTestApi::class)
class StorageRunRecordUiTest {
    @Test fun equalDestinationPathsKeepTheirConnectionLabelsAndRetryOnlyTheFailedTarget() = runComposeUiTest {
        val nextcloud = StorageDestination(ref = "nextcloud", path = "/backup", networkEntryId = 1)
        val dropbox = StorageDestination(ref = "dropbox", path = "/backup", networkEntryId = 2)
        val record = SyncRunRecord(1, 1, "manual", 1, 2, SyncRunStatus.PartialSuccess, 2, 1, 1,
            targets = listOf(StorageTargetRun(nextcloud, 11, SyncRunStatus.Success, successCount = 1),
                StorageTargetRun(dropbox, 22, SyncRunStatus.Failure, failureCount = 1, uncertain = true)))
        var retried: Long? = null
        setContent {
            MaterialTheme {
                Column(Modifier.size(360.dp, 800.dp).verticalScroll(rememberScrollState())) {
                    SyncRunRecordCard(task = SyncTask(1, "backup", sourceType = SyncEndpointType.Local, sourcePath = "/source",
                        targetType = SyncEndpointType.Network, targetRef = "nextcloud", targetPath = "/backup"), record = record,
                        targetNames = mapOf(1L to "Nextcloud test", 2L to "Dropbox test"),
                        onRetry = {}, onTargetRetry = { retried = it }, onItemRetry = { _, _ -> }, onControl = {})
                }
            }
        }
        onNodeWithText("Nextcloud test", substring = true).assertExists()
        onNodeWithText("Dropbox test", substring = true).performScrollTo().assertExists()
        onAllNodesWithText(AppStrings.ui_storage_uncertain, substring = true).onFirst().assertExists()
        onAllNodesWithText(AppStrings.ui_retry_failed_items).onFirst().performScrollTo().performClick()
        runOnIdle { assertEquals(22L, retried) }
    }
}
