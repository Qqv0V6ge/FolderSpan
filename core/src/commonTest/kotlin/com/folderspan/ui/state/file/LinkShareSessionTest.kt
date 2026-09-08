package com.folderspan.ui.state.file

import com.folderspan.data.file.FileSimpleInfo
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LinkShareSessionTest {
    @Test
    fun sessionMatchesClientFingerprintAndExpiry() {
        val session = session()

        assertTrue(session.matches("client-1", fingerprint(), nowMillis = 1_000L))
        assertTrue(session.matches(null, fingerprint(), nowMillis = 1_000L))
    }

    @Test
    fun sessionRejectsWrongClientFingerprintOrExpiredAccess() {
        val session = session()

        assertFalse(session.matches("client-2", fingerprint(), nowMillis = 1_000L))
        assertFalse(session.matches("client-1", fingerprint(ip = "10.0.0.3"), nowMillis = 1_000L))
        assertFalse(session.matches("client-1", fingerprint(userAgent = "Other"), nowMillis = 1_000L))
        assertFalse(session.matches("client-1", fingerprint(), nowMillis = 2_001L))
    }

    @Test
    fun ticketAndSessionExposeAuthorizationSnapshot() {
        val file = file("shared.txt")
        val session = session(files = listOf(file), allowHidden = true)
        val ticket = LinkShareTicket(
            token = "ticket",
            access = LinkShareDeviceAccess(allowHidden = true, allowUpload = true, files = listOf(file)),
            expiresAtMillis = 2_000L,
        )

        assertEquals(
            LinkShareAuthorization(allowHidden = true, allowUpload = true, files = listOf(file)),
            session.authorization()
        )
        assertEquals(
            LinkShareAuthorization(allowHidden = true, allowUpload = true, files = listOf(file)),
            ticket.authorization()
        )
    }

    private fun session(
        files: List<FileSimpleInfo> = emptyList(),
        allowHidden: Boolean = false,
        allowUpload: Boolean = true,
    ): LinkShareSession {
        return LinkShareSession(
            token = "session",
            clientId = "client-1",
            fingerprint = fingerprint(),
            access = LinkShareDeviceAccess(allowHidden, allowUpload, files),
            expiresAtMillis = 2_000L,
        )
    }

    private fun fingerprint(
        ip: String = "10.0.0.2",
        userAgent: String = "Browser",
    ): ShareTokenFingerprint {
        return ShareTokenFingerprint(clientIp = ip, userAgent = userAgent)
    }

    private fun file(name: String): FileSimpleInfo {
        return FileSimpleInfo(
            name = name,
            isDirectory = false,
            isHidden = false,
            path = "/$name",
            mineType = "text/plain",
            size = 1L,
            createdDate = 0L,
            updatedDate = 0L,
        )
    }
}
