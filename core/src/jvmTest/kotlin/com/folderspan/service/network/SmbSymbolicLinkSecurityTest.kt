package com.folderspan.service.network

import com.folderspan.data.main.network.NetworkDriveExtras
import com.folderspan.data.main.network.SmbDriveExtras
import com.folderspan.exception.AuthorityException
import com.hierynomus.msdtyp.AccessMask
import com.hierynomus.msfscc.FileAttributes
import com.hierynomus.mssmb2.SMB2CreateDisposition
import com.hierynomus.mssmb2.SMB2CreateOptions
import com.hierynomus.mssmb2.SMB2ShareAccess
import com.hierynomus.smbj.SMBClient
import com.hierynomus.smbj.SmbConfig
import com.hierynomus.smbj.auth.AuthenticationContext
import com.hierynomus.smbj.share.DiskShare
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assume.assumeTrue
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import java.nio.file.Paths
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

// 专用 Samba 4.23+ 测试共享，需支持目录租约且 case sensitive = yes；目录是共享的本地挂载点。
class SmbSymbolicLinkSecurityTest {
    @Test
    fun allOperationsAndRecursiveDeleteRejectReparsePoints() = runBlocking {
        val endpoint = System.getenv("FOLDERSPAN_TEST_SMB_ENDPOINT")
        val shareDirectory = System.getenv("FOLDERSPAN_TEST_SMB_SHARE_DIRECTORY")
        assumeTrue("未配置专用 SMB 链接测试共享", !endpoint.isNullOrBlank() && !shareDirectory.isNullOrBlank())
        val owner = UUID.randomUUID().toString()
        val root = "fs52-$owner"
        val directory = Paths.get(shareDirectory!!).resolve(root)
        val config = NetworkConnectionConfig(
            "SMB", endpoint!!,
            System.getenv("FOLDERSPAN_TEST_USER") ?: "test",
            System.getenv("FOLDERSPAN_TEST_PASSWORD") ?: "test-password",
            extras = NetworkDriveExtras(smb = SmbDriveExtras(share = System.getenv("FOLDERSPAN_TEST_SMB_SHARE") ?: "test")),
            sessionOwner = owner,
        )
        try {
            Files.createDirectories(directory.resolve("ordinary"))
            Files.writeString(directory.resolve("ordinary/payload.txt"), "payload")
            Files.createDirectory(directory.resolve("remove-tree"))
            SMBClient(SmbConfig.builder().withSigningRequired(true).build()).use { raw ->
                val key = networkSessionKey(config, 445)
                val connection = raw.connect(key.host, key.port)
                assertTrue(connection.connectionContext.supportsDirectoryLeasing())
                val session = connection.authenticate(AuthenticationContext(config.username, config.password.toCharArray(), null))
                val share = session.connectShare(config.extras.smb.share) as DiskShare
                for ((name, target) in mapOf(
                    "file-link" to "ordinary\\payload.txt", "dir-link" to "ordinary", "dangling" to "missing", "cycle" to ".",
                    "ORDINARY" to "ordinary",
                    "remove-tree\\dir-link" to "..\\ordinary",
                )) createReparsePoint(share, "$root\\$name", target)
            }
            val client = SmbNetworkClient(config)
            withTimeout(60_000) {
                assertEquals(listOf("payload.txt"), client.list("/$root/ordinary").getOrThrow().map { it.name })
                assertTrue(client.list("/$root/ORDINARY").exceptionOrNull() is AuthorityException, "目录缓存不得绕过链接检查")
                verifyRemoteSymbolicLinkRejection(client, "/$root")
                assertTrue(client.list("$root\\dir-link\\").exceptionOrNull() is AuthorityException)
                assertTrue(client.delete("/$root/remove-tree", true).exceptionOrNull() is AuthorityException)
                assertEquals("payload", Files.readString(directory.resolve("ordinary/payload.txt")))
                assertTrue(client.createFile("/$root/missing-parent/new").isFailure)
                assertTrue(client.createFile("/$root/ordinary/payload.txt/new").isFailure)
                assertTrue(client.list("").isSuccess)
            }
        } finally {
            SmbNetworkClient.closeOwner(owner)
            if (Files.exists(directory)) Files.walk(directory).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists) }
        }
    }

    private fun createReparsePoint(share: DiskShare, path: String, target: String) {
        val name = target.toByteArray(Charsets.UTF_16LE)
        val blob = ByteBuffer.allocate(20 + name.size * 2).order(ByteOrder.LITTLE_ENDIAN)
            .putInt(0xa000000c.toInt()).putShort((12 + name.size * 2).toShort()).putShort(0)
            .putShort(0).putShort(name.size.toShort()).putShort(name.size.toShort()).putShort(name.size.toShort())
            .putInt(1).put(name).put(name).array()
        share.open(path, setOf(AccessMask.FILE_READ_ATTRIBUTES, AccessMask.FILE_WRITE_ATTRIBUTES, AccessMask.DELETE),
            setOf(FileAttributes.FILE_ATTRIBUTE_NORMAL), SMB2ShareAccess.ALL, SMB2CreateDisposition.FILE_CREATE,
            setOf(SMB2CreateOptions.FILE_OPEN_REPARSE_POINT)).use { entry ->
            entry.ioctl(0x000900a4, true, blob, 0, blob.size)
            val actual = entry.ioctl(0x000900a8, true, byteArrayOf(), 0, 0, 1024)
            assertEquals(0xa000000c.toInt(), ByteBuffer.wrap(actual).order(ByteOrder.LITTLE_ENDIAN).int)
        }
    }
}
