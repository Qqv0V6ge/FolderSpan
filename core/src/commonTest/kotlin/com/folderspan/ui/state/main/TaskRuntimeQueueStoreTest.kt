package com.folderspan.ui.state.main

import com.folderspan.data.file.FileProtocol
import kotlin.test.*

class TaskRuntimeQueueStoreTest {
    @Test
    fun ackQueueEntryRemovesHeadAndDeletesEmptyFile() {
        val store = TestTaskRuntimePersistenceStore()
        store.appendQueueEntries(
            taskKey = 601L,
            stage = TaskRuntimeStage.COPY,
            category = TaskRuntimeQueueCategory.FILES,
            entries = listOf(
                queueEntry("copy-1", "/src/a.txt", "/dest/a.txt", 0),
                queueEntry("copy-2", "/src/b.txt", "/dest/b.txt", 1),
            ),
        )

        val first = assertNotNull(store.peekNextQueueEntry(601L, TaskRuntimeStage.COPY, TaskRuntimeQueueCategory.FILES))
        assertEquals("copy-1", first.entry.entryId)

        store.ackQueueEntry(601L, TaskRuntimeStage.COPY, TaskRuntimeQueueCategory.FILES, first.fileName)

        val second = assertNotNull(store.peekNextQueueEntry(601L, TaskRuntimeStage.COPY, TaskRuntimeQueueCategory.FILES))
        assertEquals("copy-2", second.entry.entryId)

        store.ackQueueEntry(601L, TaskRuntimeStage.COPY, TaskRuntimeQueueCategory.FILES, second.fileName)

        assertNull(store.peekNextQueueEntry(601L, TaskRuntimeStage.COPY, TaskRuntimeQueueCategory.FILES))
        assertFalse(store.hasPendingEntries(601L))
    }

    @Test
    fun ackQueueEntryOnlyRemovesHeadFromCurrentCategoryFile() {
        val store = TestTaskRuntimePersistenceStore()
        store.appendQueueEntries(
            taskKey = 604L,
            stage = TaskRuntimeStage.COPY,
            category = TaskRuntimeQueueCategory.DIRECTORIES,
            entries = listOf(directoryEntry("dir-1", "/src", "/dest", 0)),
        )
        store.appendQueueEntries(
            taskKey = 604L,
            stage = TaskRuntimeStage.COPY,
            category = TaskRuntimeQueueCategory.FILES,
            entries = listOf(
                queueEntry("file-1", "/src/a.txt", "/dest/a.txt", 0),
                queueEntry("file-2", "/src/b.txt", "/dest/b.txt", 1),
            ),
        )

        val file = assertNotNull(
            store.peekNextQueueEntry(604L, TaskRuntimeStage.COPY, TaskRuntimeQueueCategory.FILES)
        )
        store.ackQueueEntry(604L, TaskRuntimeStage.COPY, TaskRuntimeQueueCategory.FILES, file.fileName)

        assertEquals(
            "dir-1",
            assertNotNull(
                store.peekNextQueueEntry(604L, TaskRuntimeStage.COPY, TaskRuntimeQueueCategory.DIRECTORIES)
            ).entry.entryId,
        )
        assertEquals(
            "file-2",
            assertNotNull(store.peekNextQueueEntry(604L, TaskRuntimeStage.COPY, TaskRuntimeQueueCategory.FILES)).entry.entryId,
        )
    }

