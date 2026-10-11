package com.folderspan.utils

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AppVersionTest {
    @Test
    fun equalVersionsAreNotNewer() {
        assertEquals(SemanticAppVersion(1, 2, 3), parseSemanticAppVersion("1.2.3"))
        assertFalse(isNewerAppVersion("1.2.3", "1.2.3"))
        assertFalse(isNewerAppVersion(" 1.2.3 ", "1.2.3"))
    }

    @Test
    fun greaterPatchMinorOrMajorIsNewer() {
        assertTrue(isNewerAppVersion("1.2.4", "1.2.3"))
        assertTrue(isNewerAppVersion("1.3.0", "1.2.9"))
        assertTrue(isNewerAppVersion("2.0.0", "1.9.9"))
    }

    @Test
    fun olderVersionsAreNotNewer() {
        assertFalse(isNewerAppVersion("1.2.2", "1.2.3"))
        assertFalse(isNewerAppVersion("1.1.9", "1.2.0"))
        assertFalse(isNewerAppVersion("0.9.9", "1.0.0"))
    }

    @Test
    fun missingPartsAreRejected() {
        assertNull(parseSemanticAppVersion("1.2"))
        assertNull(parseSemanticAppVersion("1"))
        assertNull(parseSemanticAppVersion(""))
        assertNull(parseSemanticAppVersion("1.2.3.4"))
        assertFalse(isNewerAppVersion("1.2", "1.2.0"))
        assertFalse(isNewerAppVersion("1.2.3", "1.2"))
    }

    @Test
    fun suffixesAndLeadingVAreRejectedByTheHelper() {
        assertNull(parseSemanticAppVersion("1.2.3-beta"))
        assertNull(parseSemanticAppVersion("v1.2.3"))
        assertNull(parseSemanticAppVersion("1.2.3+build"))
        assertFalse(isNewerAppVersion("v1.2.4", "1.2.3"))
        assertFalse(isNewerAppVersion("1.2.4-beta", "1.2.3"))
    }

    @Test
    fun currentAppVersionIsAComparableTriple() {
        assertNotNull(parseSemanticAppVersion(currentAppVersion()))
    }
}
