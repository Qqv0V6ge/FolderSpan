package com.folderspan.ui.state.main

import strings.AppStrings

import com.folderspan.data.StatusEnum
import com.folderspan.data.file.FileProtocol
import com.folderspan.test.ChineseLocalizationTest
import kotlin.test.*

class TaskStateRecoverySupportTest : ChineseLocalizationTest() {
    @Test
    fun restoresActiveTasksWithoutRuntimeDataAsFailures() {
        val runtimeStore = TestTaskRuntimePersistenceStore()
        val loadingTask = Task(
            taskType = TaskType.Move,
            key = 500L,
            status = StatusEnum.LOADING,
            protocol = FileProtocol.Device,
            protocolId = "device-a",
        )
        val pausedTask = loadingTask.copy(
            key = 499L,
            status = StatusEnum.PAUSE,
        )
        runtimeStore.saveTaskSnapshot(loadingTask)
        runtimeStore.saveTaskSnapshot(pausedTask)

        val taskState = TaskState(TestTaskFailureResultStore(), runtimeStore)

        assertEquals(StatusEnum.FAILURE, taskState.getTask(loadingTask.key)?.status)
        assertEquals(StatusEnum.FAILURE, taskState.getTask(pausedTask.key)?.status)
    }

    @Test
    fun restoresInterruptedTaskAsRecoverableFailure() {
        val runtimeStore = TestTaskRuntimePersistenceStore()
        val task = Task(
            taskType = TaskType.Copy,
            key = 501L,
            status = StatusEnum.LOADING,
            values = mapOf("path" to "/src/root"),
            protocol = FileProtocol.Local,
        )
        runtimeStore.saveTaskSnapshot(task)
        runtimeStore.saveMeta(
            TaskRuntimeMeta(
                taskKey = task.key,
                taskType = task.taskType,
                source = TaskRuntimeEndpointRef(
                    protocol = FileProtocol.Local,
                    path = "/src/root",
                ),
                target = TaskRuntimeEndpointRef(
                    protocol = FileProtocol.Local,
                    path = "/dest/root",
                ),
                currentPhase = TaskRuntimePhase.EXECUTING,
                scanCompleted = true,
                totalEntries = 1,
                remainingEntries = 1,
                createdAt = 1L,
                updatedAt = 2L,
            )
        )
        runtimeStore.appendQueueEntries(
            taskKey = task.key,
            stage = TaskRuntimeStage.COPY,
            category = TaskRuntimeQueueCategory.FILES,
            entries = listOf(
                TaskRuntimeQueueEntry(
                    entryId = "copy-1",
                    stage = TaskRuntimeStage.COPY,
                    kind = TaskRuntimeEntryKind.FILE_COPY,
                    src = TaskRuntimeEndpointRef(
                        protocol = FileProtocol.Local,
                        path = "/src/root/file.txt",
                    ),
                    dest = TaskRuntimeEndpointRef(
                        protocol = FileProtocol.Local,
                        path = "/dest/root/file.txt",
                    ),
                    order = 0,
                )
            ),
        )
        runtimeStore.saveRunState(
            TaskRuntimeRunState(
                taskKey = task.key,
                runId = "run-501",
                currentPhase = TaskRuntimePhase.EXECUTING,
                currentStage = TaskRuntimeStage.COPY,
                currentQueueCategory = TaskRuntimeQueueCategory.FILES,
                currentQueueFile = "000001.pb64l",
                currentEntryPath = "/dest/root/file.txt",
                lastStartedAt = 3L,
            )
        )

        val taskState = TaskState(TestTaskFailureResultStore(), runtimeStore)
        val restored = assertNotNull(taskState.getTask(task.key))

        assertEquals(StatusEnum.FAILURE, restored.status)
        assertEquals("/dest/root/file.txt", restored.values["path"])
        assertTrue(taskState.canContinueTask(restored))
        assertEquals(AppStrings.ui_task_interrupted_after_application_exits_task_can_continued, restored.result["/dest/root/file.txt"])
        assertEquals("execute", restored.values["__runtime_phase"])
        assertEquals(TaskRuntimeStage.COPY.name, restored.values["__runtime_stage"])
        assertEquals(TaskRuntimeQueueCategory.FILES.name, restored.values["__runtime_queue_category"])
        assertEquals("000001.pb64l", restored.values["__runtime_queue_file"])
        assertEquals("1", restored.values["__runtime_remaining"])
        assertEquals("true", restored.values["__runtime_resumed"])
    }

