package com.folderspan.service.mcp.auth

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class McpTokenFormatTest {
    @Test
    fun parserAcceptsOnlyTheExpectedTokenShape() {
        val token = "fmcp_0011223344556677_${"ab".repeat(32)}"
        val parsed = assertNotNull(parseMcpToken(token))

        assertEquals("0011223344556677", parsed.lookupId)
        assertEquals(32, parsed.secretBytes.size)
        assertNull(parseMcpToken("bearer_$token"))
        assertNull(parseMcpToken("fmcp_0011_${"ab".repeat(32)}"))
        assertNull(parseMcpToken("fmcp_0011223344556677_not-hex"))
    }

    @Test
    fun hashingMatchesSha256VectorAndComparisonIsExact() {
        val hash = hashMcpTokenSecret("abc".encodeToByteArray())

        assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad", hash)
        assertTrue(constantTimeTokenHashEquals(hash, hash))
        assertFalse(constantTimeTokenHashEquals(hash, hash.dropLast(1) + "0"))
        assertFalse(constantTimeTokenHashEquals(hash, hash + "00"))
    }
}
