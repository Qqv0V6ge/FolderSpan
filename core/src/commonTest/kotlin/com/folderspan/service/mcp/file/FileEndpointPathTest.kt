package com.folderspan.service.mcp.file

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class FileEndpointPathTest {
    @Test
    fun preservesTrailingFilenameWhitespace() {
        for (suffix in listOf(" ", "  ", "\t", "\n")) {
            val path = "/data/file$suffix"
            assertEquals(path, normalizeEndpointPath(path, "/"))
            assertEquals("/data" to "file$suffix", parentAndName(path, "/"))
            validateEndpointLeafName("file$suffix")
        }
        assertEquals("C:\\data\\file ", normalizeEndpointPath("C:\\data\\file ", "\\"))
    }

    @Test
    fun rejectsWhitespaceBeforeAbsolutePathInsteadOfRetargetingIt() {
        assertEquals(FileEndpointErrorCode.InvalidArgument,
            assertFailsWith<FileEndpointException> { normalizeEndpointPath(" /data/file ", "/") }.code)
    }

    @Test
    fun stillRejectsTraversalNullAndVerbatimPaths() {
        for (path in listOf("relative/file ", "/data/../file ", "/data/./file ", "/data/file\u0000 ")) {
            assertFailsWith<FileEndpointException> { normalizeEndpointPath(path, "/") }
        }
        assertFailsWith<FileEndpointException> { normalizeEndpointPath("\\\\?\\C:\\data\\file ", "\\") }
    }
}
