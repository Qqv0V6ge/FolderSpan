package com.folderspan.browser

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class BrowserExternalPathPolicyTest {
    @Test
    fun normalizesDropAndClipboardRelativePathsWithoutChangingStructure() {
        assertEquals("folder/nested/file.txt", normalizeBrowserExternalRelativePath("/folder\\nested//file.txt"))
        assertEquals("empty", normalizeBrowserExternalRelativePath("empty/"))
    }

    @Test
    fun rejectsTraversalAndInvalidNames() {
        assertNull(normalizeBrowserExternalRelativePath("../secret.txt"))
        assertNull(normalizeBrowserExternalRelativePath("folder/./file.txt"))
        assertNull(normalizeBrowserExternalRelativePath("bad\u0000name"))
        assertNull(normalizeBrowserExternalRelativePath("  "))
    }
}
