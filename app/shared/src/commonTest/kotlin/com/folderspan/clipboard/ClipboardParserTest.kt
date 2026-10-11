package com.folderspan.clipboard

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ClipboardParserTest {
    @Test
    fun openClipboardParsingStillTreatsPlainPathsAndFileUrlsAsNavigationCandidates() {
        val result = parseClipboardContent(
            ClipboardContent(
                texts = listOf("/tmp/from-text.txt\nfile:///tmp/from-url.txt"),
                filePaths = emptyList(),
            )
        )

        assertEquals(listOf("/tmp/from-text.txt", "/tmp/from-url.txt"), result.paths)
        assertTrue(result.urls.isEmpty())
    }


    @Test
    fun parseClipboardContent_detectsUrls() {
        val content = ClipboardContent(texts = listOf("  https://example.com/file.zip?token=secret#part  "))

        val result = parseClipboardContent(content)

        assertEquals(listOf("https://example.com/file.zip?token=secret#part"), result.urls)
        assertEquals("  https://example.com/file.zip?token=secret#part  ", result.rawText)
        assertTrue(result.paths.isEmpty())
    }

    @Test
    fun parseClipboardContent_acceptsStandaloneHttpAndBracketedIpv6() {
        assertEquals(
            "http://localhost:8080/file.bin",
            parseStandaloneHttpUrl("http://localhost:8080/file.bin"),
        )
        assertEquals(
            "https://[2001:db8::1]:8443/file.bin",
            parseStandaloneHttpUrl("https://[2001:db8::1]:8443/file.bin"),
        )
    }

    @Test
    fun parseClipboardContent_rejectsMixedMultipleCredentialAndOtherSchemeUrls() {
        val rejected = listOf(
            "download https://example.com/file.zip",
            "https://example.com/a\nhttps://example.com/b",
            "https://user:pass@example.com/file.zip",
            "ftp://example.com/file.zip",
            "https:///file.zip",
            "https://example.com:70000/file.zip",
            "https://example.com\\file.zip",
        )

        rejected.forEach { value ->
            assertNull(parseStandaloneHttpUrl(value), value)
        }
    }

    @Test
    fun parseClipboardContent_convertsFileUrlToPath() {
        val content = ClipboardContent(texts = listOf("file:///Users/test/report.txt"))

        val result = parseClipboardContent(content)

        assertEquals(listOf("/Users/test/report.txt"), result.paths)
        assertTrue(result.urls.isEmpty())
    }

    @Test
    fun parseClipboardContent_keepsWindowsPaths() {
        val content = ClipboardContent(texts = listOf("C:\\Users\\me\\file.txt"))

        val result = parseClipboardContent(content)

        assertEquals(listOf("C:\\Users\\me\\file.txt"), result.paths)
        assertTrue(result.urls.isEmpty())
    }

    @Test
    fun parseClipboardContent_handlesMixedEntries() {
        val content = ClipboardContent(
            texts = listOf("/tmp/data\nhttps://example.com"),
            filePaths = listOf("/var/log")
        )

        val result = parseClipboardContent(content)

        assertEquals(listOf("/var/log", "/tmp/data"), result.paths)
        assertTrue(result.urls.isEmpty())
        assertEquals(listOf("/var/log"), result.filePaths)
        assertEquals(listOf("/tmp/data\nhttps://example.com"), result.unrecognizedTexts)
    }
}
