package com.folderspan.ui.state.file

import com.folderspan.data.file.FileProtocol
import com.folderspan.data.file.FileSimpleInfo
import com.folderspan.data.main.Local
import com.folderspan.data.main.share.SYSTEM_SHARE_DESK_ID
import com.folderspan.data.StatusEnum
import com.folderspan.ui.state.main.Task
import com.folderspan.ui.state.main.TaskState
import com.folderspan.ui.state.main.TaskType
import com.folderspan.ui.state.main.TestTaskRuntimePersistenceStore
import com.folderspan.ui.state.main.TASK_SESSION_TITLE_VALUE_KEY
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withContext
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import strings.AppStrings

@OptIn(ExperimentalCoroutinesApi::class)
class FileDropCopyTest {
    private val endpoint = FileDropEndpoint(FileDropTargetKind.Local)
    private val destination = FileDropDestination(endpoint, "/target", Local())
    private val target = ExternalFileImportTarget(Local(), file("/target", directory = true), "/target")

    @AfterTest
    fun cleanup() {
        FileDropSessionRegistry.end()
        ShareListDropResourceRegistry.releaseAll()
        ClipboardUrlShareFiles.clear()
        ExternalFileResourceLeaseRegistry.clearForTest()
    }

    @Test
    fun selectedItemDragsEntireSelectionAndPreservesEndpointIdentities() {
        val local = file("/same.txt")
        val device = file("/same.txt", protocol = FileProtocol.Device, protocolId = "device-one")
        val selection = mutableListOf(local, device)
        val snapshot = snapshotFileDragSelection(device, selection)
        selection.clear()
        device.path = "/changed.txt"
        device.protocolId = "device-two"
        assertEquals(2, snapshot.size)
        assertEquals("/same.txt", snapshot[1].path)
        assertEquals("device-one", snapshot[1].protocolId)
    }

    @Test
    fun unselectedItemNeverIncludesSelectionFromAnotherEndpoint() {
        val dragged = file("/same.txt", protocol = FileProtocol.Share, protocolId = "read-only-share")
        val snapshot = snapshotFileDragSelection(dragged, listOf(file("/same.txt")))
        assertEquals(listOf(dragged), snapshot)
        assertEquals(FileProtocol.Share, snapshot.single().protocol)
    }

    @Test
    fun dragPreviewSnapshotsGrabbedItemWithoutReorderingSelection() {
        val first = file("/one.txt")
        val dragged = file("/two.txt", protocol = FileProtocol.Device, protocolId = "device-one")
        val selection = listOf(first, dragged)
        FileDropSessionRegistry.startInternal(selection, 10f, 20f, previewFile = dragged, isTouch = true)
        dragged.path = "/changed.txt"
        val session = assertNotNull(FileDropSessionRegistry.session.value)
        assertEquals(listOf("/one.txt", "/two.txt"), session.files.map { it.path })
        assertEquals("/two.txt", session.previewFile?.path)
        assertEquals("device-one", session.previewFile?.protocolId)
        assertTrue(session.isTouch)
        FileDropSessionRegistry.move(30f, 40f)
        assertEquals(session.previewFile, FileDropSessionRegistry.session.value?.previewFile)
        FileDropSessionRegistry.moveExternal(50f, 60f)
        assertNull(FileDropSessionRegistry.session.value?.previewFile)
        assertFalse(FileDropSessionRegistry.session.value!!.isTouch)
    }

    @Test
    fun localTargetWorksWithoutDevicesAndIsCapturedBeforeSessionEnds() {
        val owner = Any()
        FileDropSessionRegistry.moveExternal(15f, 15f)
        FileDropSessionRegistry.register(owner, endpoint, FileDropBounds(0f, 0f, 100f, 40f))
        assertEquals(endpoint, FileDropSessionRegistry.session.value?.hoveredEndpoint)
        val captured = FileDropSessionRegistry.captureEndpoint()
        FileDropSessionRegistry.end()
        FileDropSessionRegistry.unregister(owner)
        assertNull(FileDropSessionRegistry.session.value)
        assertEquals(endpoint, captured)
    }

