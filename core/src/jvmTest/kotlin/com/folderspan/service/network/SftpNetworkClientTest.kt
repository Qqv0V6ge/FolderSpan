package com.folderspan.service.network

import com.folderspan.data.main.network.Network
import com.folderspan.data.main.network.NetworkDriveExtras
import com.folderspan.data.main.network.SftpDriveExtras
import com.folderspan.service.operation.TraversalParallelismConfig
import com.folderspan.service.operation.collectDirectoryEntriesAdaptive
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.apache.sshd.common.config.keys.PublicKeyEntry
import org.apache.sshd.common.file.virtualfs.VirtualFileSystemFactory
import org.apache.sshd.common.keyprovider.KeyPairProvider
import org.apache.sshd.common.session.Session
import org.apache.sshd.common.session.SessionListener
import org.apache.sshd.server.SshServer
import org.apache.sshd.server.auth.password.PasswordAuthenticator
import org.apache.sshd.sftp.SftpModuleProperties
import org.apache.sshd.sftp.server.SftpSubsystemFactory
import java.nio.file.Files
import java.nio.file.Paths
import java.security.KeyPairGenerator
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SftpNetworkClientTest {
    @Test
    fun parallelClientsReuseAuthenticationAndAllowNestedTransfers() = runBlocking {
        val keyPair = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
        val directory = Files.createTempDirectory("sftp-session-reuse-test")
        val authentications = AtomicInteger()
        val connections = AtomicInteger()
        val server = SshServer.setUpDefaultServer().apply {
            host = "127.0.0.1"
            port = 0
            keyPairProvider = KeyPairProvider.wrap(keyPair)
            passwordAuthenticator = PasswordAuthenticator { _, _, _ ->
                authentications.incrementAndGet()
                Thread.sleep(100)
                true
            }
            subsystemFactories = listOf(SftpSubsystemFactory.Builder().build())
            fileSystemFactory = VirtualFileSystemFactory(directory)
            addSessionListener(object : SessionListener {
                override fun sessionCreated(session: Session) {
                    connections.incrementAndGet()
                }
            })
        }
        try {
            Files.writeString(directory.resolve("payload.txt"), "payload")
            server.start()
            val network = Network(
                "Test", "/", "SFTP", "127.0.0.1:${server.port}", "test", "password",
                extras = NetworkDriveExtras(sftp = SftpDriveExtras(
                    knownHosts = "[127.0.0.1]:${server.port} ${PublicKeyEntry.toString(keyPair.public)}",
                )),
            )
            val results = withTimeout(30_000) {
                List(24) { async(Dispatchers.IO) { SftpNetworkClient(network).list("/") } }.awaitAll()
            }
            assertTrue(results.all { it.isSuccess }, results.mapNotNull { it.exceptionOrNull() }.toString())
            assertEquals(1, connections.get())
            assertEquals(1, authentications.get())
            assertTrue(SftpNetworkClient(network).list("/missing").isFailure)
            assertTrue(SftpNetworkClient(network).list("/").isSuccess)
            assertEquals(1, authentications.get())

            val streaming = AtomicInteger()
            val allStreaming = CompletableDeferred<Unit>()
            withTimeout(20_000) {
                List(8) { index ->
                    async(Dispatchers.IO) {
                        SftpNetworkClient(network).downloadByChunks("/payload.txt", 7) { chunk, _ ->
                            if (streaming.incrementAndGet() == 8) allStreaming.complete(Unit)
                            allStreaming.await()
                            var sent = false
                            SftpNetworkClient(network).uploadFromSource("/copy-$index", chunk.size.toLong(), { _, _ -> }) {
                                if (sent) null else chunk.also { sent = true }
                            }.getOrThrow()
                            Result.success(Unit)
                        }.getOrThrow()
                    }
                }.awaitAll()
            }
            repeat(8) { index -> assertEquals("payload", Files.readString(directory.resolve("copy-$index"))) }
            assertEquals(1, connections.get())
            assertEquals(1, authentications.get())

            val wrongPassword = Network(
                "Wrong password", "/", "SFTP", network.host, network.username, "wrong", extras = network.extras,
            )
            server.passwordAuthenticator = PasswordAuthenticator { _, password, _ -> password == "password" }
            assertTrue(SftpNetworkClient(wrongPassword).list("/").isFailure)
            val wrongKey = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
            val wrongTrust = Network(
                "Wrong host key", "/", "SFTP", network.host, network.username, network.password,
                extras = NetworkDriveExtras(sftp = SftpDriveExtras(
                    knownHosts = "[127.0.0.1]:${server.port} ${PublicKeyEntry.toString(wrongKey.public)}",
                )),
            )
            assertTrue(SftpNetworkClient(wrongTrust).list("/").isFailure)
            assertTrue(SftpNetworkClient(network).list("/").isSuccess)

            val oldSession = server.activeSessions.single { it.isAuthenticated }
            oldSession.close(true).await(5_000)
            // 等客户端处理服务器的关闭消息，再验证下一次操作只重新认证一次。
            withTimeout(10_000) {
                while (SftpNetworkClient(network).list("/").isFailure) delay(20)
            }
            assertEquals(4, connections.get())
            assertTrue(SftpNetworkClient(network).list("/").isSuccess)
            assertEquals(4, connections.get())
        } finally {
            server.stop(true)
            Files.walk(directory).use { paths ->
                paths.sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) }
            }
        }
    }

    @Test
    fun directoryTraversalPreservesLinkMetadataWithoutFollowingLinks() = runBlocking {
        val keyPair = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
        for (version in listOf(3, 6)) {
            val directory = Files.createTempDirectory("sftp-traversal-test")
            val server = SshServer.setUpDefaultServer().apply {
                host = "127.0.0.1"
                port = 0
                keyPairProvider = KeyPairProvider.wrap(keyPair)
                passwordAuthenticator = PasswordAuthenticator { _, _, _ -> true }
                subsystemFactories = listOf(SftpSubsystemFactory.Builder().build())
                fileSystemFactory = VirtualFileSystemFactory(directory)
                SftpModuleProperties.SFTP_VERSION.set(this, version)
            }
            try {
                Files.createDirectory(directory.resolve("nested"))
                Files.writeString(directory.resolve("nested/payload.txt"), "payload")
                val links = mapOf(
                    "dir-link" to "nested",
                    "file-link" to "nested/payload.txt",
                    "dangling" to "missing",
                    "cycle" to ".",
                )
                links.forEach { (name, target) ->
                    Files.createSymbolicLink(directory.resolve(name), Paths.get(target))
                }
                server.start()
                val network = Network(
                    "Test", "/", "SFTP", "127.0.0.1:${server.port}", "test", "password",
                    extras = NetworkDriveExtras(sftp = SftpDriveExtras(
                        knownHosts = "[127.0.0.1]:${server.port} ${PublicKeyEntry.toString(keyPair.public)}",
                    )),
                )
                val listedPaths = mutableListOf<String>()
                val rejectedPaths = mutableListOf<String>()
                val entries = withTimeout(20_000) {
                    collectDirectoryEntriesAdaptive(
                        root = network.getFile("/").getOrThrow(),
                        config = TraversalParallelismConfig(1, 1, 4),
                        pathSeparator = "/",
                        requireKnownSymbolicLinkMetadata = true,
                        rejectSymbolicLinkEntries = true,
                        onRejectedEntry = { entry, _ -> rejectedPaths += entry.path },
                    ) { parent ->
                        listedPaths += parent.path
                        network.getList(parent.path)
                    }
                }
                assertEquals(listOf("/", "/nested"), listedPaths)
                assertEquals(setOf("/nested", "/nested/payload.txt"), entries.map { it.path }.toSet())
                assertTrue(entries.all { it.isSymbolicLinkKnown && !it.isSymbolicLink })
                assertTrue(entries.single { it.path == "/nested" }.isDirectory)
                assertFalse(entries.single { it.path.endsWith("payload.txt") }.isDirectory)
                assertEquals(links.keys.map { "/$it" }.toSet(), rejectedPaths.toSet())

                val traversed = withTimeout(20_000) { network.traverse("/").toList() }
                assertTrue(traversed.all { it.isSuccess })
                val linkEntries = traversed.flatMap { it.getOrThrow() }.filter { it.name in links }
                assertEquals(links.size, linkEntries.size)
                assertTrue(linkEntries.all { it.isSymbolicLinkKnown && it.isSymbolicLink && !it.isDirectory })
            } finally {
                server.stop(true)
                Files.walk(directory).use { paths ->
                    paths.sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) }
                }
            }
        }
    }
}
