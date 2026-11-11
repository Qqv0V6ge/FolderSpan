package com.folderspan.ui.screen.file

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.graphics.toAwtImage
import javax.imageio.ImageIO
import java.io.File
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.test.*
import androidx.compose.ui.test.v2.runSkikoComposeUiTest
import com.folderspan.data.file.FileSimpleInfo
import com.folderspan.data.main.device.Device as ConnectedDevice
import com.folderspan.data.main.device.DeviceType
import com.folderspan.data.main.Local
import com.folderspan.ui.components.dialog.PathSelectorDialog
import com.folderspan.data.file.FileProtocol
import com.folderspan.db.*
import com.folderspan.service.*
import com.folderspan.test.ChineseLocalizationTest
import com.folderspan.ui.navigation.*
import com.folderspan.ui.state.file.*
import com.folderspan.ui.state.main.*
import com.folderspan.utils.DatabaseReady
import com.folderspan.utils.FileAccessPermission
import com.folderspan.utils.FileUtils
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.runBlocking
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import org.koin.dsl.module
import java.nio.file.Files
import kotlin.test.*
import strings.AppStrings

@OptIn(ExperimentalTestApi::class)
class CopyDestinationNavigationTest : ChineseLocalizationTest() {
    @Test fun directoryDialogKeepsTheRouteAndAddsFoldersBeforeReturningToSource() {
        fixture { fileState, tasks, root ->
            val request = fileState.createCopyDestinationRequest(listOf(runBlocking { FileUtils.getFile(FileAccessPermission.Allowed, root.resolve("input.txt").toString()).getOrThrow() }))
            val endpoint = request.discovery.loads.value.single { it.endpoint.endpoint.protocol == FileProtocol.Local }.endpoint
            val destination = runBlocking { request.resolveDirectory(endpoint, FileUtils.getFile(FileAccessPermission.Allowed, root.resolve("one").toString()).getOrThrow()) }
            request.state.select(destination)
            val originalControls = request.state.controls.value
            val stack = mutableStateListOf<AppRoute>(object : AppScreenRoute { @Composable override fun Content() { Text("Source page") } }.toAppRoute())
            val navigator = BackStackAppNavigator(stack)
            navigator.push(CopyDestinationScreen(request))
            runSkikoComposeUiTest(size = Size(1000f, 800f)) {
                setContent { MaterialTheme { CompositionLocalProvider(LocalAppNavigator provides navigator) {
                    (stack.last() as? AppRoute.Payload)?.let(AppRoutePayloadStore::resolve)?.Content()
                } } }
                onNodeWithTag("copy-add-directory:local").performClick()
                onNodeWithText(AppStrings.ui_select_path).assertIsDisplayed()
                onNodeWithText("选择其他本机目录").assertDoesNotExist()
                runOnIdle { assertEquals(2, stack.size) }
                onNodeWithText(AppStrings.ui_cancel).performClick()
                runOnIdle {
                    assertEquals(originalControls, request.state.controls.value)
                    assertEquals(1, request.state.selected.value.size)
                    assertTrue(tasks.tasks.isEmpty())
                }
                onNodeWithTag("copy-add-directory:local").performClick()
                // 路径面包屑会缩略较长的临时目录名。
                val parentBreadcrumb = hasText(root.fileName.toString().take(10), substring = true) and
                    hasAnyAncestor(hasTestTag("full-size-file-selector-dialog-content"))
                waitUntilAtLeastOneExists(parentBreadcrumb, timeoutMillis = 10_000)
                onNode(parentBreadcrumb).performScrollTo().performClick()
                val extraEntry = "file-selector-entry:${root.resolve("two")}"
                waitUntilAtLeastOneExists(hasTestTag(extraEntry), timeoutMillis = 10_000)
                onNodeWithText("input.txt").assertDoesNotExist()
                onNode(hasContentDescription(AppStrings.ui_select_file) and hasAnyAncestor(hasTestTag(extraEntry))).performClick()
                val thirdEntry = "file-selector-entry:${root.resolve("three")}"
                onNode(hasContentDescription(AppStrings.ui_select_file) and hasAnyAncestor(hasTestTag(thirdEntry))).performClick()
                onNode(hasContentDescription(AppStrings.ui_deselect) and hasAnyAncestor(hasTestTag(extraEntry))).assertIsDisplayed()
                val image = File("build/reports/copy-destination/add-folder.png")
                image.parentFile.mkdirs()
                ImageIO.write(onAllNodes(isRoot()).onLast().captureToImage().toAwtImage(), "png", image)
                onNodeWithText(AppStrings.ui_ok).performClick()
                waitUntil(timeoutMillis = 10_000) { request.state.selected.value.size == 3 }
                runOnIdle { assertEquals(2, stack.size); assertTrue(tasks.tasks.isEmpty()) }
                onNodeWithTag("copy-remove-directory:local:${root.resolve("two")}").performClick()
                onNodeWithTag("copy-remove-directory:local:${root.resolve("three")}").performClick()
                onNodeWithTag("copy-target:local:${root.resolve("two")}").assertDoesNotExist()
                onNodeWithTag("copy-target:local:${root.resolve("three")}").assertDoesNotExist()
                runOnIdle {
                    assertEquals(1, request.state.selected.value.size)
                    assertTrue(Files.isDirectory(root.resolve("two")))
                    assertTrue(Files.isDirectory(root.resolve("three")))
                }
                onNodeWithTag("copy-select-directory:local:${destination.directory.location.path}").performScrollTo().performClick()
                runOnIdle { assertTrue(request.state.selected.value.isEmpty()) }
                onNodeWithTag("copy-select-directory:local:${destination.directory.location.path}").assertIsDisplayed().performClick()
                onNodeWithTag("copy-submit").performClick()
                waitUntil(timeoutMillis = 20_000) { stack.size == 1 && Files.exists(root.resolve("one/input.txt")) }
                onNodeWithText("Source page").assertIsDisplayed()
                assertEquals("source content", Files.readString(root.resolve("one/input.txt")))
            }
            AppRoutePayloadStore.release(navigator.routes)
        }
    }

