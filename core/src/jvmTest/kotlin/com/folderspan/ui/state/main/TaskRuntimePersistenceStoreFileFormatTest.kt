package com.folderspan.ui.state.main

import com.folderspan.data.StatusEnum
import com.folderspan.data.file.FileProtocol
import com.folderspan.utils.PathUtils
import com.folderspan.utils.ProtoBufCodec
import java.io.File
import kotlin.io.encoding.Base64
import kotlin.test.*

class TaskRuntimePersistenceStoreFileFormatTest {
    private val store = TempTaskRuntimePersistenceStore()

    @Test
    fun taskModelRoundTripsThroughProtobuf() {
        val task = Task(
            taskType = TaskType.Copy,
            key = System.nanoTime(),
            status = StatusEnum.LOADING,
            values = mapOf("path" to "/src/protobuf.txt"),
        )

        assertEquals(task, ProtoBufCodec.decode<Task>(ProtoBufCodec.encode(task)))
    }

    @Test
    fun persistsRuntimeTaskDataAsProtobufPayloadsInsteadOfJsonFiles() {
        val taskKey = System.nanoTime()
        val task = Task(
            taskType = TaskType.Copy,
            key = taskKey,
            status = StatusEnum.LOADING,
            values = mapOf("path" to "/src/protobuf.txt"),
        )
        val entry = TaskRuntimeQueueEntry(
            entryId = "entry-protobuf",
            stage = TaskRuntimeStage.COPY,
            kind = TaskRuntimeEntryKind.FILE_COPY,
            src = TaskRuntimeEndpointRef(protocol = FileProtocol.Local, path = "/src/protobuf.txt"),
            dest = TaskRuntimeEndpointRef(protocol = FileProtocol.Local, path = "/dest/protobuf.txt"),
            size = 34L,
        )
        val checkpoint = TaskRuntimeTransferCheckpoint(
            taskKey = taskKey,
            entryId = entry.entryId,
            destPath = entry.dest.path,
            fileSize = entry.size,
            chunkSize = 1024,
            createdAt = 1L,
        )

        try {
            store.clearTaskRuntime(taskKey)

            store.saveTaskSnapshot(task)
            store.saveMeta(
                TaskRuntimeMeta(
                    taskKey = taskKey,
                    taskType = TaskType.Copy,
                    createdAt = 1L,
                )
            )
            store.saveRunState(
                TaskRuntimeRunState(
                    taskKey = taskKey,
                    runId = "run-protobuf",
                )
            )
            store.appendQueueEntries(
                taskKey = taskKey,
                stage = TaskRuntimeStage.COPY,
                category = TaskRuntimeQueueCategory.FILES,
                entries = listOf(entry),
            )
            store.saveTransferCheckpoint(checkpoint)

            val taskDir = File(File(PathUtils.getCachePath(), "task-runtime"), taskKey.toString())
            val taskFile = File(taskDir, "task.pb64")
            val metaFile = File(taskDir, "meta.pb64")
            val runFile = File(taskDir, "run.pb64")
            val queueFile = File(taskDir, "pending/copy/files/000001.pb64l")

            assertFalse(File(taskDir, "task.json").exists())
            assertFalse(File(taskDir, "meta.json").exists())
            assertFalse(File(taskDir, "run.json").exists())
            assertFalse(File(taskDir, "pending/copy/files/000001.queue").exists())
            assertTrue(taskFile.exists())
            assertTrue(metaFile.exists())
            assertTrue(runFile.exists())
            assertTrue(queueFile.exists())
            assertFalse(taskFile.readText().trimStart().startsWith("{"))
            assertFalse(metaFile.readText().trimStart().startsWith("{"))
            assertFalse(runFile.readText().trimStart().startsWith("{"))
            assertFalse(queueFile.readText().trimStart().startsWith("{"))

            assertEquals(task, ProtoBufCodec.decode<Task>(Base64.decode(taskFile.readText().trim())))
            assertEquals(task, store.loadTaskSnapshot(taskKey))
            assertEquals(TaskType.Copy, store.loadMeta(taskKey)?.taskType)
            assertEquals("run-protobuf", store.loadRunState(taskKey)?.runId)
            assertEquals("entry-protobuf", assertNotNull(store.peekNextQueueEntry(taskKey, TaskRuntimeStage.COPY, TaskRuntimeQueueCategory.FILES)).entry.entryId)
            assertEquals(checkpoint, store.loadTransferCheckpoint(taskKey, entry.entryId))
        } finally {
            store.clearTaskRuntime(taskKey)
        }
    }
}
