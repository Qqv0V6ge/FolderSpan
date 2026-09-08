package com.folderspan.service.network

import strings.AppStrings

import com.folderspan.exception.NetworkUnsupportedException
import org.apache.commons.net.ftp.FTPClient
import org.apache.sshd.sftp.client.SftpClient
import org.apache.sshd.sftp.client.extensions.CopyFileExtension
import org.apache.sshd.sftp.common.SftpConstants
import org.apache.sshd.sftp.common.SftpException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class JvmServerSideCopySupportTest {
    @Test
    fun ftpCopyUsesOnlySiteCpfrAndCptoCommands() {
        val client = RecordingFtpClient(350, 250)

        copyFileWithFtpSiteCommands(client, "/source/file.txt", "/target/file.txt")

        assertEquals(
            listOf(
                "SITE" to "CPFR /source/file.txt",
                "SITE" to "CPTO /target/file.txt",
            ),
            client.commands,
        )
    }

    @Test
    fun ftpCopyStopsWhenServerRejectsCpfr() {
        val client = RecordingFtpClient(500, 250)

        assertFailsWith<NetworkUnsupportedException> {
            copyFileWithFtpSiteCommands(client, "/source/file.txt", "/target/file.txt")
        }

        assertEquals(listOf("SITE" to "CPFR /source/file.txt"), client.commands)
    }

    @Test
    fun ftpCopyTreatsPositive202ReplyAsUnsupported() {
        val client = RecordingFtpClient(202)

        assertFailsWith<NetworkUnsupportedException> {
            copyFileWithFtpSiteCommands(client, "/source/file.txt", "/target/file.txt")
        }

        assertEquals(listOf("SITE" to "CPFR /source/file.txt"), client.commands)
    }

    @Test
    fun ftpCopyFailsWhenServerRejectsCpto() {
        val client = RecordingFtpClient(350, 550)

        assertFailsWith<IllegalStateException> {
            copyFileWithFtpSiteCommands(client, "/source/file.txt", "/target/file.txt")
        }

        assertEquals(2, client.commands.size)
    }

    @Test
    fun ftpCopyRejectsControlCommandInjectionBeforeSendingAnything() {
        val client = RecordingFtpClient()

        assertFailsWith<IllegalArgumentException> {
            copyFileWithFtpSiteCommands(client, "/source/file.txt\r\nDELE /victim", "/target/file.txt")
        }
        assertFailsWith<IllegalArgumentException> {
            copyFileWithFtpSiteCommands(client, "/source/file.txt", "/target/file.txt\nDELE /victim")
        }

        assertTrue(client.commands.isEmpty())
    }

    @Test
    fun sftpCopyChecksExtensionSupportBeforeInvokingIt() {
        val extension = RecordingCopyFileExtension(supported = false)

        assertFailsWith<NetworkUnsupportedException> {
            copyFileWithSftpExtension(extension, "/source/file.txt", "/target/file.txt")
        }

        assertFalse(extension.invoked)
    }

    @Test
    fun sftpCopyInvokesServerExtensionWithOverwriteEnabled() {
        val extension = RecordingCopyFileExtension(supported = true)

        copyFileWithSftpExtension(extension, "/source/file.txt", "/target/file.txt")

        assertTrue(extension.invoked)
        assertEquals("/source/file.txt", extension.sourcePath)
        assertEquals("/target/file.txt", extension.targetPath)
        assertTrue(extension.overwriteDestination)
    }

    @Test
    fun sftpCopyMapsRuntimeUnsupportedStatusToFallbackSignal() {
        val extension = RecordingCopyFileExtension(
            supported = true,
            copyFailure = SftpException(SftpConstants.SSH_FX_OP_UNSUPPORTED, "unsupported"),
        )

        assertFailsWith<NetworkUnsupportedException> {
            copyFileWithSftpExtension(extension, "/source/file.txt", "/target/file.txt")
        }

        assertTrue(extension.invoked)
    }

    private class RecordingFtpClient(vararg replies: Int) : FTPClient() {
        private val replies = ArrayDeque(replies.toList())
        val commands = mutableListOf<Pair<String, String>>()

        override fun sendCommand(command: String?, args: String?): Int {
            commands += command.orEmpty() to args.orEmpty()
            return replies.removeFirst()
        }

        override fun getReplyString(): String = "test reply"
    }

    private class RecordingCopyFileExtension(
        private val supported: Boolean,
        private val copyFailure: SftpException? = null,
    ) : CopyFileExtension {
        var invoked = false
        var sourcePath = ""
        var targetPath = ""
        var overwriteDestination = false

        override fun getName(): String = "copy-file"

        override fun isSupported(): Boolean = supported

        override fun getClient(): SftpClient = error(AppStrings.ui_test_jvm_server_side_copy_support_the_test_will_not_read_sftp_client)

        override fun copyFile(src: String, dst: String, overwriteDestination: Boolean) {
            invoked = true
            sourcePath = src
            targetPath = dst
            this.overwriteDestination = overwriteDestination
            copyFailure?.let { throw it }
        }
    }
}