    @Test fun invalidBatchDirectorySelectionKeepsEveryPreviousDestination() {
        fixture { fileState, tasks, root ->
            val request = fileState.createCopyDestinationRequest(listOf(runBlocking {
                FileUtils.getFile(FileAccessPermission.Allowed, root.resolve("input.txt").toString()).getOrThrow()
            }))
            val endpoint = request.discovery.loads.value.single { it.endpoint.endpoint.protocol == FileProtocol.Local }.endpoint
            val destination = runBlocking { request.resolveDirectory(endpoint,
                FileUtils.getFile(FileAccessPermission.Allowed, root.resolve("one").toString()).getOrThrow()) }
            request.state.select(destination)
            val stack = mutableStateListOf<AppRoute>(object : AppScreenRoute { @Composable override fun Content() { Text("Source page") } }.toAppRoute())
            val navigator = BackStackAppNavigator(stack)
            navigator.push(CopyDestinationScreen(request))
            runSkikoComposeUiTest(size = Size(1000f, 800f)) {
                setContent { MaterialTheme { CompositionLocalProvider(LocalAppNavigator provides navigator) {
                    (stack.last() as AppRoute.Payload).let(AppRoutePayloadStore::resolve)?.Content()
                } } }
                onNodeWithTag("copy-add-directory:local").performClick()
                val parentBreadcrumb = hasText(root.fileName.toString().take(10), substring = true) and
                    hasAnyAncestor(hasTestTag("full-size-file-selector-dialog-content"))
                waitUntilAtLeastOneExists(parentBreadcrumb, timeoutMillis = 10_000)
                onNode(parentBreadcrumb).performScrollTo().performClick()
                for (name in listOf("two", "three")) {
                    val entry = hasTestTag("file-selector-entry:${root.resolve(name)}")
                    waitUntilAtLeastOneExists(entry, timeoutMillis = 10_000)
                    onNode(hasContentDescription(AppStrings.ui_select_file) and hasAnyAncestor(entry)).performClick()
                }
                runOnIdle { Files.delete(root.resolve("three")) }
                onNodeWithText(AppStrings.ui_ok).performClick()
                waitUntil(timeoutMillis = 10_000) {
                    !onNodeWithTag("copy-submit").fetchSemanticsNode().config.contains(SemanticsProperties.Disabled)
                }
                runOnIdle { assertEquals(listOf(destination), request.state.selected.value); assertTrue(tasks.tasks.isEmpty()); assertEquals(2, stack.size) }
                onNodeWithTag("copy-target:local:${root.resolve("two")}").assertDoesNotExist()
                onNodeWithTag("copy-target:local:${root.resolve("three")}").assertDoesNotExist()
            }
            AppRoutePayloadStore.release(navigator.routes)
        }
    }

