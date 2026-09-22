package com.folderspan.service.network

import com.folderspan.data.main.network.Network
import com.russhwolf.settings.MapSettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.withTimeout
import org.apache.sshd.common.config.keys.PublicKeyEntry
import org.apache.sshd.common.config.keys.KeyUtils
import org.apache.sshd.common.digest.BuiltinDigests
import org.apache.sshd.common.file.virtualfs.VirtualFileSystemFactory
import org.apache.sshd.common.keyprovider.KeyPairProvider
import org.apache.sshd.server.SshServer
import org.apache.sshd.server.auth.password.PasswordAuthenticator
import org.apache.sshd.sftp.server.SftpSubsystemFactory
import strings.AppStrings
import java.net.InetSocketAddress
import java.nio.file.Files
import java.security.KeyPairGenerator
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SftpServerKeyVerifierTest {
    @Test
    fun blankKnownHostsRequestsTrustForTheActualServerKey() {
        val keyPair = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
        for (knownHosts in listOf("", " \t\n")) {
            val verifier = resolveSftpServerKeyVerifier(knownHosts, "sftp.example", 22, SftpHostKeyTrust(MapSettings()))
            val error = assertFailsWith<SftpUnknownHostKeyException> {
                verifier.verifyServerKey(null, InetSocketAddress("sftp.example", 22), keyPair.public)
            }
            assertEquals("sftp.example", error.hostKey.host)
            assertEquals(KeyUtils.getFingerPrint(BuiltinDigests.sha256, keyPair.public), error.hostKey.fingerprint)
        }
    }

    @Test
    fun firstConnectionWaitsForTrustBeforeAuthenticationAndRejectsChangedKeys() = runBlocking {
        val keyGenerator = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }
        val directory = Files.createTempDirectory("sftp-host-key-test")
        val authentications = AtomicInteger()
        val server = SshServer.setUpDefaultServer().apply {
            host = "127.0.0.1"
            port = 0
            keyPairProvider = KeyPairProvider.wrap(keyGenerator.generateKeyPair())
            passwordAuthenticator = PasswordAuthenticator { _, _, _ -> authentications.incrementAndGet(); true }
            subsystemFactories = listOf(SftpSubsystemFactory.Builder().build())
            fileSystemFactory = VirtualFileSystemFactory(directory)
        }
        try {
            server.start()
            val network = Network("Test", "/", "SFTP", "127.0.0.1:${server.port}", "test", "password")
            val settings = MapSettings()
            val trust = SftpHostKeyTrust(settings)
            trust.attachDialogHost()
            val client = SftpNetworkClient(network, trust)

            suspend fun awaitRequest(connection: Deferred<Result<List<NetworkFileEntry>>>): SftpHostKeyRequest =
                withTimeout(10_000) {
                    val prompt = async { trust.request.filterNotNull().first() }
                    select {
                        prompt.onAwait { it }
                        connection.onAwait { throw AssertionError("Connection ended before trust confirmation", it.exceptionOrNull()) }
                    }
                }

            val rejected = async(Dispatchers.Default) { client.list("/") }
            val firstRequest = awaitRequest(rejected)
            assertEquals(0, authentications.get())
            trust.respond(firstRequest, false)
            assertTrue(withTimeout(10_000) { rejected.await() }.isFailure)
            assertEquals(0, authentications.get())

            val accepted = async(Dispatchers.Default) { client.list("/") }
            val secondRequest = awaitRequest(accepted)
            assertEquals(0, authentications.get())
            trust.respond(secondRequest, true)
            assertTrue(withTimeout(10_000) { accepted.await() }.isSuccess)
            assertEquals(1, authentications.get())

            val restoredClient = SftpNetworkClient(network, SftpHostKeyTrust(settings))
            assertTrue(restoredClient.list("/").isSuccess)
            assertEquals(2, authentications.get())

            server.keyPairProvider = KeyPairProvider.wrap(keyGenerator.generateKeyPair())
            // 现有会话仍使用已校验的密钥；断开后，新握手必须拒绝服务器的新密钥。
            assertTrue(client.list("/").isSuccess)
            server.activeSessions.forEach { it.close(true).await(5_000) }
            withTimeout(10_000) {
                while (true) {
                    val error = client.list("/").exceptionOrNull()
                    if (generateSequence(error) { it.cause }.take(8).any {
                        it.message?.contains(AppStrings.ui_sftp_host_key_changed) == true
                    }) break
                    delay(20)
                }
            }
            assertEquals(2, authentications.get())
            assertNull(trust.request.value)
        } finally {
            server.stop(true)
            directory.toFile().deleteRecursively()
        }
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
        val otherKey = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
        assertFalse(matching.verifyServerKey(null, address, otherKey.public))
    }

    @Test
    fun nonDefaultPortRequiresMatchingPort() {
        val keyPair = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
        val knownHosts = "[10.0.0.168]:2220 ${PublicKeyEntry.toString(keyPair.public)}"
        val address = InetSocketAddress("10.0.0.168", 2220)

        val matching = resolveSftpServerKeyVerifier(knownHosts, "10.0.0.168", 2220)
        val wrongPort = resolveSftpServerKeyVerifier(knownHosts, "10.0.0.168", 22)

        assertTrue(matching.verifyServerKey(null, address, keyPair.public))
        assertFalse(wrongPort.verifyServerKey(null, address, keyPair.public))
    }
}
