package com.folderspan.root

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RootAvailabilityTest {
    @Test
    fun hasSuBinaryUsesKnownPathsBeforeShellLookup() {
        val result = RootAvailability.hasSuBinary(
            pathExists = { path -> path == "/system/xbin/su" },
            commandLookup = { "" }
        )

        assertTrue(result)
    }

    @Test
    fun hasSuBinaryUsesShellLookupWhenKnownPathsAreMissing() {
        val result = RootAvailability.hasSuBinary(
            pathExists = { false },
            commandLookup = { "/sbin/su" }
        )

        assertTrue(result)
    }

    @Test
    fun hasSuBinaryReturnsFalseWhenNoPathOrLookupMatches() {
        val result = RootAvailability.hasSuBinary(
            pathExists = { false },
            commandLookup = { "" }
        )

        assertFalse(result)
    }
}