    @Test fun deviceChangesUpdateCardsAndDiscardUnselectedCachedLocations() {
        fixture { fileState, _, root ->
            val device = ConnectedDevice("live-device", "Live device", "/", mutableMapOf(), DeviceType.JVM, "fixture-token")
            fileState.deviceState.devices.add(device)
            val request = fileState.createCopyDestinationRequest(listOf(runBlocking {
                FileUtils.getFile(FileAccessPermission.Allowed, root.resolve("input.txt").toString()).getOrThrow()
            }))
            val endpoint = request.discovery.loads.value.single { it.endpoint.endpoint.sourceId == device.id }.endpoint
            val target = CopyDestination(CopyFileSnapshot(endpoint,
                FileSimpleInfo.pathFileSimpleInfo("/saved").copy(protocol = FileProtocol.Device, protocolId = device.id)), "Saved", device.name)
            request.state.select(target)
            val stack = mutableStateListOf<AppRoute>(object : AppScreenRoute { @Composable override fun Content() { Text("Source page") } }.toAppRoute())
            val navigator = BackStackAppNavigator(stack)
            navigator.push(CopyDestinationScreen(request))
            runSkikoComposeUiTest(size = Size(1000f, 800f)) {
                setContent { MaterialTheme { CompositionLocalProvider(LocalAppNavigator provides navigator) {
                    (stack.last() as AppRoute.Payload).let(AppRoutePayloadStore::resolve)?.Content()
                } } }
                val cardTag = "copy-resource:${endpoint.connectionBinding}"
                onNodeWithTag(cardTag).assertIsDisplayed()
                runOnIdle { fileState.deviceState.devices.clear() }
                waitUntil(timeoutMillis = 10_000) { request.discovery.loads.value.none { it.endpoint == endpoint } && request.state.selected.value.single().unavailableReason != null }
                onNodeWithTag(cardTag).assertIsDisplayed()
                onNodeWithTag("copy-submit").assertIsNotEnabled()
                onNodeWithTag("copy-resource-select:${endpoint.connectionBinding}").performClick()
                onNodeWithTag(cardTag).assertDoesNotExist()
                runOnIdle { fileState.deviceState.devices.add(device.copy(id = "new-device", name = "New live device")) }
                waitUntilAtLeastOneExists(hasText("New live device"), timeoutMillis = 10_000)
                onNodeWithText("New live device").assertIsDisplayed()
                runOnIdle { fileState.deviceState.devices.clear() }
                waitUntilDoesNotExist(hasText("New live device"), timeoutMillis = 10_000)
                runOnIdle { assertTrue(request.state.selected.value.isEmpty()) }
            }
            AppRoutePayloadStore.release(navigator.routes)
        }
    }

