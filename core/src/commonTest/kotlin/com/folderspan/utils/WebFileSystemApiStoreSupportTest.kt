package com.folderspan.utils

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class WebFileSystemApiStoreSupportTest {
    @Test
    fun taskRuntimeTemporaryFilesAreNotPersistedToOpfs() {
        assertFalse(
            shouldPersistWebFileSystemApiPath(
                path = "/tmp/task-runtime/1779777366020/task.pb64.tmp",
                cachePath = "/tmp",
            )
        )
        assertFalse(
            shouldPersistWebFileSystemApiPath(
                path = "/tmp/task-runtime/1779777366020/pending/copy/files/000001.pb64l.tmp",
                cachePath = "/tmp",
            )
        )
    }

    @Test
    fun finalRuntimeFilesAndUserTemporaryFilesArePersistedToOpfs() {
        assertTrue(
            shouldPersistWebFileSystemApiPath(
                path = "/tmp/task-runtime/1779777366020/task.pb64",
                cachePath = "/tmp",
            )
        )
        assertTrue(
            shouldPersistWebFileSystemApiPath(
                path = "/tmp/downloads/user-file.tmp",
                cachePath = "/tmp",
            )
        )
    }
}
