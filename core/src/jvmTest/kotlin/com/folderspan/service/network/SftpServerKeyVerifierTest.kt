package com.folderspan.service.network

import org.apache.sshd.client.keyverifier.RejectAllServerKeyVerifier
import org.apache.sshd.common.config.keys.PublicKeyEntry
import java.net.InetSocketAddress
import java.security.KeyPairGenerator
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

class SftpServerKeyVerifierTest {
    @Test
    fun missingKnownHostsRejectsEveryServerKey() {
        val verifier = resolveSftpServerKeyVerifier("", "sftp.example", 22)

        assertSame(RejectAllServerKeyVerifier.INSTANCE, verifier)
    }

    @Test
    fun invalidKnownHostsFailsClosedAsConfigurationError() {
        assertFailsWith<IllegalArgumentException> {
            resolveSftpServerKeyVerifier("not-a-valid-known-host-entry", "sftp.example", 22)
        }
    }

    @Test
    fun knownHostsRequiresMatchingHostAndKey() {
        val keyPair = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
        val encodedKey = PublicKeyEntry.toString(keyPair.public)
        val matching = resolveSftpServerKeyVerifier(
            knownHosts = "sftp.example $encodedKey",
            host = "sftp.example",
            port = 22,
        )
        val wrongHost = resolveSftpServerKeyVerifier(
            knownHosts = "other.example $encodedKey",
            host = "sftp.example",
            port = 22,
        )
        val address = InetSocketAddress("sftp.example", 22)

        assertTrue(matching.verifyServerKey(null, address, keyPair.public))
        assertFalse(wrongHost.verifyServerKey(null, address, keyPair.public))
    }
}