    @Test
    fun headingsActionsAndWhitespaceDoNotBecomeDropTargets() {
        val owner = Any()
        FileDropSessionRegistry.register(owner, endpoint, FileDropBounds(0f, 40f, 80f, 80f))
        FileDropSessionRegistry.moveExternal(20f, 20f)
        assertNull(FileDropSessionRegistry.captureEndpoint())
        FileDropSessionRegistry.moveExternal(90f, 50f)
        assertNull(FileDropSessionRegistry.captureEndpoint())
        FileDropSessionRegistry.moveExternal(20f, 90f)
        assertNull(FileDropSessionRegistry.captureEndpoint())
        FileDropSessionRegistry.unregister(owner)
    }

    @Test
    fun samePathAndDescendantAreRejectedOnlyOnTheSameEndpoint() {
        val source = file("/source/folder", directory = true, protocol = FileProtocol.Device, protocolId = "one")
        assertTrue(fileDropTargetsSource(source, file("/source", true, FileProtocol.Device, "one"), "/"))
        assertTrue(fileDropTargetsSource(source, file("/source/folder/child", true, FileProtocol.Device, "one"), "/"))
        assertFalse(fileDropTargetsSource(source, file("/source/folder/child", true, FileProtocol.Device, "two"), "/"))
        assertFalse(fileDropTargetsSource(source, file("/source/folder/child", true), "/"))
    }

    @Test
    fun localSystemShareCannotOverwriteItsOwnSource() {
        val source = file("/source/folder", true, FileProtocol.Share, SYSTEM_SHARE_DESK_ID)
        assertTrue(fileDropTargetsSource(source, file("/source", true), "/"))
        assertTrue(fileDropTargetsSource(source, file("/source/folder/nested", true), "/"))
        assertFalse(fileDropTargetsSource(source, file("/other", true), "/"))
    }

    @Test
    fun windowsSelfPathGuardIgnoresCaseAndNormalizesSeparators() {
        val source = file("C:\\Data\\Folder", true).copy(name = "Folder")
        assertTrue(fileDropTargetsSource(source, file("c:/data", true), "\\"))
        assertTrue(fileDropTargetsSource(source, file("C:/DATA/FOLDER/child", true), "\\"))
        assertFalse(fileDropTargetsSource(source, file("C:/Data/Folder-two", true), "\\"))
    }

    @Test
    fun rememberedDirectoryIsPreferredAndFallbackIsLazy() = runTest {
        var fallbackReads = 0
        val directory = resolveFileDropDirectory("/remembered", {
            fallbackReads++
            "/default"
        }) { path -> file(path, directory = true) }
        assertEquals("/remembered", directory?.path)
        assertEquals(0, fallbackReads)
    }

    @Test
    fun missingOrUnwritableRememberedDirectoryFallsBackToDefault() = runTest {
        val validated = mutableListOf<String>()
        val directory = resolveFileDropDirectory("/not-writable", { "/" }) { path ->
            validated += path
            if (path == "/") file(path, directory = true) else null
        }
        assertEquals("/", directory?.path)
        assertEquals(listOf("/not-writable", "/"), validated)
        assertNull(resolveFileDropDirectory(null, { "/" }) { null })
    }

    @Test
    fun switchingPagesAndSelectionWhileWaitingDoesNotRetargetRequest() = runTest {
        val connection = CompletableDeferred<Unit>()
        val selected = mutableListOf(file("/source.txt", protocol = FileProtocol.Network, protocolId = "network-one"))
        val snapshot = snapshotFileDragSelection(selected.single(), selected)
        var capturedPath = ""
        var copiedFiles = emptyList<FileSimpleInfo>()
        var copies = 0
        val coordinator = FileDropCopyCoordinator(this, { requested ->
            connection.await()
            capturedPath = requested.rememberedPath.orEmpty()
            target
        }, {}, { _, batch, _ ->
            copies++
            copiedFiles = batch.files
            listOf(1L)
        }, testTaskState())
        coordinator.submit(destination) { buildPreparedExternalFileBatch(snapshot) }
        runCurrent()
        assertEquals(FileDropCopyStatus.WaitingForTarget, coordinator.requests.value.single().status)
        selected.clear()
        connection.complete(Unit)
        advanceUntilIdle()
        assertEquals("/target", capturedPath)
        assertEquals("network-one", copiedFiles.single().protocolId)
        assertEquals(1, copies)
        assertTrue(coordinator.requests.value.isEmpty())
    }

