package com.folderspan.service.network

import com.folderspan.data.main.network.Network
import com.folderspan.data.main.network.NetworkDriveExtras
import com.folderspan.data.main.network.SmbDriveExtras
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assume.assumeTrue
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

// 指向专用测试服务器；只在本次随机创建的目录中读写。
// FOLDERSPAN_TEST_FTP_ENDPOINT / FOLDERSPAN_TEST_SMB_ENDPOINT、USER、PASSWORD、SMB_SHARE。
class NetworkSessionReuseIntegrationTest {
    @Test
    fun ftpReusesLoginAcrossListingsAndNestedTransfers() = verifyProtocol("FTP")

    @Test
    fun smbReusesAuthenticationAcrossListingsAndNestedTransfers() = verifyProtocol("SMB")

    private fun verifyProtocol(protocol: String) = runBlocking {
        val endpoint = System.getenv("FOLDERSPAN_TEST_${protocol}_ENDPOINT")
        assumeTrue("未配置 $protocol 集成测试服务器", !endpoint.isNullOrBlank())
        val network = Network(
            "Integration", "/", protocol, endpoint!!,
            System.getenv("FOLDERSPAN_TEST_USER") ?: "test",
            System.getenv("FOLDERSPAN_TEST_PASSWORD") ?: "test-password",
            extras = NetworkDriveExtras(smb = SmbDriveExtras(
                share = System.getenv("FOLDERSPAN_TEST_SMB_SHARE") ?: "test",
            )),
        )
        fun newClient(): NetworkClient = if (protocol == "FTP") FtpNetworkClient(network) else SmbNetworkClient(network)
        val root = "/reuse-${UUID.randomUUID()}"
        val source = "$root/source.txt"
        val target = "$root/target.txt"
        val client = newClient()
        withTimeout(60_000) {
            assertTrue(client.createFolder(root).getOrThrow())
            try {
                var sent = false
                client.uploadFromSource(source, 7, { _, _ -> }) {
                    if (sent) null else "payload".encodeToByteArray().also { sent = true }
                }.getOrThrow()
                repeat(24) {
                    assertEquals(listOf("source.txt"), newClient().list(root).getOrThrow().map { it.name })
                }
                (client as ChunkReadableNetworkClient).downloadByChunks(source, 7) { chunk, _ ->
                    var copied = false
                    newClient().uploadFromSource(target, chunk.size.toLong(), { _, _ -> }) {
                        if (copied) null else chunk.also { copied = true }
                    }.map { Unit }
                }.getOrThrow()
                val copied = mutableListOf<Byte>()
                (newClient() as ChunkReadableNetworkClient).downloadByChunks(target, 7) { chunk, _ ->
                    copied.addAll(chunk.toList())
                    Result.success(Unit)
                }.getOrThrow()
                assertEquals("payload", copied.toByteArray().decodeToString())
                List(8) {
                    async(Dispatchers.IO) {
                        assertEquals(setOf("source.txt", "target.txt"), newClient().list(root).getOrThrow().map { it.name }.toSet())
                    }
                }.awaitAll()
                // 回调失败不能重放下载，也不能把未消费的 FTP 完成响应交给下一次操作。
                var callbacks = 0
                val failure = (client as ChunkReadableNetworkClient).downloadByChunks(source, 7) { _, _ ->
                    callbacks++
                    Result.failure(IllegalStateException("cancel transfer"))
                }
                assertTrue(failure.isFailure)
                assertEquals(1, callbacks)
                assertEquals(2, newClient().list(root).getOrThrow().size)
            } finally {
                client.delete(target, false)
                client.delete(source, false)
                client.delete(root, true)
            }
        }
    }
}