    @Test
    fun requestCancelRemovesRuntimeFilesImmediately() {
        val runtimeStore = TestTaskRuntimePersistenceStore()
        val taskState = TaskState(TestTaskFailureResultStore(), runtimeStore)
        val task = Task(
            taskType = TaskType.Delete,
            key = 502L,
            status = StatusEnum.LOADING,
        )
        taskState.addOrUpdate(task)
        runtimeStore.saveMeta(
            TaskRuntimeMeta(
                taskKey = task.key,
                taskType = task.taskType,
                source = TaskRuntimeEndpointRef(
                    protocol = FileProtocol.Local,
                    path = "/tmp/remove.txt",
                ),
                currentPhase = TaskRuntimePhase.EXECUTING,
                scanCompleted = true,
                totalEntries = 1,
                remainingEntries = 1,
                createdAt = 1L,
            )
        )
        runtimeStore.appendQueueEntries(
            taskKey = task.key,
            stage = TaskRuntimeStage.DELETE,
            category = TaskRuntimeQueueCategory.FILES,
            entries = listOf(
                TaskRuntimeQueueEntry(
                    entryId = "delete-1",
                    stage = TaskRuntimeStage.DELETE,
                    kind = TaskRuntimeEntryKind.TARGET_DELETE,
                    src = TaskRuntimeEndpointRef(
                        protocol = FileProtocol.Local,
                        path = "/tmp/remove.txt",
                    ),
                    order = 0,
                )
            ),
        )
        runtimeStore.saveRunState(
            TaskRuntimeRunState(
                taskKey = task.key,
                runId = "run-502",
                currentPhase = TaskRuntimePhase.EXECUTING,
                currentStage = TaskRuntimeStage.DELETE,
                currentQueueCategory = TaskRuntimeQueueCategory.FILES,
                currentQueueFile = "000001.pb64l",
            )
        )
        runtimeStore.saveTransferCheckpoint(
            TaskRuntimeTransferCheckpoint(
                taskKey = task.key,
                entryId = "delete-1",
                destPath = "/tmp/remove.txt",
                fileSize = 1024L,
                chunkSize = 128,
                completedChunks = 1,
                createdAt = 1L,
            )
        )

        taskState.requestCancel(task)

        assertNull(runtimeStore.loadMeta(task.key))
        assertNull(runtimeStore.loadRunState(task.key))
        assertNull(runtimeStore.loadTransferCheckpoint(task.key, "delete-1"))
        assertFalse(runtimeStore.hasPendingEntries(task.key))
    }

    @Test
    fun runtimeWithoutPendingQueueDoesNotExposeContinueAction() {
        val runtimeStore = TestTaskRuntimePersistenceStore()
        val taskState = TaskState(TestTaskFailureResultStore(), runtimeStore)
        val task = Task(
            taskType = TaskType.Move,
            key = 503L,
            status = StatusEnum.FAILURE,
        )
        taskState.addOrUpdate(task)
        runtimeStore.saveMeta(
            TaskRuntimeMeta(
                taskKey = task.key,
                taskType = task.taskType,
                source = TaskRuntimeEndpointRef(
                    protocol = FileProtocol.Local,
                    path = "/src/file.txt",
                ),
                target = TaskRuntimeEndpointRef(
                    protocol = FileProtocol.Local,
                    path = "/dest/file.txt",
                ),
                currentPhase = TaskRuntimePhase.EXECUTING,
                scanCompleted = true,
                totalEntries = 2,
                remainingEntries = 0,
                createdAt = 1L,
            )
        )

        assertFalse(taskState.canContinueTask(task))
        assertTrue(runtimeStore.loadMeta(task.key) != null)
        assertFalse(runtimeStore.hasPendingEntries(task.key))
    }

    @Test
    fun restoresCategoryWhenRunStateIsMissing() {
        val runtimeStore = TestTaskRuntimePersistenceStore()
        val task = Task(
            taskType = TaskType.Delete,
            key = 504L,
            status = StatusEnum.LOADING,
            values = mapOf("path" to "/tmp/root"),
            protocol = FileProtocol.Local,
        )
        runtimeStore.saveTaskSnapshot(task)
        runtimeStore.saveMeta(
            TaskRuntimeMeta(
                taskKey = task.key,
                taskType = task.taskType,
                source = TaskRuntimeEndpointRef(protocol = FileProtocol.Local, path = "/tmp/root"),
                currentPhase = TaskRuntimePhase.EXECUTING,
                scanCompleted = true,
                totalEntries = 2,
                remainingEntries = 2,
                createdAt = 1L,
            )
        )
        runtimeStore.appendQueueEntries(
            taskKey = task.key,
            stage = TaskRuntimeStage.DELETE,
            category = TaskRuntimeQueueCategory.DIRECTORIES,
            entries = listOf(
                TaskRuntimeQueueEntry(
                    entryId = "delete-dir",
                    stage = TaskRuntimeStage.DELETE,
                    kind = TaskRuntimeEntryKind.TARGET_DELETE,
                    src = TaskRuntimeEndpointRef(protocol = FileProtocol.Local, path = "/tmp/root"),
                    isDirectory = true,
                    order = 0,
                )
            ),
        )
        runtimeStore.appendQueueEntries(
            taskKey = task.key,
            stage = TaskRuntimeStage.DELETE,
            category = TaskRuntimeQueueCategory.FILES,
            entries = listOf(
                TaskRuntimeQueueEntry(
                    entryId = "delete-file",
                    stage = TaskRuntimeStage.DELETE,
                    kind = TaskRuntimeEntryKind.TARGET_DELETE,
                    src = TaskRuntimeEndpointRef(protocol = FileProtocol.Local, path = "/tmp/root/a.txt"),
                    order = 0,
                )
            ),
        )

        val taskState = TaskState(TestTaskFailureResultStore(), runtimeStore)
        val restored = assertNotNull(taskState.getTask(task.key))

        assertEquals(StatusEnum.FAILURE, restored.status)
        assertEquals(TaskRuntimeStage.DELETE.name, restored.values["__runtime_stage"])
        assertEquals(TaskRuntimeQueueCategory.FILES.name, restored.values["__runtime_queue_category"])
        assertEquals("2", restored.values["__runtime_remaining"])
        assertTrue(taskState.canContinueTask(restored))
    }
}