    @Test
    fun failedConnectionRetainsSourcesUntilExplicitRetryAndDoesNotLoop() = runTest {
        var preparations = 0
        var connections = 0
        var copies = 0
        val coordinator = FileDropCopyCoordinator(this, {
            connections++
            if (connections == 1) error("connection rejected")
            target
        }, {}, { _, _, _ -> copies++; listOf(2L) }, testTaskState())
        val id = coordinator.submit(destination) { preparations++; buildPreparedExternalFileBatch(listOf(file("/source.txt"))) }
        advanceUntilIdle()
        assertEquals(FileDropCopyStatus.Failed, coordinator.requests.value.single().status)
        assertEquals("connection rejected", coordinator.requests.value.single().error)
        assertEquals(1, connections)
        coordinator.retry(id)
        coordinator.retry(id)
        advanceUntilIdle()
        assertEquals(1, preparations)
        assertEquals(2, connections)
        assertEquals(1, copies)
    }

    @Test
    fun timeoutIsRetryableInsteadOfLeavingAStuckWaitingRequest() = runTest {
        val coordinator = FileDropCopyCoordinator(this, {
            withTimeout(1L) { awaitCancellation() }
        }, {}, { _, _, _ -> error("must not enqueue") }, testTaskState())
        coordinator.submit(destination) { buildPreparedExternalFileBatch(listOf(file("/source.txt"))) }
        advanceUntilIdle()
        assertEquals(FileDropCopyStatus.Failed, coordinator.requests.value.single().status)
        coordinator.dispose()
    }

    @Test
    fun invalidSourceIsNotSubstitutedAndNeverEnqueued() = runTest {
        var enqueued = false
        val coordinator = FileDropCopyCoordinator(this, { target }, { error("source disconnected") }, { _, _, _ ->
            enqueued = true
            listOf(3L)
        }, testTaskState())
        val source = file("/source.txt", protocol = FileProtocol.Device, protocolId = "original-device")
        coordinator.submit(destination) { buildPreparedExternalFileBatch(listOf(source)) }
        advanceUntilIdle()
        assertFalse(enqueued)
        assertEquals("original-device", coordinator.requests.value.single().batch?.files?.single()?.protocolId)
        coordinator.dispose()
    }

    @Test
    fun leaseSurvivesFailedConnectionAndCancelReleasesExactlyOnce() = runTest {
        var releases = 0
        val lease = ExternalFileResourceLease("failed-drop")
        ExternalFileResourceLeaseRegistry.register(lease) { releases++ }
        val coordinator = FileDropCopyCoordinator(this, { error("offline") }, {}, { _, _, _ -> emptyList() }, testTaskState())
        val id = coordinator.submit(destination) { buildPreparedExternalFileBatch(listOf(file("/source.txt")), lease = lease) }
        advanceUntilIdle()
        assertEquals(0, releases)
        coordinator.cancel(id)
        coordinator.cancel(id)
        assertEquals(1, releases)
    }

    @Test
    fun heldPlatformPermissionSurvivesPreparationAndTaskExecution() = runTest {
        var releases = 0
        val heldLease = ExternalFileResourceLease("uri-permission")
        ExternalFileResourceLeaseRegistry.register(heldLease) { releases++ }
        val coordinator = FileDropCopyCoordinator(this, { target }, {}, { _, batch, _ ->
            ExternalFileResourceLeaseRegistry.bindTasks(assertNotNull(batch.lease), listOf(8L))
            listOf(8L)
        }, testTaskState())
        coordinator.submit(destination, heldLease) { buildPreparedExternalFileBatch(listOf(file("/staged.txt"))) }
        advanceUntilIdle()
        assertEquals(0, releases)
        ExternalFileResourceLeaseRegistry.releaseTask(8L)
        assertEquals(1, releases)
    }

