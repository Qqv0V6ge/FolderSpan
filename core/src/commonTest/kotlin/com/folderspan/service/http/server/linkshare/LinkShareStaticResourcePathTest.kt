package com.folderspan.service.http.server.linkshare

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class LinkShareStaticResourcePathTest {
    @Test
    fun acceptsBundledStaticFilesAndRejectsTraversal() {
        assertEquals("styles/shared-styles.css", resolveLinkShareStaticRelativePath("/static/styles/shared-styles.css"))
        assertEquals("streamsaver/sw.js", resolveLinkShareStaticRelativePath("/static/streamsaver/sw.js"))
        assertNull(resolveLinkShareStaticRelativePath("/static/"))
        assertNull(resolveLinkShareStaticRelativePath("/static"))
        assertNull(resolveLinkShareStaticRelativePath("/static/../shell/script.sh"))
        assertNull(resolveLinkShareStaticRelativePath("/static/foo/../../etc/passwd"))
        assertNull(
            resolveLinkShareStaticRelativePath(
                LinkShareHttpTarget.parse("/static/..%2f..%2fetc/passwd").path
            )
        )
        assertNull(
            resolveLinkShareStaticRelativePath(
                LinkShareHttpTarget.parse("/static/%2e%2e/%2e%2e/etc/passwd").path
            )
        )
        assertNull(resolveLinkShareStaticRelativePath("/favicon.ico"))
    }

    @Test
    fun rejectsNullBytesBackslashesAndEmptySegments() {
        assertNull(resolveLinkShareStaticRelativePath("/static/foo\u0000.css"))
        assertNull(resolveLinkShareStaticRelativePath("/static/foo\\bar.css"))
        assertNull(resolveLinkShareStaticRelativePath("/static/foo//bar.css"))
        assertNull(resolveLinkShareStaticRelativePath("/static/./shared-styles.css"))
    }

    @Test
    fun bundledResourcePathsStayInsideShareFileRoot() {
        assertEquals(
            "files/share-file/static/styles/shared-styles.css",
            normalizeLinkShareBundledResourcePath("files/share-file/static/styles/shared-styles.css"),
        )
        assertEquals(
            "files/share-file/shell/script.sh",
            normalizeLinkShareBundledResourcePath("/files/share-file/shell/script.sh"),
        )
        assertNull(normalizeLinkShareBundledResourcePath("files/share-file/static/../shell/script.sh"))
        assertNull(normalizeLinkShareBundledResourcePath("files/share-file/../../kotlin/SettingsUtils.kt"))
        assertNull(normalizeLinkShareBundledResourcePath("files/other/static/styles.css"))
    }
}
