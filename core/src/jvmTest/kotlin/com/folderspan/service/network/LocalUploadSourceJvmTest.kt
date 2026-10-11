package com.folderspan.service.network

import strings.AppStrings

import com.folderspan.exception.AuthorityException
import org.apache.commons.net.ftp.FTPSClient
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LocalUploadSourceJvmTest {
    @Test
    fun openLocalSourceNoFollowRejectsSymbolicLinkWithoutReadingTarget() {
        val directory = Files.createTempDirectory("local-upload-nofollow-")
        val secret = directory.resolve("secret.bin")
        val link = directory.resolve("alias.bin")
        try {
            Files.write(secret, byteArrayOf(7, 7, 7))
            Files.createSymbolicLink(link, secret)
            val error = runCatching { openLocalSourceNoFollow(link.toString()) }.exceptionOrNull()
            assertTrue(error is AuthorityException || error?.message.orEmpty().contains(AppStrings.legacy_symbolic_link_marker))
            assertContentEquals(byteArrayOf(7, 7, 7), Files.readAllBytes(secret))
        } finally {
            Files.deleteIfExists(link)
            Files.deleteIfExists(secret)
            Files.deleteIfExists(directory)
        }
    }

    @Test
    fun openLocalSinkNoFollowRejectsSymbolicLinkWithoutWritingTarget() {
        val directory = Files.createTempDirectory("local-sink-nofollow-")
        val secret = directory.resolve("secret.bin")
        val link = directory.resolve("alias.bin")
        try {
            Files.write(secret, byteArrayOf(9, 9, 9))
            Files.createSymbolicLink(link, secret)
            val error = runCatching { openLocalSinkNoFollow(link.toString()) }.exceptionOrNull()
            assertTrue(error is AuthorityException || error?.message.orEmpty().contains(AppStrings.legacy_symbolic_link_marker))
            assertContentEquals(byteArrayOf(9, 9, 9), Files.readAllBytes(secret))
        } finally {
            Files.deleteIfExists(link)
            Files.deleteIfExists(secret)
            Files.deleteIfExists(directory)
        }
    }

    @Test
    fun ftpsTransportEnablesEndpointChecking() {
        val client = createFtpTransportClient(ftpsEnabled = true)
        assertTrue(client is FTPSClient)
        assertTrue(client.isEndpointCheckingEnabled)
        val plain = createFtpTransportClient(ftpsEnabled = false)
        assertFalse(plain is FTPSClient)
    }
}