    @Test
    fun submissionFailureKeepsReleasedProducerResourcesForRetry() = runTest {
        var releases = 0
        var submissions = 0
        val lease = ExternalFileResourceLease("submission-failure")
        ExternalFileResourceLeaseRegistry.register(lease) { releases++ }
        val coordinator = FileDropCopyCoordinator(this, { target }, {}, { _, batch, _ ->
            submissions++
            if (submissions == 1) {
                ExternalFileResourceLeaseRegistry.releaseProducer(lease.id)
                error("queue unavailable")
            }
            ExternalFileResourceLeaseRegistry.bindTasks(assertNotNull(batch.lease), listOf(9L))
            listOf(9L)
        }, testTaskState())
        val id = coordinator.submit(destination) { buildPreparedExternalFileBatch(listOf(file("/source.txt")), lease = lease) }
        advanceUntilIdle()
        assertEquals(0, releases)
        coordinator.retry(id)
        advanceUntilIdle()
        assertEquals(0, releases)
        ExternalFileResourceLeaseRegistry.releaseTask(9L)
        assertEquals(1, releases)
    }

    @Test
    fun cancelDuringPreparationReleasesPermissionWithoutEnqueueing() = runTest {
        var releases = 0
        val lease = ExternalFileResourceLease("preparing-permission")
        ExternalFileResourceLeaseRegistry.register(lease) { releases++ }
        val coordinator = FileDropCopyCoordinator(this, { target }, {}, { _, _, _ -> error("must not enqueue") }, testTaskState())
        val id = coordinator.submit(destination, lease) { awaitCancellation() }
        runCurrent()
        coordinator.cancel(id)
        advanceUntilIdle()
        assertEquals(1, releases)
        assertTrue(coordinator.requests.value.isEmpty())
    }

    @Test
    fun shareListCanReleaseItsReferenceWhileCopyKeepsTemporaryTreeAlive() {
        var releases = 0
        ShareListDropResourceRegistry.register(listOf("/temporary/folder")) { releases++ }
        val lease = assertNotNull(ShareListDropResourceRegistry.retainForCopy(listOf("/temporary/folder/nested/file.txt")))
        ShareListDropResourceRegistry.releaseAll()
        assertEquals(0, releases)
        ExternalFileResourceLeaseRegistry.releaseProducer(lease.id)
        assertEquals(1, releases)
    }

    @Test
    fun urlAndStagedShareResourcesAreCapturedBeforeTheListChanges() {
        var releases = 0
        val source = ClipboardUrlShareFiles.add(file("/staged/source.txt"), "https://example.invalid/source.txt") { releases++ }
        val lease = assertNotNull(retainFileDropResources(listOf(source)))
        val batch = buildPreparedExternalFileBatch(listOf(source))
        ClipboardUrlShareFiles.clear()
        assertEquals("https://example.invalid/source.txt", batch.sourceDownloadUrls[source.path])
        assertEquals(0, releases)
        ExternalFileResourceLeaseRegistry.releaseProducer(lease.id)
        assertEquals(1, releases)
    }

    @Test
    fun cancellingAnUninterruptiblePreparationKeepsPermissionUntilReaderStops() = runTest {
        var releases = 0
        val permission = ExternalFileResourceLease("non-cancellable-reader")
        val staging = ExternalFileResourceLease("non-cancellable-staging")
        ExternalFileResourceLeaseRegistry.register(permission) { releases++ }
        ExternalFileResourceLeaseRegistry.register(staging) { releases++ }
        val readerStopped = CompletableDeferred<Unit>()
        val coordinator = FileDropCopyCoordinator(this, { target }, {}, { _, _, _ -> error("must not enqueue") }, testTaskState())
        val id = coordinator.submit(destination, permission) {
            withContext(NonCancellable) {
                readerStopped.await()
                buildPreparedExternalFileBatch(listOf(file("/staged.txt")), lease = staging)
            }
        }
        runCurrent()
        coordinator.cancel(id)
        runCurrent()
        assertEquals(0, releases)
        readerStopped.complete(Unit)
        advanceUntilIdle()
        assertEquals(2, releases)
        assertTrue(coordinator.requests.value.isEmpty())
    }

