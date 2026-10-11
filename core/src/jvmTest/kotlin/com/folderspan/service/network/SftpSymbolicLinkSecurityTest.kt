package com.folderspan.service.network

import com.folderspan.data.main.network.NetworkDriveExtras
import com.folderspan.data.main.network.SftpDriveExtras
import com.folderspan.exception.AuthorityException
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.apache.sshd.common.config.keys.PublicKeyEntry
import org.apache.sshd.common.file.virtualfs.VirtualFileSystemFactory
import org.apache.sshd.common.keyprovider.KeyPairProvider
import org.apache.sshd.server.SshServer
import org.apache.sshd.server.auth.password.PasswordAuthenticator
import org.apache.sshd.sftp.SftpModuleProperties
import org.apache.sshd.sftp.server.SftpSubsystemFactory
import java.nio.file.Files
import java.nio.file.Paths
import java.security.KeyPairGenerator
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SftpSymbolicLinkSecurityTest {
    @Test
    fun allOperationsRejectLeafParentDanglingAndCyclicLinks() = runBlocking {
        val keyPair = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
        for (version in listOf(3, 6)) {
            val directory = Files.createTempDirectory("sftp-link-security")
            val owner = UUID.randomUUID().toString()
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
                Files.createDirectory(directory.resolve("ordinary"))
                Files.writeString(directory.resolve("ordinary/payload.txt"), "payload")
                mapOf("file-link" to "ordinary/payload.txt", "dir-link" to "ordinary", "dangling" to "missing", "cycle" to ".")
                    .forEach { (name, target) -> Files.createSymbolicLink(directory.resolve(name), Paths.get(target)) }
                server.start()
                val config = NetworkConnectionConfig(
                    "SFTP", "127.0.0.1:${server.port}", "test", "password",
                    extras = NetworkDriveExtras(sftp = SftpDriveExtras(
                        knownHosts = "[127.0.0.1]:${server.port} ${PublicKeyEntry.toString(keyPair.public)}",
                    )), sessionOwner = owner,
                )
                val client = SftpNetworkClient(config)
                withTimeout(30_000) {
                    verifyRemoteSymbolicLinkRejection(client, "")
                    for (path in listOf("dir-link/", "\\dir-link\\", "//dir-link/")) {
                        assertTrue(client.list(path).exceptionOrNull() is AuthorityException)
                    }
                    for (path in listOf("file-link", "dir-link/payload.txt", "\\dir-link\\payload.txt")) {
                        assertTrue(client.downloadByChunks(path, 7) { _, _ -> error("链接不得回调") }.exceptionOrNull() is AuthorityException)
                    }
                    assertEquals(listOf("payload.txt"), client.list("ordinary").getOrThrow().map { it.name })
                    assertEquals(listOf("ordinary/payload.txt"), client.list("ordinary").getOrThrow().map { it.path })
                    assertTrue(SftpNetworkClient(config.copy(pathSeparator = "/dir-link", sessionOwner = owner)).list("").getOrThrow().isNotEmpty())
                    assertTrue(client.createFile("/ordinary/missing-parent/new").isFailure)
                    assertTrue(client.createFile("/ordinary/payload.txt/new").isFailure)
                }
            } finally {
                SftpNetworkClient.closeOwner(owner)
                server.stop(true)
                Files.walk(directory).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists) }
            }
        }
    }
}
