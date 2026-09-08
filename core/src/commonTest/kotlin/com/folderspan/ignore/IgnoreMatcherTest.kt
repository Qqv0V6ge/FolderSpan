package com.folderspan.ignore

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class IgnoreMatcherTest {
    @Test
    fun matchesCommonIgnoreSyntax() {
        val matcher = IgnoreMatcher(
            IgnoreMatcher.parse(
                listOf(
                    "",
                    "# comment",
                    "\\#literal",
                    "*.log",
                    "!keep.log",
                    "build/",
                    "/root.txt",
                    "docs/**/tmp?.txt",
                )
            )
        )

        assertTrue(matcher.matches("#literal", isDirectory = false))
        assertTrue(matcher.matches("error.log", isDirectory = false))
        assertFalse(matcher.matches("keep.log", isDirectory = false))
        assertTrue(matcher.matches("build", isDirectory = true))
        assertTrue(matcher.matches("src/build/output.bin", isDirectory = false))
        assertTrue(matcher.matches("root.txt", isDirectory = false))
        assertFalse(matcher.matches("nested/root.txt", isDirectory = false))
        assertTrue(matcher.matches("docs/tmp1.txt", isDirectory = false))
        assertTrue(matcher.matches("docs/a/b/tmp2.txt", isDirectory = false))
        assertFalse(matcher.matches("docs/a/b/tmp20.txt", isDirectory = false))
    }

    @Test
    fun buildsPathPreferenceCandidatesFromCurrentPathToRoot() {
        assertEquals(
            listOf(
                "/a/b/c/tmp",
                "/a/b/c",
                "/a/b",
                "/a",
                "/",
            ), buildPathPreferenceCandidates("/a/b/c/tmp", "/")
        )
    }
}
