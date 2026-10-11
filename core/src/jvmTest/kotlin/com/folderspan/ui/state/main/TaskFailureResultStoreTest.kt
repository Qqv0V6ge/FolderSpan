package com.folderspan.ui.state.main

import strings.AppStrings

import com.folderspan.data.file.FileProtocol
import com.folderspan.utils.PathUtils
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TaskFailureResultStoreTest {
    private val store = TempTaskFailureResultStore()

    @Test
    fun recordFailureAndResolveUpdatesPersistedEntries() {
        val taskKey = nextTaskKey()
        val entry = TaskRetryEntry(
            entryKey = "copy|file",
            taskType = TaskType.Copy,
            stage = TaskRetryStage.COPY,
            srcPath = "/src/file.txt",
            srcProtocol = FileProtocol.Local,
            destPath = "/dest/file.txt",
            destProtocol = FileProtocol.Local,
            isDirectory = false,
            size = 12L,
        )

        try {
            store.recordFailure(taskKey, entry, AppStrings.ui_copy_failed)
            assertEquals(
                listOf(entry.withState(TaskRetryState.FAILURE).withFailureMessage(AppStrings.ui_copy_failed)),
                store.loadFailures(taskKey).entries,
            )

            store.recordResolved(taskKey, entry)
            assertTrue(store.loadFailures(taskKey).entries.isEmpty())
        } finally {
            store.deleteTask(taskKey)
        }
    }

    @Test
    fun replaceFailuresCompactsTaskFileToLatestFailures() {
        val taskKey = nextTaskKey()
        val first = TaskRetryEntry(
            entryKey = "copy|first",
            taskType = TaskType.Copy,
            stage = TaskRetryStage.COPY,
            srcPath = "/src/first.txt",
            srcProtocol = FileProtocol.Local,
            destPath = "/dest/first.txt",
            destProtocol = FileProtocol.Local,
            isDirectory = false,
        ).withState(TaskRetryState.FAILURE).withFailureMessage("first")
        val second = TaskRetryEntry(
            entryKey = "copy|second",
            taskType = TaskType.Copy,
            stage = TaskRetryStage.COPY,
            srcPath = "/src/second.txt",
            srcProtocol = FileProtocol.Local,
            destPath = "/dest/second.txt",
            destProtocol = FileProtocol.Local,
            isDirectory = false,
        ).withState(TaskRetryState.FAILURE).withFailureMessage("second")

        try {
            store.recordFailure(taskKey, first, "old-first")
            store.recordFailure(taskKey, second, "old-second")

            store.replaceFailures(taskKey, listOf(second))

            assertEquals(listOf(second), store.loadFailures(taskKey).entries)
        } finally {
            store.deleteTask(taskKey)
        }
    }

    @Test
    fun loadFailuresReturnsUnavailableWhenTaskFileIsMissing() {
        val taskKey = nextTaskKey()
        store.deleteTask(taskKey)

        val loaded = store.loadFailures(taskKey)

        assertTrue(loaded.entries.isEmpty())
        assertFalse(loaded.fileAvailable)
    }

    @Test
    fun recordFailurePersistsProtobufPayloadLinesInsteadOfJsonLines() {
        val taskKey = nextTaskKey()
        val entry = TaskRetryEntry(
            entryKey = "copy|protobuf",
            taskType = TaskType.Copy,
            stage = TaskRetryStage.COPY,
            srcPath = "/src/protobuf.txt",
            srcProtocol = FileProtocol.Local,
            destPath = "/dest/protobuf.txt",
            destProtocol = FileProtocol.Local,
            isDirectory = false,
            size = 34L,
        )

        try {
            store.deleteTask(taskKey)
            store.recordFailure(taskKey, entry, "protobuf failure")

            val root = File(PathUtils.getCachePath(), "task-failure-results")
            val protoFile = File(root, "$taskKey.pb64l")

            assertFalse(File(root, "$taskKey.jsonl").exists())
            assertTrue(protoFile.exists())
            assertFalse(protoFile.readText().trimStart().startsWith("{"))
            assertEquals(
                listOf(entry.withState(TaskRetryState.FAILURE).withFailureMessage("protobuf failure")),
                store.loadFailures(taskKey).entries,
            )
        } finally {
            store.deleteTask(taskKey)
        }
    }

    private fun nextTaskKey(): Long = System.nanoTime()
}