    @Test fun removingTheDefaultLocationKeepsTheResourceAndItsAddAction() {
        fixture { fileState, tasks, root ->
            val request = fileState.createCopyDestinationRequest(listOf(runBlocking {
                FileUtils.getFile(FileAccessPermission.Allowed, root.resolve("input.txt").toString()).getOrThrow()
            }))
            val stack = mutableStateListOf<AppRoute>(object : AppScreenRoute { @Composable override fun Content() { Text("Source page") } }.toAppRoute())
            val navigator = BackStackAppNavigator(stack)
            navigator.push(CopyDestinationScreen(request))
            runSkikoComposeUiTest(size = Size(1000f, 800f)) {
                setContent { MaterialTheme { CompositionLocalProvider(LocalAppNavigator provides navigator) {
                    (stack.last() as AppRoute.Payload).let(AppRoutePayloadStore::resolve)?.Content()
                } } }
                waitUntil(timeoutMillis = 10_000) { request.discovery.loads.value.any { it.destinations.isNotEmpty() } }
                val home = request.discovery.loads.value.single { it.endpoint.endpoint.protocol == FileProtocol.Local }.destinations.single()
                onNodeWithTag("copy-resource-select:local").performClick()
                runOnIdle { assertEquals(1, request.state.selected.value.size) }
                onNodeWithTag("copy-remove-directory:local:${home.directory.location.path}").performClick()
                onNodeWithTag("copy-target:local:${home.directory.location.path}").assertDoesNotExist()
                onNodeWithTag("copy-resource:local").assertIsDisplayed()
                onNodeWithTag("copy-submit").assertDoesNotExist()
                onNodeWithTag("copy-add-directory:local").assertIsEnabled().performClick()
                onNodeWithText(AppStrings.ui_select_path).assertIsDisplayed()
                onNodeWithText(AppStrings.ui_cancel).performClick()
                onNodeWithTag("copy-target:local:${home.directory.location.path}").assertDoesNotExist()
                runOnIdle { assertTrue(request.state.selected.value.isEmpty()); assertTrue(tasks.tasks.isEmpty()); assertTrue(Files.isDirectory(java.nio.file.Path.of(home.directory.location.path))) }
                onNodeWithTag("copy-add-directory:local").performClick()
                onNodeWithText(AppStrings.ui_ok).assertIsEnabled().performClick()
                waitUntil(timeoutMillis = 10_000) { request.state.selected.value.size == 1 }
                onNodeWithTag("copy-target:local:${home.directory.location.path}").assertIsDisplayed()
                onNodeWithTag("copy-submit").assertIsEnabled()
                runOnIdle { assertTrue(request.state.selected.value.single().directory.sameLocation(home.directory)) }
            }
            AppRoutePayloadStore.release(navigator.routes)
        }
    }

    @Test fun existingPathDialogCallersStillSelectOneDirectory() {
        fixture { _, tasks, root ->
            var confirmed: FileSimpleInfo? = null
            var showing by mutableStateOf(true)
            runSkikoComposeUiTest(size = Size(1000f, 800f)) {
                setContent { MaterialTheme {
                    if (showing) PathSelectorDialog(Local(), root.toString(), onDismiss = { showing = false },
                        onConfirm = { confirmed = it; showing = false })
                } }
                for (name in listOf("two", "three")) {
                    val entry = hasTestTag("file-selector-entry:${root.resolve(name)}")
                    waitUntilAtLeastOneExists(entry, timeoutMillis = 10_000)
                    onNode(hasContentDescription(AppStrings.ui_select_file) and hasAnyAncestor(entry)).performClick()
                }
                onNode(hasContentDescription(AppStrings.ui_select_file) and hasAnyAncestor(hasTestTag("file-selector-entry:${root.resolve("two")}"))).assertIsDisplayed()
                onNodeWithText(AppStrings.ui_ok).performClick()
                runOnIdle { assertEquals(root.resolve("three").toString(), confirmed?.path); assertTrue(tasks.tasks.isEmpty()) }
            }
        }
    }

