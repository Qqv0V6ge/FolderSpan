package com.folderspan.ui.state.file

import com.folderspan.ui.state.main.TaskRuntimeEndpointRef
import com.folderspan.ui.state.main.TaskRuntimeEntryKind
import com.folderspan.ui.state.main.TaskRuntimeQueueEntry
import com.folderspan.ui.state.main.TaskRuntimeStage
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class FileTaskRuntimeMetricModeTest {
    @Test
    fun smallCopyBatchesUseItemRuntimeMetrics() {
        val entries = TaskRuntimeQueueEntriesByCategory(
            files = List(8) { index -> copyEntry(path = "/source/$index.txt", size = 4_096L, order = index) },
        )

        assertFalse(shouldUseCopyByteRuntimeMetrics(entries))
    }

    @Test
    fun largeCopyBatchesUseByteRuntimeMetrics() {
        val entries = TaskRuntimeQueueEntriesByCategory(
            files = listOf(copyEntry(path = "/source/movie.bin", size = 8L * 1024L * 1024L)),
        )

        assertTrue(shouldUseCopyByteRuntimeMetrics(entries))
    }

    @Test
    fun zeroByteCopyBatchesUseItemRuntimeMetrics() {
        val entries = TaskRuntimeQueueEntriesByCategory(
            files = List(3) { index -> copyEntry(path = "/source/empty-$index.txt", size = 0L, order = index) },
        )

        assertFalse(shouldUseCopyByteRuntimeMetrics(entries))
    }

    @Test
    fun directoryHeavySmallCopyBatchesUseItemRuntimeMetrics() {
        val entries = TaskRuntimeQueueEntriesByCategory(
            directories = List(12) { index -> directoryEntry(path = "/target/dir-$index", order = index) },
            files = List(8) { index -> copyEntry(path = "/source/$index.txt", size = 4_096L, order = index + 12) },
        )

        assertFalse(shouldUseCopyByteRuntimeMetrics(entries))
    }

    private fun directoryEntry(path: String, order: Int = 0): TaskRuntimeQueueEntry {
        return TaskRuntimeQueueEntry(
            entryId = path,
            stage = TaskRuntimeStage.COPY,
            kind = TaskRuntimeEntryKind.DIRECTORY_CREATE,
            src = TaskRuntimeEndpointRef(path = path.replace("/target", "/source")),
            dest = TaskRuntimeEndpointRef(path = path),
            isDirectory = true,
            order = order,
        )
    }

    private fun copyEntry(path: String, size: Long, order: Int = 0): TaskRuntimeQueueEntry {
        return TaskRuntimeQueueEntry(
            entryId = path,
            stage = TaskRuntimeStage.COPY,
            kind = TaskRuntimeEntryKind.FILE_COPY,
            src = TaskRuntimeEndpointRef(path = path),
            dest = TaskRuntimeEndpointRef(path = path.replace("/source", "/target")),
            size = size,
            order = order,
        )
    }
}