    @Test
    fun allSourceAndDestinationCombinationsHaveACopyRoute() {
        val destinations = listOf(FileProtocol.Local, FileProtocol.Device, FileProtocol.Network)
        FileProtocol.entries.forEach { source ->
            destinations.forEach { destination ->
                assertTrue(resolveCopyRoute(source, destination, "source-id") != CopyRoute.Unsupported)
            }
        }
        assertEquals(CopyRoute.ShareToDevice, resolveCopyRoute(FileProtocol.Share, FileProtocol.Device, "read-only"))
    }

    @Test
    fun preparationIsASessionTaskAndItsPhaseChangesWhileConnecting() = runTest {
        val taskStore = TestTaskRuntimePersistenceStore()
        val taskState = TaskState(runtimeStore = taskStore)
        val preparation = CompletableDeferred<Unit>()
        val connection = CompletableDeferred<Unit>()
        val coordinator = FileDropCopyCoordinator(this, {
            connection.await()
            target
        }, {}, { _, _, _ -> listOf(101L) }, taskState)
        coordinator.submit(destination) {
            preparation.await()
            buildPreparedExternalFileBatch(listOf(file("/source.txt")))
        }
        val preparing = taskState.snapshotTasks().single()
        assertEquals(StatusEnum.LOADING, preparing.status)
        assertEquals(AppStrings.ui_drop_copy_preparing_source, preparing.values[TASK_SESSION_TITLE_VALUE_KEY])
        runCurrent()
        preparation.complete(Unit)
        runCurrent()
        val waiting = taskState.snapshotTasks().single()
        assertEquals(preparing.key, waiting.key)
        assertEquals(AppStrings.ui_drop_copy_waiting_target, waiting.values[TASK_SESSION_TITLE_VALUE_KEY])
        assertTrue(taskStore.loadTaskSnapshots().isEmpty())
        assertTrue(TaskState(runtimeStore = taskStore).snapshotTasks().isEmpty())
        connection.complete(Unit)
        advanceUntilIdle()
        assertTrue(taskState.snapshotTasks().isEmpty())
    }

    @Test
    fun startingCopyDeletesPreparationTaskBeforeTheCopyFinishes() = runTest {
        val taskState = testTaskState()
        val copyFinished = CompletableDeferred<Unit>()
        val copyTask = Task(TaskType.Copy, status = StatusEnum.LOADING)
        val coordinator = FileDropCopyCoordinator(this, { target }, {}, { _, _, onCopyStarted ->
            onCopyStarted()
            taskState.addOrUpdate(copyTask)
            copyFinished.await()
            listOf(copyTask.key)
        }, taskState)
        coordinator.submit(destination) { buildPreparedExternalFileBatch(listOf(file("/source.txt"))) }
        val preparationKey = taskState.snapshotTasks().single().key
        runCurrent()
        assertNull(taskState.getTask(preparationKey))
        assertEquals(listOf(copyTask.key), taskState.snapshotTasks().map { it.key })
        assertFalse(copyFinished.isCompleted)
        copyFinished.complete(Unit)
        advanceUntilIdle()
        assertEquals(listOf(copyTask.key), taskState.snapshotTasks().map { it.key })
    }

    @Test
    fun failedPreparationCanBeRetriedFromTheTaskEntryWithoutCreatingAnotherTask() = runTest {
        val taskState = testTaskState()
        var connections = 0
        val connection = CompletableDeferred<Unit>()
        val coordinator = FileDropCopyCoordinator(this, {
            connections++
            if (connections == 1) error("connection rejected")
            connection.await()
            target
        }, {}, { _, _, _ -> listOf(102L) }, taskState)
        coordinator.submit(destination) { buildPreparedExternalFileBatch(listOf(file("/source.txt"))) }
        advanceUntilIdle()
        val failed = taskState.snapshotTasks().single()
        assertEquals(StatusEnum.FAILURE, failed.status)
        assertEquals("connection rejected", failed.result["preparation"])
        assertTrue(taskState.canContinueTask(failed))
        assertTrue(taskState.retrySessionTask(failed))
        runCurrent()
        val retrying = taskState.snapshotTasks().single()
        assertEquals(failed.key, retrying.key)
        assertEquals(StatusEnum.LOADING, retrying.status)
        assertTrue(retrying.result.isEmpty())
        connection.complete(Unit)
        advanceUntilIdle()
        assertEquals(2, connections)
        assertTrue(taskState.snapshotTasks().isEmpty())
    }

