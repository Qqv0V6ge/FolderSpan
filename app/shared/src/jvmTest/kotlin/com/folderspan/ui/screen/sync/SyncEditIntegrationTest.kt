package com.folderspan.ui.screen.sync

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.asSkiaBitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.v2.runSkikoComposeUiTest
import com.folderspan.db.FolderSpanDatabase
import com.folderspan.data.main.network.Network
import com.folderspan.data.main.network.NetworkEntry
import com.folderspan.data.main.network.buildProtocolId
import com.folderspan.service.network.NetworkFileEntry
import com.folderspan.service.network.StorageCapabilities
import com.folderspan.service.network.StoragePath
import com.folderspan.service.network.StorageProvider
import com.folderspan.service.network.UnsupportedNetworkClient
import com.folderspan.ui.navigation.AppRoute
import com.folderspan.ui.navigation.BackStackAppNavigator
import com.folderspan.ui.navigation.LocalAppNavigator
import com.folderspan.ui.navigation.toAppRoute
import com.folderspan.ui.state.main.*
import com.folderspan.test.ChineseLocalizationTest
import kotlinx.coroutines.runBlocking
import org.jetbrains.skia.Image
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import org.koin.dsl.module
import strings.AppStrings
import java.lang.reflect.Proxy
import java.nio.file.Files
import java.nio.file.Path
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