    @Test fun returningWithoutSubmittingClosesOnlyTheCurrentDraft() {
        fixture { fileState, tasks, root ->
            val sources = listOf(runBlocking { FileUtils.getFile(FileAccessPermission.Allowed, root.resolve("input.txt").toString()).getOrThrow() })
            val request = fileState.createCopyDestinationRequest(sources)
            val other = fileState.createCopyDestinationRequest(sources)
            val stack = mutableStateListOf<AppRoute>(object : AppScreenRoute { @Composable override fun Content() { Text("Source page") } }.toAppRoute())
            val navigator = BackStackAppNavigator(stack)
            navigator.push(CopyDestinationScreen(request))
            runSkikoComposeUiTest(size = Size(1000f, 800f)) {
                setContent { MaterialTheme { CompositionLocalProvider(LocalAppNavigator provides navigator) {
                    (stack.last() as? AppRoute.Payload)?.let(AppRoutePayloadStore::resolve)?.Content()
                } } }
                onNodeWithContentDescription(AppStrings.copy_destination_back).performClick()
                onNodeWithText("Source page").assertIsDisplayed()
                runOnIdle { assertTrue(tasks.tasks.isEmpty()) }
            }
            assertFailsWith<IllegalStateException> { runBlocking { request.submit() } }
            assertTrue(other.state.selected.value.isEmpty())
            other.close()
            AppRoutePayloadStore.release(navigator.routes)
        }
    }

    @Test fun sourceDialogAppendsFilesAndFoldersAndRemovalChangesTheRealCopy() {
        fixture { fileState, tasks, root ->
            val added = Files.writeString(root.resolve("added.txt"), "added content")
            val vanishing = Files.writeString(root.resolve("vanishing.txt"), "temporary")
            val tree = Files.createDirectory(root.resolve("source-tree"))
            Files.writeString(tree.resolve("child.txt"), "child content")
            val request = fileState.createCopyDestinationRequest(listOf(runBlocking {
                FileUtils.getFile(FileAccessPermission.Allowed, root.resolve("input.txt").toString()).getOrThrow()
            }))
            val endpoint = request.state.sources.single().endpoint
            val destination = runBlocking { request.resolveDirectory(endpoint,
                FileUtils.getFile(FileAccessPermission.Allowed, root.resolve("one").toString()).getOrThrow()) }
            request.state.select(destination)
            val stack = mutableStateListOf<AppRoute>(object : AppScreenRoute {
                @Composable override fun Content() { Text("Source page") }
            }.toAppRoute())
            val navigator = BackStackAppNavigator(stack)
            navigator.push(CopyDestinationScreen(request))
            runSkikoComposeUiTest(size = Size(1000f, 900f)) {
                setContent { MaterialTheme { CompositionLocalProvider(LocalAppNavigator provides navigator) {
                    (stack.last() as? AppRoute.Payload)?.let(AppRoutePayloadStore::resolve)?.Content()
                } } }
                onNodeWithTag("copy-view-sources").performClick()
                onNodeWithTag("copy-add-sources").performClick()
                onNodeWithText(AppStrings.ui_cancel).performClick()
                runOnIdle { assertEquals(1, request.state.sources.size); assertEquals(2, stack.size) }
                onNodeWithTag("copy-add-sources").performClick()
                val vanishedEntry = hasTestTag("file-selector-entry:$vanishing")
                waitUntilAtLeastOneExists(vanishedEntry, timeoutMillis = 10_000)
                onNode(hasContentDescription(AppStrings.ui_select_file) and hasAnyAncestor(vanishedEntry)).performClick()
                runOnIdle { Files.delete(vanishing) }
                onNodeWithTag("copy-confirm-sources").performClick()
                waitUntilAtLeastOneExists(hasTestTag("copy-source-error"), timeoutMillis = 10_000)
                onNodeWithTag("copy-source-error").assertIsDisplayed()
                runOnIdle { assertEquals(1, request.state.sources.size); assertTrue(tasks.tasks.isEmpty()) }
                onNodeWithTag("copy-add-sources").performClick()
                listOf(added, tree, root.resolve("input.txt")).forEach { path ->
                    val entry = hasTestTag("file-selector-entry:$path")
                    waitUntilAtLeastOneExists(entry, timeoutMillis = 10_000)
                    onNode(hasContentDescription(AppStrings.ui_select_file) and hasAnyAncestor(entry)).performClick()
                }
                onNodeWithTag("copy-confirm-sources").performClick()
                waitUntil(timeoutMillis = 10_000) { request.state.sources.size == 3 }
                onNodeWithTag("copy-source-sheet").assertIsDisplayed()
                onNodeWithTag("copy-remove-source:local:${root.resolve("input.txt")}").performClick()
                waitUntil(timeoutMillis = 10_000) { request.state.sources.size == 2 }
                onNodeWithText(AppStrings.copy_destination_sources.format(count = "2")).assertIsDisplayed()
                runOnIdle { assertTrue(Files.exists(root.resolve("input.txt"))); assertTrue(tasks.tasks.isEmpty()) }
                onNodeWithTag("copy-close-sources").performClick()
                onNodeWithTag("copy-submit").performClick()
                waitUntil(timeoutMillis = 20_000) { stack.size == 1 && Files.exists(root.resolve("one/source-tree/child.txt")) }
                assertEquals("added content", Files.readString(root.resolve("one/added.txt")))
                assertEquals("child content", Files.readString(root.resolve("one/source-tree/child.txt")))
                assertFalse(Files.exists(root.resolve("one/input.txt")))
            }
            AppRoutePayloadStore.release(navigator.routes)
        }
    }

