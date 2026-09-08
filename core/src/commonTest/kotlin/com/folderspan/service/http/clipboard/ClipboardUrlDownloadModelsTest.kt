package com.folderspan.service.http.clipboard

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ClipboardUrlDownloadModelsTest {
    private val native = ClipboardUrlDownloadPlatformCapabilities(
        platformName = "Desktop",
        canSetCookie = true,
        canSetUserAgent = true,
        canControlAutomaticHeaders = true,
        browserCredentialsOmitted = false,
    )

    @Test
    fun defaultsAndValidRangesCreateAnEphemeralTask() {
        val draft = draft()
        val result = assertIs<ClipboardUrlDownloadConfigResult.Valid>(draft.validate())
        assertEquals(2, result.config.retries)
        assertEquals(1, result.config.threads)
        assertEquals("a=b", result.config.cookie)
        assertEquals("FolderSpan/1.0 (Desktop) ClipboardUrlDownload/1", result.config.userAgent)
    }

    @Test
    fun invalidRangesAndHeaderInjectionAreRejected() {
        val result = assertIs<ClipboardUrlDownloadConfigResult.Invalid>(
            draft().copy(
                retries = "6",
                threads = "0",
                cookie = "a=b\r\nAuthorization: secret",
                userAgent = "agent\nInjected: yes",
            ).validate()
        )
        assertTrue(result.errors.retries != null)
        assertTrue(result.errors.threads != null)
        assertTrue(result.errors.cookie != null)
        assertTrue(result.errors.userAgent != null)
    }

    @Test
    fun browserDropsUncontrollableSensitiveFields() {
        val browser = native.copy(
            platformName = "Web",
            canSetCookie = false,
            canSetUserAgent = false,
            canControlAutomaticHeaders = false,
            browserCredentialsOmitted = true,
        )
        val result = assertIs<ClipboardUrlDownloadConfigResult.Valid>(
            draft().copy(
                cookie = "bad value without equals",
                userAgent = "",
                automaticHeaders = true,
                capabilities = browser,
            ).validate()
        )
        assertEquals("", result.config.cookie)
        assertEquals("", result.config.userAgent)
        assertFalse(result.config.automaticHeaders)
    }

    @Test
    fun stateMachineIsSingleFlightAndClearsSensitiveValues() {
        val machine = ClipboardUrlDownloadStateMachine()
        assertEquals(ClipboardUrlDraftOpenResult.Opened, machine.openDraft(draft()))
        assertEquals(ClipboardUrlDraftOpenResult.AlreadyRunning, machine.openDraft(draft()))

        val confirmed = assertIs<ClipboardUrlDraftConfirmResult.Confirmed>(machine.confirm(draft()))
        assertEquals("a=b", confirmed.config.cookie)
        assertEquals(ClipboardUrlDraftOpenResult.AlreadyRunning, machine.openDraft(draft()))

        machine.cancel()
        assertEquals("", confirmed.config.cookie)
        assertEquals("", confirmed.config.userAgent)
        assertNull(machine.activeConfigForTest())
        assertEquals(ClipboardUrlDraftOpenResult.Opened, machine.openDraft(draft()))
    }

    @Test
    fun invalidConfirmationKeepsTheDraftOpen() {
        val machine = ClipboardUrlDownloadStateMachine()
        machine.openDraft(draft())
        val result = machine.confirm(draft().copy(threads = "9"))
        assertIs<ClipboardUrlDraftConfirmResult.Invalid>(result)
        val state = assertIs<ClipboardUrlDownloadState.Configuring>(machine.state.value)
        assertTrue(state.errors.threads != null)
    }

    private fun draft() = ClipboardUrlDownloadDraft(
        rawText = "https://example.com/file.zip?token=secret",
        url = "https://example.com/file.zip?token=secret",
        targetSummary = "https://example.com",
        cookie = "a=b",
        userAgent = "FolderSpan/1.0 (Desktop) ClipboardUrlDownload/1",
        capabilities = native,
    )
}
