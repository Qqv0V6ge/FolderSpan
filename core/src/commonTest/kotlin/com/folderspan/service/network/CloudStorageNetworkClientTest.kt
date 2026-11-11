package com.folderspan.service.network

import com.folderspan.data.main.network.CloudDriveExtras
import com.folderspan.data.main.network.NetworkDriveExtras
import com.folderspan.test.runSuspendTest
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.*
import io.ktor.http.*
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.*
import kotlin.test.*

class CloudStorageNetworkClientTest {
    @Test fun googleMoveKeepsTheSelectedDestinationParentAndNeverOverwritesANamedEntry() = runSuspendTest {
        for (occupied in listOf(false, true)) {
            var writes = 0
            var rootListings = 0
            val http = HttpClient(MockEngine { request ->
                when {
                    request.method == HttpMethod.Patch -> {
                        writes++
                        assertEquals("P", request.url.parameters["addParents"])
                        assertEquals("S", request.url.parameters["removeParents"])
                        assertEquals("new.txt", Json.parseToJsonElement(request.body.toByteArray().decodeToString()).jsonObject["name"]!!.jsonPrimitive.content)
                        respond("{}")
                    }
                    request.url.encodedPath.endsWith("/root") -> respond(google("R", "/", true, null))
                    request.url.encodedPath.endsWith("/A") -> respond(google("A", "old.txt", parent = "S"))
                    request.url.encodedPath.endsWith("/S") -> respond(google("S", "source", true))
                    request.url.encodedPath.endsWith("/P") -> respond(google("P", "target", true))
                    request.url.encodedPath.endsWith("/files") -> {
                        val query = request.url.parameters["q"].orEmpty()
                        if ("'P'" in query) respond(if (occupied) """{"files":[""" + google("B", "new.txt", parent = "P") + "]}" else """{"files":[]}""")
                        else { rootListings++; respond("""{"files":[""" + google("P", "target", true) + "," + google("Q", "target", true) + "]}") }
                    }
                    else -> error("Unexpected request")
                }
            }) { followRedirects = false }
            try {
                val client = GoogleDriveNetworkClient(config("GoogleDrive"), api(http))
                val moved = client.move(StoragePath("/source/old.txt", StorageReference("A", "S")),
                    StoragePath("/target/new.txt", StorageReference(ancestorId = "P", ancestorPath = "/target")))
                assertEquals(!occupied, moved.getOrDefault(false))
                assertEquals(if (occupied) 0 else 1, writes)
                assertEquals(0, rootListings)
            } finally { http.close() }
        }
    }

    private fun config(protocol: String, cloud: CloudDriveExtras = CloudDriveExtras()) =
        NetworkConnectionConfig(protocol, "https://storage.example.test", "", "", extras = NetworkDriveExtras(cloud = cloud))
    private fun api(http: HttpClient) = StorageHttpApi({ "private-token" }, http)
    private fun google(id: String, name: String, folder: Boolean = false, parent: String? = "R") = buildJsonObject {
        put("id", id); put("name", name); put("mimeType", if (folder) "application/vnd.google-apps.folder" else "text/plain"); put("size", "7")
        if (parent != null) putJsonArray("parents") { add(parent) }
    }.toString()
    @Test
    fun googlePaginationPreservesDuplicateNamesAndDownloadsSelectedId() = runSuspendTest {
        var pages = 0
        val http = HttpClient(MockEngine { request ->
            assertEquals("Bearer private-token", request.headers[HttpHeaders.Authorization])
            when {
                request.url.parameters["alt"] == "media" -> {
                    assertTrue(request.url.encodedPath.endsWith("/B")); respond("payload", headers = headersOf(HttpHeaders.ContentLength, "7"))
                }
                request.url.encodedPath.endsWith("/root") -> respond(google("R", "/", true, null))
                request.url.encodedPath.endsWith("/B") -> respond(google("B", "same.txt"))
                request.url.encodedPath.endsWith("/files") -> {
                    pages++
                    if (pages == 1) respond("""{"files":[""" + google("A", "same.txt") + """],"nextPageToken":"next"}""")
                    else { assertEquals("next", request.url.parameters["pageToken"]); respond("""{"files":[""" + google("B", "same.txt") + "]}") }
                }
                else -> error("Unexpected request")
            }
        }) { followRedirects = false }
        try {
            val client = GoogleDriveNetworkClient(config("GoogleDrive"), api(http))
            val list = client.list("/").getOrThrow()
            assertEquals(listOf("same.txt", "same.txt"), list.map { it.name })
            assertEquals(listOf("A", "B"), list.map { it.reference?.id })
            var content = ""
            client.downloadByChunks(StoragePath(list[1].path, list[1].reference), 7L) { bytes, _ ->
                content += bytes.decodeToString(); Result.success(Unit)
            }.getOrThrow()
            assertEquals("payload", content); assertEquals(2, pages)
        } finally { http.close() }
    }

