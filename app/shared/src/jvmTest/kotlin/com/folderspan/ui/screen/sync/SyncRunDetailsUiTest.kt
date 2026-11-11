package com.folderspan.ui.screen.sync

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.asSkiaBitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.v2.runSkikoComposeUiTest
import com.folderspan.test.ChineseLocalizationTest
import com.folderspan.data.file.FileSimpleInfo
import com.folderspan.ui.state.main.*
import org.jetbrains.skia.Image
import java.nio.file.Files
import java.nio.file.Path
import strings.AppStrings
import kotlin.test.Test
import kotlin.test.assertEquals

@OptIn(ExperimentalTestApi::class)
class SyncRunDetailsUiTest : ChineseLocalizationTest() {
    private val location = SyncRecoveryLocation(SyncRecoveryEndpoint(SyncEndpointType.Local, "", "/archive"),
        "/archive/.folderspan-recycle/group-17/photos/holiday.jpg")
    private fun row(index: Long) = SyncRunDetailRow(index, SyncRunItem("photos/$index.jpg", index != 0L,
        if (index == 0L) "文件已保留，等待恢复" else "旧版本已移入回收目录", kind = SyncRunItemKind.Recycle,
        participant = "target-0", recoveryGroup = "group-17", recoveryLocations = listOf(location)))

    @Test fun loads100RowsThenNextPageWithoutReloadingTheFirstPage() = runSkikoComposeUiTest(size = Size(720f, 900f)) {
        val requests = mutableListOf<Pair<Long, Int>>()
        setContent { MaterialTheme {
            SyncRunDetailsDialog("结果明细", mapOf("target-0" to "照片归档"), loadPage = { offset, limit ->
                requests += offset to limit
                (offset until minOf(offset + limit, 103)).map(::row)
            }, onDismiss = {})
        } }
        waitForIdle()
        assertEquals(listOf(0L to 100), requests)
        onNodeWithText("photos/0.jpg").assertIsDisplayed()
        onNodeWithTag("sync-result-list").performScrollToNode(hasTestTag("sync-result-more"))
        onNodeWithTag("sync-result-more").performClick()
        waitForIdle()
        assertEquals(listOf(0L to 100, 100L to 100), requests)
        onNodeWithTag("sync-result-list").performScrollToNode(hasText("photos/102.jpg"))
        onNodeWithText("photos/102.jpg").assertIsDisplayed()
        onNodeWithTag("sync-result-more").assertDoesNotExist()
    }

    @Test fun failedPageCanBeRetriedWithoutDroppingExistingRows() = runSkikoComposeUiTest(size = Size(720f, 900f)) {
        var attempts = 0
        setContent { MaterialTheme {
            SyncRunDetailsDialog("结果明细", emptyMap(), loadPage = { _, _ ->
                attempts++
                if (attempts == 1) error("temporary read failure")
                listOf(row(0))
            }, onDismiss = {})
        } }
        waitForIdle()
        onNodeWithText("temporary read failure", substring = true).assertExists()
        onNodeWithText(AppStrings.ui_try_again).performClick()
        waitForIdle()
        onNodeWithText("photos/0.jpg").assertIsDisplayed()
        assertEquals(2, attempts)
    }

    @Test fun failedAppendRetainsLoadedRowsAndRetriesTheSameOffset() = runSkikoComposeUiTest(size = Size(720f, 900f)) {
        val requests = mutableListOf<Pair<Long, Int>>()
        setContent { MaterialTheme {
            SyncRunDetailsDialog("结果明细", emptyMap(), loadPage = { offset, limit ->
                requests += offset to limit
                if (requests.size == 2) error("append failed")
                (offset until minOf(offset + limit, 101)).map(::row)
            }, onDismiss = {})
        } }
        waitForIdle()
        onNodeWithTag("sync-result-list").performScrollToNode(hasTestTag("sync-result-more"))
        onNodeWithTag("sync-result-more").performClick()
        waitForIdle()
        onNodeWithTag("sync-result-list").performScrollToNode(hasText("append failed"))
        onNodeWithText("append failed").assertExists()
        onNodeWithTag("sync-result-more").performClick()
        waitForIdle()
        assertEquals(listOf(0L to 100, 100L to 100, 100L to 100), requests)
        onNodeWithTag("sync-result-list").performScrollToNode(hasText("photos/0.jpg"))
        onNodeWithText("photos/0.jpg").assertIsDisplayed()
    }

    @Test fun failedSourceInPagedResultsCanStillBeRetriedIndividually() = runSkikoComposeUiTest(size = Size(720f, 900f)) {
        val item = row(0).item.copy(source = FileSimpleInfo.pathFileSimpleInfo("/source/photo.jpg"))
        var retried: SyncRunItem? = null
        setContent { MaterialTheme {
            SyncRunDetailsDialog("结果明细", emptyMap(), loadPage = { _, _ -> listOf(SyncRunDetailRow(0, item)) },
                onDismiss = {}, onRetryItem = { retried = it })
        } }
        waitForIdle()
        onNodeWithText(AppStrings.ui_retry_failed_items).performClick()
        assertEquals(item, retried)
    }

    @Test fun oldGroupCardDoesNotComposeThousandsOfFailureRows() = runSkikoComposeUiTest(size = Size(720f, 900f)) {
        val task = SyncTask(1, "旧备份组", sourceType = SyncEndpointType.Local, sourcePath = "/source",
            targetType = SyncEndpointType.Local, targetPath = "/archive")
        val failures = (0 until 10_000).map { SyncRunItem("failed-$it.jpg", false, "failed", FileSimpleInfo.pathFileSimpleInfo("/source/$it.jpg")) }
        val target = StorageTargetRun(StorageDestination(type = SyncEndpointType.Local, ref = "", path = "/archive"), 17, status = SyncRunStatus.Failure, failureCount = failures.size, items = failures)
        val record = SyncRunRecord(1, 1, "manual", 1, 2, SyncRunStatus.Failure, 10_000, 0, 10_000, targets = listOf(target))
        setContent { MaterialTheme {
            SyncRunRecordCard(task, record, emptyMap(), onRetry = {}, onTargetRetry = {}, onItemRetry = { _, _ -> }, onControl = {})
        } }
        onNodeWithText("failed-9999.jpg", substring = true).assertDoesNotExist()
        onAllNodesWithText("failed-", substring = true).assertCountEquals(8)
    }

    @Test fun fullRecoveryPathsFitFiveWidthsAndTheShortWindow() {
        for ((width, height) in listOf(390 to 800, 720 to 900, 1024 to 768, 1366 to 900, 1700 to 900, 680 to 360)) {
            runSkikoComposeUiTest(size = Size(width.toFloat(), height.toFloat())) {
                setContent { MaterialTheme {
                    SyncRunDetailsDialog(AppStrings.sync_result_recovery, mapOf("target-0" to "照片归档"),
                        loadPage = { _, _ -> listOf(row(0), row(1)) }, onDismiss = {})
                } }
                waitForIdle()
                onNodeWithTag("sync-result-list").performScrollToNode(hasText(location.path))
                onAllNodesWithText(location.path).onFirst().assertIsDisplayed()
                val path = Path.of("build/sync-results-ui-evidence/details-${width}x$height.png")
                Files.createDirectories(path.parent)
                Files.write(path, Image.makeFromBitmap(onNode(isDialog()).captureToImage().asSkiaBitmap()).encodeToData()!!.bytes)
            }
        }
    }
}