    private fun fixture(block: (FileState, TaskState, java.nio.file.Path) -> Unit) {
        val root = Files.createTempDirectory("copy-navigation")
        Files.writeString(root.resolve("input.txt"), "source content")
        Files.createDirectory(root.resolve("one"))
        Files.createDirectory(root.resolve("two"))
        Files.createDirectory(root.resolve("three"))
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        FolderSpanDatabase.Schema.create(driver)
        DatabaseReady.markReady()
        val database = FolderSpanDatabase(driver,
            DeviceAdapter = Device.Adapter(typeAdapter = driverAdapter),
            DeviceConnectAdapter = DeviceConnect.Adapter(connectionTypeAdapter = driverConnectTypeAdapter, categoryAdapter = driverCategoryAdapter),
            FileBookmarkAdapter = FileBookmark.Adapter(typeAdapter = fileBookmarkAdapter, protocolAdapter = fileProtocolAdapter),
            FileFavoriteAdapter = FileFavorite.Adapter(protocolAdapter = fileProtocolAdapter),
            FileRecentAdapter = FileRecent.Adapter(protocolAdapter = fileProtocolAdapter),
            FileFilterAdapter = FileFilter.Adapter(typeAdapter = fileFilterType, extensionsAdapter = listOfStringsAdapter),
            FilePathPreferenceAdapter = FilePathPreference.Adapter(protocolAdapter = fileProtocolAdapter, sortAdapter = fileFilterSortAdapter, ignoreFilesAdapter = listOfStringsAdapter),
            DeviceReceiveShareAdapter = DeviceReceiveShare.Adapter(connectionTypeAdapter = driverConnectTypeAdapter))
        val tasks = TaskState(runtimeStore = object : TaskRuntimePersistenceStore by TempTaskRuntimePersistenceStore() {
            override fun loadTaskSnapshots(): List<Task> = emptyList()
        }, cleanupStore = TempTaskCleanupStore(root.resolve("cleanup").toString()))
        stopKoin()
        val application = startKoin { modules(module {
            single { database }; single { tasks }; single { NetworkState(database) }; single { DeviceState() }; single { MainState() }; single { FileRecentState() }; single { NotificationState() }
            single { FileState().also { it.mainScope.coroutineContext.cancelChildren() } }
        }) }
        try { block(application.koin.get(), tasks, root) }
        finally { application.koin.get<FileState>().mainScope.cancel(); runBlocking { tasks.stop() }; stopKoin(); driver.close(); root.toFile().deleteRecursively() }
    }
}
