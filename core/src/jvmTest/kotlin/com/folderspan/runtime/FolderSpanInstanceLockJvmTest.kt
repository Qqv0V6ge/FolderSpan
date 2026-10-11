package com.folderspan.runtime

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class FolderSpanInstanceLockJvmTest {
    @Test
    fun secondAcquireFailsUntilTheFirstLockIsReleased() {
        val directory = Files.createTempDirectory("folderspan-lock-")
        val first = FolderSpanInstanceLock.tryAcquire(directory)
        assertNotNull(first)
        val second = FolderSpanInstanceLock.tryAcquire(directory)
        assertNull(second)
        assertEquals(1, failIfRuntimeLocked(null))
        first.release()
        val third = FolderSpanInstanceLock.tryAcquire(directory)
        assertNotNull(third)
        assertEquals(0, failIfRuntimeLocked(third))
        third.release()
        assertTrue(Files.exists(directory.resolve(FolderSpanInstanceLock.FILE_NAME)))
    }
}
