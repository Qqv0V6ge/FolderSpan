package com.folderspan.service.network

import com.folderspan.exception.NetworkUnsupportedException
import com.hierynomus.msfscc.fileinformation.FileBasicInformation
import org.apache.commons.net.ftp.FTPClient
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class NetworkModifiedTimeTest {
    @Test
    fun ftpUsesUtcMfmtAndPreservesSpacesInThePath() {
        val client = RecordingFtpClient(213)
        assertTrue(setFtpModificationTime(client, "/photos/summer trip.jpg", Instant.parse("2024-04-12T06:07:08.999Z").toEpochMilli()))
        assertEquals(listOf("MFMT" to "20240412060708 /photos/summer trip.jpg"), client.commands)
    }

    @Test
    fun ftpReportsUnsupportedAndPermissionFailuresSeparately() {
        for (code in listOf(202, 500, 502, 504)) {
            assertFailsWith<NetworkUnsupportedException> {
                setFtpModificationTime(RecordingFtpClient(code), "/photo.jpg", 1_000)
            }
        }
        assertFailsWith<IllegalStateException> {
            setFtpModificationTime(RecordingFtpClient(550), "/photo.jpg", 1_000)
        }
    }

    @Test
    fun ftpRejectsInvalidTimeAndCommandInjectionBeforeSending() {
        val client = RecordingFtpClient(213)
        assertFailsWith<IllegalArgumentException> { setFtpModificationTime(client, "/photo.jpg", 0) }
        assertFailsWith<IllegalArgumentException> { setFtpModificationTime(client, "/photo.jpg\r\nDELE /other", 1_000) }
        assertTrue(client.commands.isEmpty())
    }

    @Test
    fun smbOnlyChangesLastWriteTimeAndKeepsOtherAttributesUntouched() {
        val millis = Instant.parse("2024-04-12T06:07:08.123Z").toEpochMilli()
        val information = smbModifiedTimeInformation(millis)
        assertEquals(millis, information.lastWriteTime.toEpochMillis())
        assertEquals(FileBasicInformation.DONT_SET, information.creationTime)
        assertEquals(FileBasicInformation.DONT_SET, information.lastAccessTime)
        assertEquals(FileBasicInformation.DONT_SET, information.changeTime)
        assertEquals(0L, information.fileAttributes)
        assertFailsWith<IllegalArgumentException> { smbModifiedTimeInformation(0) }
    }

    private class RecordingFtpClient(private val reply: Int) : FTPClient() {
        val commands = mutableListOf<Pair<String, String>>()
        override fun sendCommand(command: String?, args: String?): Int {
            commands += command.orEmpty() to args.orEmpty()
            return reply
        }
        override fun getReplyString(): String = "Test server reply $reply"
    }
}
