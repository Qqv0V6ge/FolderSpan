package com.folderspan.shizuku

import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertTrue

class ShizukuFileServiceTest {
    @Test
    fun listChildrenReturnsEmptyListForEmptyDirectory() {
        val directory = createTempDirectory().toFile()
        val result = listFileChildrenForPrivilegedService(directory.absolutePath)

        assertTrue(result.isEmpty())
    }
}