@OptIn(ExperimentalTestApi::class)
class SyncEditIntegrationTest : ChineseLocalizationTest() {
    @Test
    fun backupTargetsAreSelectedManuallyAndLegacyRecommendationChangesRequireConfirmation() {
        val root = Files.createTempDirectory("sync-manual-targets")
        val store = TempSyncTaskStore(root.resolve("store").toString())
        val database = Proxy.newProxyInstance(FolderSpanDatabase::class.java.classLoader, arrayOf(FolderSpanDatabase::class.java)) {
                _, _, _ -> error("Network backup editing must not query the device database")
        } as FolderSpanDatabase
        val provider = object : StorageProvider by UnsupportedNetworkClient("test") {
            override val capabilities = StorageCapabilities.ReadWrite
            override suspend fun effectiveCapabilities() = Result.success(capabilities)
            override suspend fun list(path: String, requestId: String?, batchId: String?) =
                Result.success(if (path == "/") listOf(NetworkFileEntry("backup", "/backup", true, isSymbolicLinkKnown = true)) else emptyList())
            override suspend fun list(path: StoragePath, requestId: String?, batchId: String?) =
                list(path.path, requestId, batchId)
        }
        val entry = NetworkEntry(1, object : Network("Backup server", "/", "FTP", "test.invalid", "", "") {
            override val client = provider
        }, true)
        val destination = entry.storageDestination("/backup")
        val task = store.saveTask(SyncTask(1, "Photos", enabled = false,
            sourceType = SyncEndpointType.Local, sourcePath = Files.createDirectories(root.resolve("source")).toString(),
            targetType = SyncEndpointType.Network, targetRef = entry.network.buildProtocolId(), targetPath = destination.path,
            ownerDeviceId = "this-device", backup = true, destinations = listOf(destination),
            selectRecommended = true, preferredEntryIds = listOf(entry.id)))
        val sync = SyncState(store, deviceIdentity = { SyncDeviceIdentity("this-device", "This computer") })
        sync.tasks.add(task)
        val networks = NetworkState(database).also { it.entries.add(entry) }
        val screen = SyncEditScreen(task.id)
        val navigator = BackStackAppNavigator(mutableListOf(AppRoute.Home, screen.toAppRoute()))
        startKoin { modules(module { single { sync }; single { database }; single { DeviceState() }; single { networks } }) }
        try {
            runSkikoComposeUiTest(size = Size(900f, 3600f)) {
                setContent { MaterialTheme { CompositionLocalProvider(LocalAppNavigator provides navigator) { screen.Content() } } }
                onNodeWithText("推荐目标").assertDoesNotExist()
                onNodeWithText("每次运行选择排名最高的合格候选目标").assertDoesNotExist()
                onNodeWithText("设为首个目标").assertExists()
                val evidence = Path.of("build/target-selection-evidence")
                Files.createDirectories(evidence)
                Files.write(evidence.resolve("manual-backup-targets.png"),
                    Image.makeFromBitmap(onRoot().captureToImage().asSkiaBitmap()).encodeToData()!!.bytes)
                onNodeWithTag("sync-add-backup-target").performClick()
                onAllNodesWithText(entry.network.name).assertCountEquals(2)
                onNodeWithTag("sync-task-name").performClick()
                onNode(isPopup()).assertDoesNotExist()
                runOnIdle { assertEquals(task, store.loadTasks().single()) }
                onNodeWithText(AppStrings.ui_save, useUnmergedTree = true).performClick()
                waitUntil(timeoutMillis = 5_000) {
                    onAllNodesWithText(AppStrings.sync_definition_change_title).fetchSemanticsNodes().isNotEmpty()
                }
                onNodeWithText(AppStrings.sync_definition_change_title).assertIsDisplayed()
                onNodeWithText(AppStrings.ui_cancel).performClick()
                runOnIdle { assertTrue(store.loadTasks().single().selectRecommended) }
                onNodeWithText(AppStrings.ui_save, useUnmergedTree = true).performClick()
                waitUntil(timeoutMillis = 5_000) {
                    onAllNodesWithText(AppStrings.sync_definition_change_title).fetchSemanticsNodes().isNotEmpty()
                }
                onNode(hasText(AppStrings.ui_save) and hasAnyAncestor(isDialog()), useUnmergedTree = true).performClick()
                try {
                    waitUntil(timeoutMillis = 5_000) { navigator.routes.size == 1 }
                } catch (failure: ComposeTimeoutException) {
                    throw AssertionError("Saving manual backup targets failed: recommended=${store.loadTasks().single().selectRecommended}\n" +
                        onRoot().printToString(), failure)
                }
                runOnIdle {
                    val saved = store.loadTasks().single()
                    assertEquals(listOf(destination), saved.destinations)
                    assertFalse(saved.selectRecommended)
                    assertTrue(saved.preferredEntryIds.isEmpty())
                }
            }
        } finally {
            runBlocking { sync.stop() }
            stopKoin()
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun modeAndProtectionChangesRequireConfirmationAndKeepInputsWhenCancelled() {
        val root = Files.createTempDirectory("sync-mode-edit")
        val source = Files.createDirectories(root.resolve("source")).toString()
        val target = Files.createDirectories(root.resolve("target")).toString()
        val store = TempSyncTaskStore(root.resolve("store").toString())
        val task = store.saveTask(SyncTask(1, "Photos", sourceType = SyncEndpointType.Local, sourcePath = source,
            targetType = SyncEndpointType.Local, targetPath = target, ownerDeviceId = "this-device"))
        val sync = SyncState(store, deviceIdentity = { SyncDeviceIdentity("this-device", "This computer") })
        sync.tasks.add(task)
        val database = Proxy.newProxyInstance(FolderSpanDatabase::class.java.classLoader, arrayOf(FolderSpanDatabase::class.java)) {
                _, _, _ -> error("Local editing must not query the device database")
        } as FolderSpanDatabase
        val screen = SyncEditScreen(task.id)
        val route = screen.toAppRoute()
        val navigator = BackStackAppNavigator(mutableListOf(AppRoute.Home, route))
        startKoin { modules(module { single { sync }; single { database }; single { DeviceState() }; single { NetworkState(database) } }) }
        try {
            runSkikoComposeUiTest(size = Size(900f, 3600f)) {
                setContent { MaterialTheme { CompositionLocalProvider(LocalAppNavigator provides navigator) { screen.Content() } } }
                val content = onNode(hasScrollToIndexAction()).fetchSemanticsNode().boundsInRoot
                val taskName = onNodeWithTag("sync-task-name").fetchSemanticsNode().boundsInRoot
                for (tag in listOf("sync-mode", "sync-retry-count", "sync-timeout-seconds", "sync-parallelism")) {
                    val field = onNodeWithTag(tag).fetchSemanticsNode().boundsInRoot
                    assertTrue(abs(field.width - taskName.width) <= 1f,
                        "模式和执行参数应与任务名称等宽：tag=$tag, field=$field, taskName=$taskName")
                }
                for (tag in listOf("sync-include-rules", "sync-filter-rules")) {
                    val rules = onNodeWithTag(tag).fetchSemanticsNode().boundsInRoot
                    assertTrue(abs(rules.left - content.left) <= 1f && abs(rules.right - content.right) <= 1f,
                        "规则输入应占满网格内容宽度：tag=$tag, rules=$rules, content=$content")
                }
                onNodeWithTag("sync-mode").performClick()
                onNodeWithText(AppStrings.sync_mode_bidirectional).performClick()
                onNodeWithTag("sync-bidi-policy").performClick()
                onNodeWithText(AppStrings.sync_bidi_source).performClick()
                onNodeWithTag("sync-retention").performTextReplacement("45")
                onNodeWithTag("sync-delete-threshold").performTextReplacement("12")
                onNodeWithTag("sync-include-rules").performTextReplacement("*.jpg")
                onNodeWithTag("sync-event-device").performClick()
                onNodeWithTag("sync-event-network").performClick()
                onNodeWithTag("sync-require-wifi").assertDoesNotExist()
                onNodeWithTag("sync-notify-level").performClick()
                onNodeWithText(AppStrings.sync_notify_off).performClick()
                onNodeWithTag("sync-recycle").performClick()
                onNodeWithText(AppStrings.sync_disable_recycle_title).assertIsDisplayed()
                onNodeWithText(AppStrings.ui_cancel).performClick()
                onNodeWithTag("sync-recycle").assertIsOn()
                onNodeWithTag("sync-retention").assertTextContains("45")
                onNodeWithTag("sync-recycle").performClick()
                onNodeWithText(AppStrings.ui_confirm).performClick()
                onNodeWithTag("sync-recycle").assertIsOff()
                onNodeWithTag("sync-recycle").performClick()
                onNodeWithTag("sync-recycle").assertIsOn()
                onNodeWithTag("sync-schedule-Weekly").performClick()
                onNodeWithTag("sync-schedule-time").performTextReplacement("03:45")
                onNodeWithText(AppStrings.ui_save, useUnmergedTree = true).performClick()
                waitForIdle()
                runOnIdle { assertEquals(SyncScheduleType.Manual, store.loadTasks().single().scheduleType) }
                onAllNodesWithText(AppStrings.sync_definition_change_title).assertCountEquals(0)
                onNodeWithTag("sync-schedule-weekday-0").performClick()
                onNodeWithTag("sync-schedule-weekday-3").performClick()
                onNodeWithText(AppStrings.ui_save, useUnmergedTree = true).performClick()
                onNodeWithText(AppStrings.sync_definition_change_title).assertIsDisplayed()
                onNodeWithText(AppStrings.ui_cancel).performClick()
                onNodeWithTag("sync-delete-threshold").assertTextContains("12")
                runOnIdle { assertEquals(SyncMode.Update, store.loadTasks().single().mode) }
                onNodeWithText(AppStrings.ui_save, useUnmergedTree = true).performClick()
                onAllNodesWithText(AppStrings.ui_save, useUnmergedTree = true).onLast().performClick()
                waitUntil(timeoutMillis = 5_000) { navigator.routes.size == 1 }
                runOnIdle {
                    val saved = store.loadTasks().single()
                    assertEquals(SyncMode.Bidirectional, saved.mode)
                    assertEquals(SyncBidiConflictPolicy.Source, saved.bidiConflictPolicy)
                    assertEquals(45, saved.recycleRetentionDays)
                    assertEquals(12, saved.deleteThreshold)
                    assertEquals("*.jpg", saved.includeRaw)
                    assertEquals(SyncNotifyLevel.Off, saved.notifyLevel)
                    assertEquals(true, saved.triggerOnDeviceConnect)
                    assertEquals(true, saved.triggerOnNetworkAvailable)
                    assertEquals(SyncScheduleType.Weekly, saved.scheduleType)
                    assertEquals(225, saved.scheduleTimeMinutes)
                    assertEquals(1 or 8, saved.scheduleWeekdays)
                }
            }
            runSkikoComposeUiTest(size = Size(900f, 3600f)) {
                setContent { MaterialTheme { CompositionLocalProvider(LocalAppNavigator provides navigator) { SyncEditScreen(task.id).Content() } } }
                onNodeWithTag("sync-mode").assertTextContains(AppStrings.sync_mode_bidirectional)
                onNodeWithTag("sync-bidi-policy").assertTextContains(AppStrings.sync_bidi_source)
                onNodeWithTag("sync-retention").assertTextContains("45")
                onNodeWithTag("sync-delete-threshold").assertTextContains("12")
                onNodeWithTag("sync-include-rules").assertTextContains("*.jpg")
                onNodeWithTag("sync-notify-level").assertTextContains(AppStrings.sync_notify_off)
                onNodeWithTag("sync-event-device").assertIsOn()
                onNodeWithTag("sync-event-network").assertIsOn()
                onNodeWithTag("sync-schedule-time").assertTextContains("03:45")
                onNodeWithTag("sync-schedule-weekday-0").assertIsSelected()
                onNodeWithTag("sync-schedule-weekday-3").assertIsSelected()
            }
        } finally { stopKoin(); root.toFile().deleteRecursively() }
    }

    @Test
    fun reverseConfirmationPreservesEditedSettingsUntilConfirmedAndPersistsThem() {
        val root = Files.createTempDirectory("sync-edit")
        val source = Files.createDirectories(root.resolve("source")).toString()
        val target = Files.createDirectories(root.resolve("target")).toString()
        val store = TempSyncTaskStore(root.resolve("store").toString())
        val task = store.saveTask(SyncTask(
            1, "Documents", sourceType = SyncEndpointType.Local, sourcePath = source,
            targetType = SyncEndpointType.Local, targetPath = target,
            ownerDeviceId = "this-device", ownerDeviceName = "This computer",
        ))
        val reverse = store.saveTask(task.copy(id = 2, name = "Reverse", sourcePath = target, targetPath = source))
        val sync = SyncState(store, deviceIdentity = { SyncDeviceIdentity("this-device", "This computer") })
        sync.tasks.addAll(listOf(task, reverse))
        val database = Proxy.newProxyInstance(
            FolderSpanDatabase::class.java.classLoader, arrayOf(FolderSpanDatabase::class.java),
        ) { _, _, _ -> error("Local endpoint editing must not query the device database") } as FolderSpanDatabase
        val screen = SyncEditScreen(task.id)
        val route = screen.toAppRoute()
        val navigator = BackStackAppNavigator(mutableListOf(AppRoute.Home, route))
        startKoin {
            modules(module {
                single { sync }
                single { database }
                single { DeviceState() }
                single { NetworkState(database) }
            })
        }
        try {
            runSkikoComposeUiTest(size = Size(900f, 2400f)) {
                setContent {
                    MaterialTheme {
                        CompositionLocalProvider(LocalAppNavigator provides navigator) { screen.Content() }
                    }
                }
                onNodeWithTag("sync-retry-count").performTextReplacement("4")
                onNodeWithTag("sync-timeout-seconds").performTextReplacement("29")
                onNodeWithTag("sync-parallelism").performTextReplacement("3")
                onNodeWithText(AppStrings.ui_save, useUnmergedTree = true).performClick()
                waitForIdle()
                runOnIdle {
                    assertSame(route, navigator.currentRoute)
                    assertEquals(1, store.loadTasks().single { it.id == 1L }.retryCount)
                }
                onNodeWithTag("sync-timeout-seconds").performTextReplacement("180")
                onNodeWithText(AppStrings.ui_save, useUnmergedTree = true).performClick()
                waitUntil(timeoutMillis = 5_000) { onAllNodesWithText(AppStrings.sync_reverse_title).fetchSemanticsNodes().isNotEmpty() }
                onNodeWithText(AppStrings.ui_cancel).performClick()
                onNodeWithTag("sync-retry-count").assertTextContains("4")
                onNodeWithTag("sync-timeout-seconds").assertTextContains("180")
                onNodeWithTag("sync-parallelism").assertTextContains("3")
                runOnIdle {
                    assertSame(route, navigator.currentRoute)
                    assertEquals(1, store.loadTasks().single { it.id == 1L }.retryCount)
                }
                onNodeWithText(AppStrings.ui_save, useUnmergedTree = true).performClick()
                waitUntil(timeoutMillis = 5_000) { onAllNodesWithText(AppStrings.sync_reverse_title).fetchSemanticsNodes().isNotEmpty() }
                onNodeWithText(AppStrings.ui_confirm).performClick()
                waitUntil(timeoutMillis = 5_000) { navigator.routes.size == 1 }
                runOnIdle {
                    val saved = store.loadTasks().single { it.id == 1L }
                    assertEquals(4, saved.retryCount)
                    assertEquals(180, saved.timeoutSeconds)
                    assertEquals(3, saved.parallelism)
                }
            }
            runSkikoComposeUiTest(size = Size(900f, 2400f)) {
                val reopened = SyncEditScreen(task.id)
                setContent {
                    MaterialTheme {
                        CompositionLocalProvider(LocalAppNavigator provides navigator) { reopened.Content() }
                    }
                }
                onNodeWithTag("sync-retry-count").assertTextContains("4")
                onNodeWithTag("sync-timeout-seconds").assertTextContains("180")
                onNodeWithTag("sync-parallelism").assertTextContains("3")
            }
        } finally {
            stopKoin()
            root.toFile().deleteRecursively()
        }
    }
}
