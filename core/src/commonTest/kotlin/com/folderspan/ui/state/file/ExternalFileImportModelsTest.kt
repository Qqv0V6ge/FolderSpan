package com.folderspan.ui.state.file

import strings.AppStrings

import com.folderspan.data.file.FileProtocol
import com.folderspan.data.file.FileSimpleInfo
import com.folderspan.data.main.Local
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import com.folderspan.data.StatusEnum
import com.folderspan.ui.state.main.Task
import com.folderspan.ui.state.main.TaskState
import com.folderspan.ui.state.main.TaskType
import com.folderspan.ui.state.main.TestTaskFailureResultStore
import com.folderspan.ui.state.main.TestTaskRuntimePersistenceStore

class ExternalFileImportModelsTest {
    @AfterTest
    fun tearDown() {
        ExternalFileResourceLeaseRegistry.clearForTest()
    }

    @Test
    fun preparedBatchDeduplicatesEquivalentRepresentations() {
        val source = file("report.txt", "/source/report.txt", size = 42, updatedDate = 7)

        val batch = buildPreparedExternalFileBatch(
            files = listOf(source, source.copy()),
            representedItemCount = 2,
        )

        assertEquals(listOf(source), batch.files)
        assertEquals(1, batch.skipped.size)
        assertEquals(ExternalFileSkipReason.Duplicate, batch.skipped.single().reason)
        assertEquals(2, batch.representedItemCount)
        assertEquals(setOf(externalFileDedupeKey(source)), batch.dedupeKeys)
    }

    @Test
    fun clipboardImageNamesUseSafeMimeExtensionsAndStableIndexes() {
        assertEquals("clipboard-image-123.png", clipboardImageFileName(123L))
        assertEquals("clipboard-image-123-2.jpg", clipboardImageFileName(123L, 1, "image/jpeg"))
        assertEquals("clipboard-image-0.webp", clipboardImageFileName(-1L, mimeType = "image/webp; quality=90"))
        assertNull(clipboardImageFileName(123L, mimeType = "application/octet-stream"))
    }

    @Test
    fun resourceLeaseWaitsForEveryBoundTask() {
        var releaseCount = 0
        val lease = ExternalFileResourceLease("lease-test")
        assertTrue(ExternalFileResourceLeaseRegistry.register(lease) { releaseCount++ })

        ExternalFileResourceLeaseRegistry.bindTasks(lease, listOf(11L, 12L))
        assertEquals(2, ExternalFileResourceLeaseRegistry.referenceCount(lease.id))
        assertEquals(0, releaseCount)

        ExternalFileResourceLeaseRegistry.releaseTask(11L)
        assertEquals(1, ExternalFileResourceLeaseRegistry.referenceCount(lease.id))
        assertEquals(0, releaseCount)

        ExternalFileResourceLeaseRegistry.releaseTask(12L)
        assertEquals(0, ExternalFileResourceLeaseRegistry.referenceCount(lease.id))
        assertEquals(1, releaseCount)

        ExternalFileResourceLeaseRegistry.releaseTask(12L)
        ExternalFileResourceLeaseRegistry.releaseProducer(lease.id)
        assertEquals(1, releaseCount)
    }

    @Test
    fun coordinatorKeepsProducerLeaseWhileConflictPlanningAndUntilEveryTaskFinishes() = runTest {
        var releaseCount = 0
        val lease = ExternalFileResourceLease("conflict-planning")
        ExternalFileResourceLeaseRegistry.register(lease) { releaseCount++ }
        val coordinator = ExternalFileImportCoordinator { _, _, _, boundLease ->
            assertEquals(1, ExternalFileResourceLeaseRegistry.referenceCount(lease.id))
            assertEquals(lease, boundLease)
            ExternalFileResourceLeaseRegistry.bindTasks(lease, listOf(31L, 32L))
            listOf(31L, 32L)
        }

        val result = coordinator.import(
            target = target(),
            batch = buildPreparedExternalFileBatch(
                files = listOf(file("a.txt", "/source/a.txt"), file("b.txt", "/source/b.txt")),
                lease = lease,
            ),
            fileOperationState = FileOperationState(),
        )

        assertEquals(listOf(31L, 32L), result.taskKeys)
        assertEquals(2, ExternalFileResourceLeaseRegistry.referenceCount(lease.id))
        assertEquals(0, releaseCount)

        ExternalFileResourceLeaseRegistry.releaseTask(31L)
        assertEquals(1, ExternalFileResourceLeaseRegistry.referenceCount(lease.id))
        assertEquals(0, releaseCount)

        ExternalFileResourceLeaseRegistry.releaseTask(32L)
        assertEquals(0, ExternalFileResourceLeaseRegistry.referenceCount(lease.id))
        assertEquals(1, releaseCount)
    }

    @Test
    fun failedTaskRetainsLeaseUntilUserDeletesIt() {
        var releaseCount = 0
        val lease = ExternalFileResourceLease("retry-source")
        ExternalFileResourceLeaseRegistry.register(lease) { releaseCount++ }
        ExternalFileResourceLeaseRegistry.bindTasks(lease, listOf(51L))
        val taskState = TaskState(
            failureResultStore = TestTaskFailureResultStore(),
            runtimeStore = TestTaskRuntimePersistenceStore(),
        )
        val task = Task(
            taskType = TaskType.Copy,
            key = 51L,
            status = StatusEnum.LOADING,
            values = mapOf(EXTERNAL_FILE_LEASE_ID_TASK_VALUE to lease.id),
        )
        taskState.addOrUpdate(task)

        assertTrue(taskState.updateStatus(task, StatusEnum.FAILURE))
        assertEquals(1, ExternalFileResourceLeaseRegistry.referenceCount(lease.id))
        assertEquals(0, releaseCount)

        assertTrue(taskState.delete(task.key))
        assertEquals(0, ExternalFileResourceLeaseRegistry.referenceCount(lease.id))
        assertEquals(1, releaseCount)
    }