    @Test
    fun cancellingPreparationFromTaskEntryStopsWaitingAndReleasesSourcePermission() = runTest {
        val taskState = testTaskState()
        var releases = 0
        val permission = ExternalFileResourceLease("task-preparation-cancel")
        ExternalFileResourceLeaseRegistry.register(permission) { releases++ }
        val coordinator = FileDropCopyCoordinator(this, { target }, {}, { _, _, _ -> error("must not copy") }, taskState)
        coordinator.submit(destination, permission) { awaitCancellation() }
        runCurrent()
        val preparation = taskState.snapshotTasks().single()
        taskState.requestCancel(preparation)
        assertTrue(taskState.isTaskCancelled(preparation.key))
        advanceUntilIdle()
        assertTrue(taskState.snapshotTasks().isEmpty())
        assertTrue(coordinator.requests.value.isEmpty())
        assertEquals(1, releases)
    }

    @Test
    fun deletingFailedPreparationTaskAlsoReleasesItsRetainedResources() = runTest {
        val taskState = testTaskState()
        var releases = 0
        val lease = ExternalFileResourceLease("task-preparation-failure")
        ExternalFileResourceLeaseRegistry.register(lease) { releases++ }
        val coordinator = FileDropCopyCoordinator(this, { error("offline") }, {}, { _, _, _ -> emptyList() }, taskState)
        coordinator.submit(destination) { buildPreparedExternalFileBatch(listOf(file("/source.txt")), lease = lease) }
        advanceUntilIdle()
        assertEquals(0, releases)
        taskState.deleteTerminalTask(taskState.snapshotTasks().single().key)
        advanceUntilIdle()
        assertEquals(1, releases)
        assertTrue(taskState.snapshotTasks().isEmpty())
        assertTrue(coordinator.requests.value.isEmpty())
    }

    @Test
    fun pausedPreparationDoesNotSubmitCopyUntilResumed() = runTest {
        val taskState = testTaskState()
        val connection = CompletableDeferred<Unit>()
        var copies = 0
        val coordinator = FileDropCopyCoordinator(this, {
            connection.await()
            target
        }, {}, { _, _, _ -> copies++; listOf(103L) }, taskState)
        coordinator.submit(destination) { buildPreparedExternalFileBatch(listOf(file("/source.txt"))) }
        runCurrent()
        val preparation = taskState.snapshotTasks().single()
        taskState.requestPause(preparation)
        assertTrue(taskState.isTaskPaused(preparation.key))
        connection.complete(Unit)
        runCurrent()
        assertEquals(StatusEnum.PAUSE, taskState.snapshotTasks().single().status)
        assertEquals(0, copies)
        taskState.requestResume(taskState.snapshotTasks().single())
        assertFalse(taskState.isTaskPaused(preparation.key))
        advanceUntilIdle()
        assertEquals(1, copies)
        assertTrue(taskState.snapshotTasks().isEmpty())
    }

    @Test
    fun disposingThePageAfterHandoffDoesNotCancelCopyOrReleaseItsResourcesEarly() = runTest {
        val taskState = testTaskState()
        val copyFinished = CompletableDeferred<Unit>()
        var releases = 0
        val lease = ExternalFileResourceLease("task-copy-handoff")
        ExternalFileResourceLeaseRegistry.register(lease) { releases++ }
        val coordinator = FileDropCopyCoordinator(this, { target }, {}, { _, batch, onCopyStarted ->
            onCopyStarted()
            ExternalFileResourceLeaseRegistry.bindTasks(assertNotNull(batch.lease), listOf(104L))
            copyFinished.await()
            listOf(104L)
        }, taskState)
        coordinator.submit(destination) { buildPreparedExternalFileBatch(listOf(file("/source.txt")), lease = lease) }
        runCurrent()
        coordinator.dispose()
        assertEquals(0, releases)
        copyFinished.complete(Unit)
        advanceUntilIdle()
        assertEquals(0, releases)
        ExternalFileResourceLeaseRegistry.releaseTask(104L)
        assertEquals(1, releases)
        assertTrue(coordinator.requests.value.isEmpty())
    }

