package com.folderspan.data.main

import com.folderspan.data.file.FileProtocol
import com.folderspan.data.file.FileSimpleInfo
import com.folderspan.data.main.network.NetworkShare
import com.folderspan.data.main.network.NetworkAccess
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import java.net.InetSocketAddress
import java.nio.file.Files
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class NetworkShareTest {
    @Test
    fun directoryObjectsBrowseRootAndNestedFoldersAndDownload() = runBlocking {
        withLinkShare { share, requests ->
            val access: NetworkAccess = share
            val root = access.getFile("/").getOrThrow()
            assertEquals(FileProtocol.Network, root.protocol)
            val folders = access.getList(root).getOrThrow()
            assertEquals(access.getList("/").getOrThrow(), folders)
            val folder = folders.single()
            assertEquals("Documents", folder.name)
            assertEquals(share.protocolId, folder.protocolId)
            val file = access.getList(folder).getOrThrow().single()
            assertEquals("readme.txt", file.name)
            assertEquals(FileProtocol.Network, file.protocol)
            assertEquals(share.protocolId, file.protocolId)
            val target = Files.createTempFile("link-share-test-", ".txt")
            try {
                assertTrue(access.downloadFileToLocal(file, target.toString()).getOrThrow())
                assertEquals("LinkShare content", Files.readString(target))
            } finally {
                Files.deleteIfExists(target)
            }
            assertTrue(requests.contains("/Documents"))
            assertTrue(requests.contains("/Documents/readme.txt"))
        }
    }

    @Test
    fun fileObjectsRefreshMetadataAndReportMissingFiles() = runBlocking {
        withLinkShare { share, _ ->
            val access: NetworkAccess = share
            val stale = FileSimpleInfo.pathFileSimpleInfo("/Documents/readme.txt")
            val current = access.getFile(stale).getOrThrow()
            assertFalse(current.isDirectory)
            assertEquals(17L, current.size)
            assertEquals(share.protocolId, current.protocolId)
            assertTrue(access.getFile(stale.copy(path = "/Documents/missing.txt")).exceptionOrNull() is NoSuchElementException)
        }
    }

    private suspend fun withLinkShare(block: suspend (NetworkShare, List<String>) -> Unit) {
        val requests = CopyOnWriteArrayList<String>()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        val file = FileSimpleInfo.nullFileSimpleInfo().copy(
            name = "readme.txt", path = "/Documents/readme.txt", size = 17L,
            mineType = "text/plain", isSymbolicLinkKnown = true,
        )
        server.createContext("/") { exchange ->
            exchange.use {
                val path = it.requestURI.path
                requests += path
                val body = when (path) {
                    "/" -> Json.encodeToString(listOf(FileSimpleInfo.pathFileSimpleInfo("/Documents").copy(
                        name = "Documents", isSymbolicLinkKnown = true,
                    )))
                    "/Documents" -> Json.encodeToString(listOf(file))
                    "/Documents/readme.txt" -> "LinkShare content"
                    else -> error("Unexpected share path: $path")
                }.toByteArray()
                it.responseHeaders.set("Content-Type", if (path.endsWith(".txt")) "text/plain" else "application/json")
                it.sendResponseHeaders(200, body.size.toLong())
                it.responseBody.write(body)
            }
        }
        server.start()
        val share = NetworkShare("LinkShare", "http://127.0.0.1:${server.address.port}", userAgent = "TestAgent")
        try {
            block(share, requests)
        } finally {
            share.close()
            server.stop(0)
        }
    }

    @Test
    fun tagEntriesSetsNetworkProtocolAndId() {
        val shareNetwork = NetworkShare(
            name = "LinkShare",
            baseUrl = "http://localhost:8080",
            password = ""
        )
        val entry = FileSimpleInfo.nullFileSimpleInfo().copy(
            name = "file.txt",
            isDirectory = false,
            path = "/file.txt",
            mineType = "text/plain"
        )
        val tagged = shareNetwork.tagEntries(listOf(entry)).first()
        assertEquals(FileProtocol.Network, tagged.protocol)
        assertEquals(shareNetwork.protocolId, tagged.protocolId)
    }
}
