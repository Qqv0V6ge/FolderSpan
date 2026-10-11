package com.folderspan.editor

import com.folderspan.utils.FileAccessPermission
import com.folderspan.utils.PathUtils
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class EditorApplicationCacheLifecycleJvmTest {
    @Test
    fun applicationPrivateCachesApplyRemovalAndRetentionPolicies() = runTest {
        val key = "lifecycle-${System.nanoTime()}"

        val lineIndexes = ApplicationPrivateEditorLineIndexCache()
        lineIndexes.write(key, byteArrayOf(1, 2, 3))
        assertContentEquals(byteArrayOf(1, 2, 3), lineIndexes.read(key))
        lineIndexes.cleanup(maxEntries = 0)
        assertNull(lineIndexes.read(key))

        val recovery = ApplicationPrivateEditorRecoveryJournalStore()
        recovery.write(
            key,
            EditorRecoveryJournal(
                sourceSize = 0L,
                pageSize = DEFAULT_FILE_EDITOR_PAGE_SIZE,
                encoding = EditorTextEncoding.UTF8.name,
                currentPage = 0L,
                pages = emptyList(),
            ),
        ).getOrThrow()
        assertTrue(recovery.read(key).getOrThrow() != null)
        recovery.cleanup(maxEntries = 0)
        assertNull(recovery.read(key).getOrThrow())

        val backups = ApplicationPrivateEditorBackupStore()
        val backup = backups.createCompleteBackup(
            key = key,
            snapshot = FileEditorSourceSnapshot(size = 3L, revision = key),
            size = 3L,
            content = flowOf(byteArrayOf(4, 5, 6)),
        ).getOrThrow()
        assertTrue(PathUtils.exists(FileAccessPermission.Allowed, backup.path))
        backups.cleanup(maxEntries = 0)
        assertFalse(PathUtils.exists(FileAccessPermission.Allowed, backup.path))

    }
}