    @Test
    fun ackQueueEntryCanRemoveCompletedEntryByIdOutOfOrder() {
        val store = TestTaskRuntimePersistenceStore()
        store.appendQueueEntries(
            taskKey = 605L,
            stage = TaskRuntimeStage.COPY,
            category = TaskRuntimeQueueCategory.FILES,
            entries = listOf(
                queueEntry("copy-1", "/src/a.txt", "/dest/a.txt", 0),
                queueEntry("copy-2", "/src/b.txt", "/dest/b.txt", 1),
                queueEntry("copy-3", "/src/c.txt", "/dest/c.txt", 2),
            ),
        )

        store.ackQueueEntry(
            taskKey = 605L,
            stage = TaskRuntimeStage.COPY,
            category = TaskRuntimeQueueCategory.FILES,
            fileName = "000001.pb64l",
            entryId = "copy-2",
        )

        assertEquals(
            listOf("copy-1", "copy-3"),
            store.loadPendingQueueEntries(
                taskKey = 605L,
                stage = TaskRuntimeStage.COPY,
                category = TaskRuntimeQueueCategory.FILES,
            ).map { item -> item.entry.entryId },
        )
    }

    @Test
    fun appendQueueEntriesSplitsIntoChunkFiles() {
        val store = TestTaskRuntimePersistenceStore()
        store.appendQueueEntries(
            taskKey = 602L,
            stage = TaskRuntimeStage.DELETE,
            category = TaskRuntimeQueueCategory.FILES,
            entries = (0 until 257).map { index ->
                TaskRuntimeQueueEntry(
                    entryId = "delete-$index",
                    stage = TaskRuntimeStage.DELETE,
                    kind = TaskRuntimeEntryKind.TARGET_DELETE,
                    src = TaskRuntimeEndpointRef(
                        protocol = FileProtocol.Local,
                        path = "/tmp/$index",
                    ),
                    order = index,
                )
            },
        )

        assertEquals(
            listOf("000001.pb64l", "000002.pb64l"),
            store.listPendingQueueFiles(602L, TaskRuntimeStage.DELETE, TaskRuntimeQueueCategory.FILES),
        )
        assertEquals(257, store.countPendingEntries(602L))
        assertEquals(257, store.countPendingEntries(602L, TaskRuntimeStage.DELETE, TaskRuntimeQueueCategory.FILES))
    }

    @Test
    fun corruptedQueueFileFailsFastOnPeek() {
        val store = TestTaskRuntimePersistenceStore()
        store.appendQueueEntries(
            taskKey = 603L,
            stage = TaskRuntimeStage.COPY,
            category = TaskRuntimeQueueCategory.FILES,
            entries = listOf(queueEntry("copy-1", "/src/a.txt", "/dest/a.txt", 0)),
        )
        store.corruptedQueueFiles += TestTaskRuntimePersistenceStore.QueueFileKey(
            603L,
            TaskRuntimeStage.COPY,
            TaskRuntimeQueueCategory.FILES,
            "000001.pb64l",
        )

        assertFailsWith<IllegalStateException> {
            store.peekNextQueueEntry(603L, TaskRuntimeStage.COPY, TaskRuntimeQueueCategory.FILES)
        }
    }

    private fun queueEntry(
        entryId: String,
        srcPath: String,
        destPath: String,
        order: Int,
    ): TaskRuntimeQueueEntry {
        return TaskRuntimeQueueEntry(
            entryId = entryId,
            stage = TaskRuntimeStage.COPY,
            kind = TaskRuntimeEntryKind.FILE_COPY,
            src = TaskRuntimeEndpointRef(
                protocol = FileProtocol.Local,
                path = srcPath,
            ),
            dest = TaskRuntimeEndpointRef(
                protocol = FileProtocol.Local,
                path = destPath,
            ),
            order = order,
        )
    }

    private fun directoryEntry(
        entryId: String,
        srcPath: String,
        destPath: String,
        order: Int,
    ): TaskRuntimeQueueEntry {
        return TaskRuntimeQueueEntry(
            entryId = entryId,
            stage = TaskRuntimeStage.COPY,
            kind = TaskRuntimeEntryKind.DIRECTORY_CREATE,
            src = TaskRuntimeEndpointRef(
                protocol = FileProtocol.Local,
                path = srcPath,
            ),
            dest = TaskRuntimeEndpointRef(
                protocol = FileProtocol.Local,
                path = destPath,
            ),
            isDirectory = true,
            order = order,
        )
    }
}