    @Test
    fun googleRejectsAmbiguousPathAndNeverSendsTokenToDownloadCdn() = runSuspendTest {
        var cdn = 0
        val http = HttpClient(MockEngine { request ->
            when {
                request.url.host.endsWith(".googleusercontent.com") -> {
                    cdn++; assertNull(request.headers[HttpHeaders.Authorization]); respond("payload")
                }
                request.url.parameters["alt"] == "media" -> respond("", HttpStatusCode.Found,
                    headersOf(HttpHeaders.Location, "https://content.googleusercontent.com/file"))
                request.url.encodedPath.endsWith("/root") -> respond(google("R", "/", true, null))
                request.url.encodedPath.endsWith("/files") -> respond("""{"files":[""" + google("A", "same.txt") + "," + google("B", "same.txt") + "]}")
                else -> respond(google("B", "same.txt"))
            }
        }) { followRedirects = false }
        try {
            val client = GoogleDriveNetworkClient(config("GoogleDrive"), api(http))
            assertTrue(client.downloadByChunks("/same.txt", 7L) { _, _ -> error("Ambiguous item must not be consumed") }.isFailure)
            client.downloadByChunks(StoragePath("/same.txt", StorageReference("B", "R")), 7L) { _, _ -> Result.success(Unit) }.getOrThrow()
            assertEquals(1, cdn)
            assertFailsWith<CancellationException> {
                client.downloadByChunks(StoragePath("/same.txt", StorageReference("B", "R")), 7L) { _, _ -> throw CancellationException("stop") }
            }
        } finally { http.close() }
    }

    @Test
    fun dropboxDetectsCursorCyclesAndEncodesNonAsciiHeader() = runSuspendTest {
        var calls = 0
        val http = HttpClient(MockEngine { calls++; respond("""{"entries":[],"has_more":true,"cursor":"repeated"}""") }) { followRedirects = false }
        try {
            val client = DropboxNetworkClient(config("Dropbox"), api(http))
            assertTrue(client.list("/").isFailure); assertEquals(2, calls)
            val value = buildJsonObject { put("path", "/文档/é.txt") }
            val header = dropboxHeader(value)
            assertTrue(header.all { it.code in 32..126 }); assertEquals(value, Json.parseToJsonElement(header))
        } finally { http.close() }
    }

    @Test
    fun seafileRepoTokenUsesScopedRoutesAndContentHashesDoNotMergeFiles() = runSuspendTest {
        val repo = "00000000-0000-0000-0000-000000000001"
        val http = HttpClient(MockEngine { request ->
            assertEquals("/api/v2.1/via-repo-token/dir/", request.url.encodedPath)
            assertEquals("/docs", request.url.parameters["path"]); assertEquals("Bearer private-token", request.headers[HttpHeaders.Authorization])
            respond("""{"dirent_list":[{"type":"file","id":"same-content-hash","name":"a.txt","size":0},{"type":"file","id":"same-content-hash","name":"b.txt","size":0}]}""")
        }) { followRedirects = false }
        try {
            val client = SeafileNetworkClient(config("Seafile", CloudDriveExtras(libraryId = repo, seafileRepoToken = true)), api(http))
            val entries = client.list("/docs").getOrThrow()
            assertEquals(listOf("a.txt", "b.txt"), entries.map { it.name }); assertTrue(entries.all { it.reference == null })
            assertTrue(client.list("/../outside").isFailure)
        } finally { http.close() }
    }

    @Test
    fun seafileUploadStreamsMultipartWithoutSendingAccountTokenToFileServer() = runSuspendTest {
        val repo = "00000000-0000-0000-0000-000000000001"
        val http = HttpClient(MockEngine { request ->
            if (request.url.encodedPath.endsWith("/upload-link/")) {
                assertEquals("Bearer private-token", request.headers[HttpHeaders.Authorization]); assertEquals("/docs", request.url.parameters["p"])
                respond(""""https://storage.example.test:8082/upload-api/temporary-token"""")
            } else {
                assertNull(request.headers[HttpHeaders.Authorization]); assertEquals("1", request.url.parameters["ret-json"])
                val body = request.body.toByteArray().decodeToString()
                assertTrue(body.contains("name=\"parent_dir\"\r\n\r\n/docs")); assertTrue(body.contains("filename=\"文档.txt\""))
                assertTrue(body.contains("\r\n\r\npayload\r\n--"))
                respond("""[{"name":"文档.txt","id":"content-hash","size":7}]""")
            }
        }) { followRedirects = false }
        try {
            val client = SeafileNetworkClient(config("Seafile", CloudDriveExtras(libraryId = repo)), api(http))
            var source: ByteArray? = "payload".encodeToByteArray()
            client.uploadFromSource("/docs/文档.txt", 7L, readChunk = { source.also { source = null } }).getOrThrow()
        } finally { http.close() }
    }
}