    @Test
    fun failedCopyAfterHandoffDoesNotRestoreThePreparationTask() = runTest {
        val taskState = testTaskState()
        val copyTask = Task(TaskType.Copy, status = StatusEnum.FAILURE)
        val coordinator = FileDropCopyCoordinator(this, { target }, {}, { _, _, onCopyStarted ->
            onCopyStarted()
            taskState.addOrUpdate(copyTask)
            error("copy failed")
        }, taskState)
        coordinator.submit(destination) { buildPreparedExternalFileBatch(listOf(file("/source.txt"))) }
        advanceUntilIdle()
        assertEquals(listOf(copyTask.key), taskState.snapshotTasks().map { it.key })
        assertTrue(coordinator.requests.value.isEmpty())
    }

    @Test
    fun repeatedCopyStartedNotificationsDoNotRecreatePreparationTask() = runTest {
        val taskState = testTaskState()
        val copyTask = Task(TaskType.Copy, status = StatusEnum.LOADING)
        val coordinator = FileDropCopyCoordinator(this, { target }, {}, { _, _, onCopyStarted ->
            onCopyStarted()
            taskState.addOrUpdate(copyTask)
            onCopyStarted()
            assertEquals(listOf(copyTask.key), taskState.snapshotTasks().map { it.key })
            listOf(copyTask.key)
        }, taskState)
        coordinator.submit(destination) { buildPreparedExternalFileBatch(listOf(file("/source.txt"))) }
        advanceUntilIdle()
        assertEquals(listOf(copyTask.key), taskState.snapshotTasks().map { it.key })
        assertTrue(coordinator.requests.value.isEmpty())
    }

    @Test
    fun emptySubmissionAfterHandoffRemovesRequestAndReleasesResources() = runTest {
        val taskState = testTaskState()
        var releases = 0
        val lease = ExternalFileResourceLease("empty-task-submission")
        ExternalFileResourceLeaseRegistry.register(lease) { releases++ }
        val coordinator = FileDropCopyCoordinator(this, { target }, {}, { _, _, onCopyStarted ->
            onCopyStarted()
            emptyList()
        }, taskState)
        coordinator.submit(destination) { buildPreparedExternalFileBatch(listOf(file("/source.txt")), lease = lease) }
        advanceUntilIdle()
        assertTrue(taskState.snapshotTasks().isEmpty())
        assertTrue(coordinator.requests.value.isEmpty())
        assertEquals(1, releases)
    }

    @Test
    fun cancellingPreparationScopeAfterHandoffDoesNotLeakTheCopyResourceReference() = runTest {
        val taskState = testTaskState()
        val preparationScope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler))
        var releases = 0
        val lease = ExternalFileResourceLease("cancelled-task-handoff")
        ExternalFileResourceLeaseRegistry.register(lease) { releases++ }
        val coordinator = FileDropCopyCoordinator(preparationScope, { target }, {}, { _, batch, onCopyStarted ->
            onCopyStarted()
            ExternalFileResourceLeaseRegistry.bindTasks(assertNotNull(batch.lease), listOf(105L))
            awaitCancellation()
        }, taskState)
        coordinator.submit(destination) { buildPreparedExternalFileBatch(listOf(file("/source.txt")), lease = lease) }
        runCurrent()
        preparationScope.cancel()
        advanceUntilIdle()
        assertTrue(taskState.snapshotTasks().isEmpty())
        assertTrue(coordinator.requests.value.isEmpty())
        assertEquals(0, releases)
        ExternalFileResourceLeaseRegistry.releaseTask(105L)
        assertEquals(1, releases)
    }

    private fun testTaskState(): TaskState = TaskState(runtimeStore = TestTaskRuntimePersistenceStore())

    private fun file(
        path: String,
        directory: Boolean = false,
        protocol: FileProtocol = FileProtocol.Local,
        protocolId: String = "",
    ): FileSimpleInfo = FileSimpleInfo.nullFileSimpleInfo().copy(
        name = path.substringAfterLast('/'),
        path = path,
        isDirectory = directory,
        protocol = protocol,
        protocolId = protocolId,
    )
}
