package com.folderspan.service.http.archive

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class FolderSpanArchiveCodecTest {
    @Test
    fun archiveRejectsUnsafeRelativePaths() {
        val unsafePaths = listOf(
            "",
            "/absolute.txt",
            "../escape.txt",
            "nested/../../escape.txt",
            "dir\\windows.txt",
        )

        unsafePaths.forEach { path ->
            assertFailsWith<IllegalArgumentException>("path=$path") {
                FolderSpanArchiveCodec.normalizeRelativePath(path)
            }
        }
    }

    @Test
    fun archiveTargetPathPreservesRootDestination() {
        assertEquals(
            "/nested/small.bin",
            FolderSpanArchiveCodec.buildTargetPath("/", "nested/small.bin", "/"),
        )
        assertEquals(
            "C:\\nested\\small.bin",
            FolderSpanArchiveCodec.buildTargetPath("C:\\", "nested/small.bin", "\\"),
        )
    }
}