    @Test
    fun restoredTaskRebindsPersistedLeaseAndDeletionReleasesIt() {
        val runtimeStore = TestTaskRuntimePersistenceStore()
        val task = Task(
            taskType = TaskType.Copy,
            key = 41L,
            status = StatusEnum.FAILURE,
            values = mapOf(EXTERNAL_FILE_LEASE_ID_TASK_VALUE to "restored-lease"),
        )
        runtimeStore.taskSnapshots[task.key] = task

        val taskState = TaskState(
            failureResultStore = TestTaskFailureResultStore(),
            runtimeStore = runtimeStore,
        )

        assertEquals(1, ExternalFileResourceLeaseRegistry.referenceCount("restored-lease"))
        assertTrue(taskState.delete(task.key))
        assertEquals(0, ExternalFileResourceLeaseRegistry.referenceCount("restored-lease"))
    }

    @Test
    fun coordinatorReleasesEmptyBatchAndReportsNoFiles() = runTest {
        var releaseCount = 0
        val lease = ExternalFileResourceLease("empty-batch")
        ExternalFileResourceLeaseRegistry.register(lease) { releaseCount++ }
        val coordinator = ExternalFileImportCoordinator { _, _, _, _ -> error(AppStrings.ui_test_external_file_import_models_should_not_create_tasks) }

        val result = coordinator.import(
            target = target(),
            batch = PreparedExternalFileBatch(lease = lease),
            fileOperationState = FileOperationState(),
        )

        assertEquals(ExternalFileImportFailure.NoFiles, result.failure)
        assertEquals(1, releaseCount)
        assertFalse(result.isSuccess)
    }

    @Test
    fun coordinatorReportsAllSkippedWhenPreparationProducedOnlyDiagnostics() = runTest {
        val coordinator = ExternalFileImportCoordinator { _, _, _, _ -> error(AppStrings.ui_test_external_file_import_models_should_not_create_tasks) }

        val result = coordinator.import(
            target = target(),
            batch = PreparedExternalFileBatch(
                skipped = listOf(ExternalFileSkip(ExternalFileSkipReason.Unreadable, "broken.txt")),
                representedItemCount = 1,
            ),
            fileOperationState = FileOperationState(),
        )

        assertEquals(ExternalFileImportFailure.AllSkipped, result.failure)
        assertEquals(1, result.skipped.size)
    }

    @Test
    fun coordinatorReleasesLeaseWhenTaskRegistrationFails() = runTest {
        var releaseCount = 0
        val lease = ExternalFileResourceLease("registration-failure")
        ExternalFileResourceLeaseRegistry.register(lease) { releaseCount++ }
        val coordinator = ExternalFileImportCoordinator { _, _, _, _ -> error("registration failed") }

        val result = coordinator.import(
            target = target(),
            batch = buildPreparedExternalFileBatch(
                files = listOf(file("a.txt", "/source/a.txt")),
                lease = lease,
            ),
            fileOperationState = FileOperationState(),
        )

        assertEquals(ExternalFileImportFailure.ReadFailed, result.failure)
        assertEquals(1, releaseCount)
    }

    @Test
    fun coordinatorKeepsCapturedTargetAndReturnsCreatedTaskKeys() = runTest {
        val capturedTarget = target()
        var observedDestination: FileSimpleInfo? = null
        val coordinator = ExternalFileImportCoordinator { target, _, _, _ ->
            observedDestination = target.destination
            listOf(21L, 22L)
        }

        val result = coordinator.import(
            target = capturedTarget,
            batch = buildPreparedExternalFileBatch(
                files = listOf(
                    file("a.txt", "/source/a.txt"),
                    file("b.txt", "/source/b.txt"),
                )
            ),
            fileOperationState = FileOperationState(),
        )

        assertEquals(capturedTarget.destination, observedDestination)
        assertEquals(listOf(21L, 22L), result.taskKeys)
        assertEquals(2, result.acceptedCount)
        assertNull(result.failure)
        assertTrue(result.isSuccess)
    }

    @Test
    fun coordinatorRejectsUnavailableCapturedTargetWithoutFallback() = runTest {
        var invoked = false
        val coordinator = ExternalFileImportCoordinator { _, _, _, _ ->
            invoked = true
            emptyList()
        }
        val destination = file("target.txt", "/target/target.txt", isDirectory = false)

        val result = coordinator.import(
            target = ExternalFileImportTarget(Local(), destination, destination.path),
            batch = buildPreparedExternalFileBatch(listOf(file("a.txt", "/source/a.txt"))),
            fileOperationState = FileOperationState(),
        )

        assertEquals(ExternalFileImportFailure.TargetUnavailable, result.failure)
        assertFalse(invoked)
    }

    private fun target(): ExternalFileImportTarget {
        val destination = file("target", "/target", isDirectory = true)
        return ExternalFileImportTarget(Local(), destination, destination.path)
    }

    private fun file(
        name: String,
        path: String,
        isDirectory: Boolean = false,
        size: Long = 1,
        updatedDate: Long = 1,
    ) = FileSimpleInfo(
        name = name,
        isDirectory = isDirectory,
        isHidden = false,
        path = path,
        mineType = if (isDirectory) "" else "text/plain",
        size = size,
        createdDate = 1,
        updatedDate = updatedDate,
        protocol = FileProtocol.Local,
    )
}
