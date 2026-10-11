package com.folderspan.ui.state.file

import com.folderspan.data.file.FileProtocol
import com.folderspan.data.main.share.SYSTEM_SHARE_DESK_ID
import com.folderspan.ui.state.main.*
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class FileTaskArchiveQueuePlannerTest {
    @Test
    fun deviceToLocalQueueSmallFilesUseArchiveBatch() {
        val selection = selectRuntimeSmallFileArchiveBatches(
            queuedEntries = listOf(
                queuedFile(
                    entryId = "a",
                    sourceProtocol = FileProtocol.Device,
                    sourcePath = "/device/root/a.txt",
                    destinationProtocol = FileProtocol.Local,
                    destinationPath = "/local/root/a.txt",
                    size = 12L,
                ),
                queuedFile(
                    entryId = "b",
                    sourceProtocol = FileProtocol.Device,
                    sourcePath = "/device/root/nested/b.txt",
                    destinationProtocol = FileProtocol.Local,
                    destinationPath = "/local/root/nested/b.txt",
                    size = 32L,
                ),
            ),
            destinationRootPath = "/local/root",
            destinationSeparator = "/",
        )

        assertEquals(1, selection.batches.size)
        assertTrue(selection.remainingEntries.isEmpty())
        assertEquals(
            listOf("/device/root/a.txt", "/device/root/nested/b.txt"),
            selection.batches.single().requestEntries.map { entry -> entry.sourcePath },
        )
        assertEquals(
            listOf("a.txt", "nested/b.txt"),
            selection.batches.single().requestEntries.map { entry -> entry.relativePath },
        )
    }

    @Test
    fun leavesQueueEntryOnOriginalPathWhenDestinationIsOutsideRoot() {
        val outsideEntry = queuedFile(
            entryId = "outside",
            sourceProtocol = FileProtocol.Device,
            sourcePath = "/device/root/outside.txt",
            destinationProtocol = FileProtocol.Local,
            destinationPath = "/other/outside.txt",
            size = 12L,
        )
        val insideEntry = queuedFile(
            entryId = "inside",
            sourceProtocol = FileProtocol.Device,
            sourcePath = "/device/root/inside.txt",
            destinationProtocol = FileProtocol.Local,
            destinationPath = "/local/root/inside.txt",
            size = 32L,
        )

        val selection = selectRuntimeSmallFileArchiveBatches(
            queuedEntries = listOf(outsideEntry, insideEntry),
            destinationRootPath = "/local/root",
            destinationSeparator = "/",
        )

        assertTrue(selection.batches.isEmpty())
        assertEquals(listOf("outside", "inside"), selection.remainingEntries.map { entry -> entry.entry.entryId })
    }

    @Test
    fun rootDestinationBuildsRelativeArchivePaths() {
        val selection = selectRuntimeSmallFileArchiveBatches(
            queuedEntries = listOf(
                queuedFile(
                    entryId = "a",
                    sourceProtocol = FileProtocol.Device,
                    sourcePath = "/device/root/a.txt",
                    destinationProtocol = FileProtocol.Local,
                    destinationPath = "/a.txt",
                    size = 12L,
                ),
                queuedFile(
                    entryId = "b",
                    sourceProtocol = FileProtocol.Device,
                    sourcePath = "/device/root/nested/b.txt",
                    destinationProtocol = FileProtocol.Local,
                    destinationPath = "/nested/b.txt",
                    size = 32L,
                ),
            ),
            destinationRootPath = "/",
            destinationSeparator = "/",
        )

        assertEquals(1, selection.batches.size)
        assertEquals(listOf("a.txt", "nested/b.txt"), selection.batches.single().requestEntries.map { entry -> entry.relativePath })
    }

    @Test
    fun emptyFileCreateEntriesStayOutOfArchiveBatches() {
        val selection = selectRuntimeSmallFileArchiveBatches(
            queuedEntries = listOf(
                queuedFile(
                    entryId = "empty",
                    sourceProtocol = FileProtocol.Device,
                    sourcePath = "/device/root/empty.txt",
                    destinationProtocol = FileProtocol.Local,
                    destinationPath = "/local/root/empty.txt",
                    size = 0L,
                    kind = TaskRuntimeEntryKind.EMPTY_FILE_CREATE,
                ),
                queuedFile(
                    entryId = "a",
                    sourceProtocol = FileProtocol.Device,
                    sourcePath = "/device/root/a.txt",
                    destinationProtocol = FileProtocol.Local,
                    destinationPath = "/local/root/a.txt",
                    size = 12L,
                ),
                queuedFile(
                    entryId = "b",
                    sourceProtocol = FileProtocol.Device,
                    sourcePath = "/device/root/b.txt",
                    destinationProtocol = FileProtocol.Local,
                    destinationPath = "/local/root/b.txt",
                    size = 24L,
                ),
            ),
            destinationRootPath = "/local/root",
            destinationSeparator = "/",
        )

        assertEquals(listOf("a", "b"), selection.batches.single().queuedEntries.map { entry -> entry.entry.entryId })
        assertEquals(listOf("empty"), selection.remainingEntries.map { entry -> entry.entry.entryId })
    }

    @Test
    fun mixedDirectoryKeepsStructureAndLargeFilesOutsideSmallFileBatch() {
        val directory = queuedFile(
            entryId = "directory",
            sourceProtocol = FileProtocol.Device,
            sourcePath = "/device/root/nested",
            destinationProtocol = FileProtocol.Local,
            destinationPath = "/local/root/nested",
            size = 0L,
            kind = TaskRuntimeEntryKind.DIRECTORY_CREATE,
        )
        val emptyFile = queuedFile(
            entryId = "empty",
            sourceProtocol = FileProtocol.Device,
            sourcePath = "/device/root/nested/empty.txt",
            destinationProtocol = FileProtocol.Local,
            destinationPath = "/local/root/nested/empty.txt",
            size = 0L,
            kind = TaskRuntimeEntryKind.EMPTY_FILE_CREATE,
        )
        val largeFile = queuedFile(
            entryId = "large",
            sourceProtocol = FileProtocol.Device,
            sourcePath = "/device/root/large.bin",
            destinationProtocol = FileProtocol.Local,
            destinationPath = "/local/root/large.bin",
            size = 1024L * 1024L + 1L,
        )
        val selection = selectRuntimeSmallFileArchiveBatches(
            queuedEntries = listOf(
                directory,
                queuedFile(
                    entryId = "small-a",
                    sourceProtocol = FileProtocol.Device,
                    sourcePath = "/device/root/a.txt",
                    destinationProtocol = FileProtocol.Local,
                    destinationPath = "/local/root/a.txt",
                    size = 12L,
                ),
                largeFile,
                queuedFile(
                    entryId = "small-b",
                    sourceProtocol = FileProtocol.Device,
                    sourcePath = "/device/root/nested/b.txt",
                    destinationProtocol = FileProtocol.Local,
                    destinationPath = "/local/root/nested/b.txt",
                    size = 24L,
                ),
                emptyFile,
            ),
            destinationRootPath = "/local/root",
            destinationSeparator = "/",
        )

        assertEquals(
            listOf("small-a", "small-b"),
            selection.batches.single().queuedEntries.map { entry -> entry.entry.entryId },
        )
        assertEquals(
            listOf("a.txt", "nested/b.txt"),
            selection.batches.single().requestEntries.map { entry -> entry.relativePath },
        )
        assertEquals(
            listOf("directory", "empty", "large"),
            selection.remainingEntries.map { entry -> entry.entry.entryId },
        )
    }

    @Test
    fun shareToLocalQueueUsesTheSameArchivePlanAsDevice() {
        assertTrue(
            shouldPlanRuntimeCopyArchive(
                sourceProtocol = FileProtocol.Share,
                destinationProtocol = FileProtocol.Local,
                sourceProtocolId = "share-1",
            )
        )
        val selection = selectRuntimeSmallFileArchiveBatches(
            queuedEntries = listOf(
                queuedFile(
                    entryId = "a",
                    sourceProtocol = FileProtocol.Share,
                    sourcePath = "/share/root/a.txt",
                    destinationProtocol = FileProtocol.Local,
                    destinationPath = "/local/root/a.txt",
                    size = 12L,
                ),
                queuedFile(
                    entryId = "b",
                    sourceProtocol = FileProtocol.Share,
                    sourcePath = "/share/root/nested/b.txt",
                    destinationProtocol = FileProtocol.Local,
                    destinationPath = "/local/root/nested/b.txt",
                    size = 32L,
                ),
            ),
            destinationRootPath = "/local/root",
            destinationSeparator = "/",
        )

        assertEquals(1, selection.batches.size)
        assertTrue(selection.remainingEntries.isEmpty())
        assertEquals(
            listOf("a.txt", "nested/b.txt"),
            selection.batches.single().requestEntries.map { entry -> entry.relativePath },
        )
    }

    @Test
    fun systemShareAndShareToDeviceStayOutOfArchivePlan() {
        assertFalse(
            shouldPlanRuntimeCopyArchive(
                sourceProtocol = FileProtocol.Share,
                destinationProtocol = FileProtocol.Local,
                sourceProtocolId = SYSTEM_SHARE_DESK_ID,
            )
        )
        assertFalse(
            shouldPlanRuntimeCopyArchive(
                sourceProtocol = FileProtocol.Share,
                destinationProtocol = FileProtocol.Device,
                sourceProtocolId = "share-1",
                destinationProtocolId = "device-1",
            )
        )
        assertFalse(
            shouldPlanRuntimeCopyArchive(
                sourceProtocol = FileProtocol.Share,
                destinationProtocol = FileProtocol.Network,
                sourceProtocolId = "share-1",
            )
        )
        assertFalse(
            shouldPlanRuntimeCopyArchive(
                sourceProtocol = FileProtocol.Device,
                destinationProtocol = FileProtocol.Network,
                sourceProtocolId = "device-1",
            )
        )
        assertFalse(
            shouldPlanRuntimeCopyArchive(
                sourceProtocol = FileProtocol.Local,
                destinationProtocol = FileProtocol.Local,
            )
        )
    }

    private fun queuedFile(
        entryId: String,
        sourceProtocol: FileProtocol,
        sourcePath: String,
        destinationProtocol: FileProtocol,
        destinationPath: String,
        size: Long,
        kind: TaskRuntimeEntryKind = TaskRuntimeEntryKind.FILE_COPY,
    ): TaskRuntimeQueuedEntry {
        return TaskRuntimeQueuedEntry(
            fileName = "000001.pb64l",
            entry = TaskRuntimeQueueEntry(
                entryId = entryId,
                stage = TaskRuntimeStage.COPY,
                kind = kind,
                src = TaskRuntimeEndpointRef(
                    protocol = sourceProtocol,
                    path = sourcePath,
                ),
                dest = TaskRuntimeEndpointRef(
                    protocol = destinationProtocol,
                    path = destinationPath,
                ),
                size = size,
                order = 0,
            ),
        )
    }
}
