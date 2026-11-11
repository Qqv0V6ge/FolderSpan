package com.folderspan.service.network.nfs

import com.folderspan.data.main.network.NetworkDriveExtras
import com.folderspan.data.main.network.NfsDriveExtras
import com.folderspan.service.network.HttpDirectoryNetworkClient
import com.folderspan.service.network.NetworkConnectionConfig
import kotlinx.coroutines.*
import org.junit.Assume.assumeTrue
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.util.UUID
import kotlin.test.*

class StorageProtocolIntegrationTest {
    @Test fun nfs3CrudAndCancellation() = runBlocking { verifyNfs(3) }
    @Test fun nfs4CrudAndCancellation() = runBlocking { verifyNfs(4) }

    private suspend fun verifyNfs(version: Int) {
        val port = System.getenv("FOLDERSPAN_NFS_TEST_PORT")?.toIntOrNull()
        val mountPort = System.getenv("FOLDERSPAN_NFS_TEST_MOUNT_PORT")?.toIntOrNull()
        assumeTrue("Podman NFS fixture is opt-in", port != null && mountPort != null)
        val config = NetworkConnectionConfig(
            protocol = "NFS", host = "nfs://127.0.0.1:$port", username = "", password = "",
            extras = NetworkDriveExtras(nfs = NfsDriveExtras(version = version, exportPath = "/export", mountPort = checkNotNull(mountPort))),
        )
        val client = NfsNetworkClient(config)
        val wire = NfsRpcConnection.connect("127.0.0.1", checkNotNull(port), config.extras.nfs)
        try {
            val response = if (version == 3) wire.call(100003, 3, 1) { opaque(byteArrayOf(1, 2, 3, 4)) }
            else wire.call(100003, 4, 1) { string("invalid-handle"); int(0); int(1); int(22); opaque(byteArrayOf(1, 2, 3, 4)) }
            assertNotEquals(0, response.int(), "Server must reject an invalid file handle")
        } finally { wire.close() }
        val root = "/v$version-${UUID.randomUUID()}"
        val payload = ByteArray(1024 * 1024 + 73) { (it % 251).toByte() }
        val local = Files.createTempDirectory("folderspan-nfs-test-")
        try {
            assertTrue(client.createFolder(root).getOrThrow())
            val deniedClient = NfsNetworkClient(config.copy(extras = config.extras.copy(nfs = config.extras.nfs.copy(exportPath = "/readonly", uid = 12345, gid = 12345))))
            try { assertTrue(deniedClient.createFile("/no-access").isFailure, "NFS must preserve server write permissions") }
            finally { deniedClient.close() }
            val source = "$root/空 格.bin"
            var offset = 0
            val progress = mutableListOf<Long>()
            assertTrue(client.uploadFromSource(source, payload.size.toLong(), { done, total ->
                assertEquals(payload.size.toLong(), total)
                progress += done
            }) {
                if (offset == payload.size) null else {
                    val end = minOf(payload.size, offset + 31 * 1024)
                    payload.copyOfRange(offset, end).also { offset = end }
                }
            }.getOrThrow())
            assertEquals(payload.size.toLong(), progress.last())
            val entry = client.list(root).getOrThrow().single()
            assertEquals("空 格.bin", entry.name)
            assertEquals(payload.size.toLong(), entry.size)
            assertTrue(entry.isSymbolicLinkKnown)
            val output = ByteArrayOutputStream()
            assertTrue(client.downloadByChunks(source, payload.size.toLong()) { bytes, total ->
                assertEquals(payload.size.toLong(), total)
                output.write(bytes)
                Result.success(Unit)
            }.getOrThrow())
            assertContentEquals(payload, output.toByteArray())

            val renamed = "$root/renamed.bin"
            assertTrue(client.rename(source, renamed).getOrThrow())
            assertEquals("renamed.bin", client.list(root).getOrThrow().single().name)
            val target = local.resolve("download.bin")
            assertTrue(client.download(renamed, target.toString(), payload.size.toLong()) { _, _ -> }.getOrThrow())
            assertContentEquals(payload, Files.readAllBytes(target))

            val empty = "$root/empty"
            assertTrue(client.createFile(empty).getOrThrow())
            val emptyOutput = ByteArrayOutputStream()
            assertTrue(client.downloadByChunks(empty, 0) { bytes, total ->
                assertEquals(0L, total); emptyOutput.write(bytes); Result.success(Unit)
            }.getOrThrow())
            assertEquals(0, emptyOutput.size())

            coroutineScope {
                val reading = CompletableDeferred<Unit>()
                val transfer = launch {
                    client.downloadByChunks(renamed, payload.size.toLong()) { _, _ ->
                        reading.complete(Unit)
                        awaitCancellation()
                    }
                }
                withTimeout(10_000) { reading.await() }
                withTimeout(10_000) { transfer.cancelAndJoin() }
            }
            assertEquals(2, client.list(root).getOrThrow().size)
            assertTrue(client.createFolder("$root/sub").getOrThrow())
            assertTrue(client.rename("$root/sub", "$root/sub renamed").getOrThrow())
            assertTrue(client.delete("$root/sub renamed", true).getOrThrow())
            assertTrue(client.createFolder("/../escape").isFailure)
            assertTrue(client.delete("/", true).isFailure)
            assertTrue(client.delete(renamed, false).getOrThrow())
            assertTrue(client.delete(empty, false).getOrThrow())
            assertTrue(client.delete(root, true).getOrThrow())
        } finally {
            withContext(NonCancellable) {
                client.list(root).getOrNull()?.forEach { client.delete(it.path, it.isDirectory) }
                client.delete(root, true)
                client.close()
            }
            Files.walk(local).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) } }
        }
    }

    @Test fun httpDirectoryUsesRealPodmanService() = runBlocking {
        val endpoint = System.getenv("FOLDERSPAN_HTTP_TEST_URL")?.takeIf { it.isNotBlank() }
        assumeTrue("Podman HTTP fixture is opt-in", endpoint != null)
        val client = HttpDirectoryNetworkClient(NetworkConnectionConfig("HttpDirectory", checkNotNull(endpoint), "", ""))
        val directory = Files.createTempDirectory("folderspan-http-test-")
        try {
            val entries = client.list("/").getOrThrow()
            assertEquals(setOf("a b.txt", "empty.txt", "nested"), entries.map { it.name }.toSet())
            assertEquals(listOf("payload.bin"), client.list("/nested").getOrThrow().map { it.name })
            val path = directory.resolve("download.bin")
            assertTrue(client.download("/nested/payload.bin", path.toString(), -1L) { _, _ -> }.getOrThrow())
            assertContentEquals(ByteArray(1024 * 1024) { (it % 256).toByte() }, Files.readAllBytes(path))
            assertTrue(client.delete("/a b.txt", false).isFailure)
        } finally {
            client.close()
            Files.walk(directory).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) } }
        }
    }
}
