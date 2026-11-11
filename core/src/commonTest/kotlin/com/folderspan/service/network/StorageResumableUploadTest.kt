package com.folderspan.service.network

import com.folderspan.data.main.network.CloudDriveExtras
import com.folderspan.data.main.network.NetworkDriveExtras
import com.folderspan.test.runSuspendTest
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.*
import io.ktor.http.*
import kotlinx.coroutines.CancellationException
import kotlin.test.*

class StorageResumableUploadTest {
    private fun config(protocol: String, connection: String = "connection") = NetworkConnectionConfig(protocol, "", "", "",
        extras = NetworkDriveExtras(cloud = CloudDriveExtras(accountId = "account", connectionId = connection)))
    private fun api(http: HttpClient) = StorageHttpApi({ "private-token" }, http)
    private fun googleFile(size: Long) =
        """{"id":"uploaded","name":"file.bin","mimeType":"application/octet-stream","size":"$size","parents":["root"]}"""
    private val root = """{"id":"root","name":"/","mimeType":"application/vnd.google-apps.folder"}"""
    private val permissions = """{"capabilities":{"canAddChildren":true,"canEdit":true}}"""
    private val sessionUrl = "https://www.googleapis.com/upload/drive/v3/files?upload_id=secret"
    private val target = StoragePath("/file.bin")

    @Test
    fun googleUsesConfirmedRangeAndFinalPartialChunk() = runSuspendTest {
        var query = false
        var chunks = 0
        val http = HttpClient(MockEngine { request ->
            when {
                request.url.parameters["upload_id"] != null -> {
                    assertEquals("Bearer private-token", request.headers[HttpHeaders.Authorization])
                    if (request.headers[HttpHeaders.ContentRange] == "bytes */300000") {
                        query = true
                        respond("", HttpStatusCode.PermanentRedirect, headersOf(HttpHeaders.Range, "bytes=0-262143"))
                    } else {
                        chunks++
                        assertEquals("bytes 262144-299999/300000", request.headers[HttpHeaders.ContentRange])
                        assertEquals(37856, request.body.toByteArray().size)
                        respond(googleFile(300000))
                    }
                }
                request.url.parameters["uploadType"] == "resumable" ->
                    respond("", headers = headersOf(HttpHeaders.Location, sessionUrl))
                request.url.parameters["fields"]?.contains("capabilities") == true -> respond(permissions)
                request.url.encodedPath.endsWith("/root") -> respond(root)
                else -> respond("""{"files":[]}""")
            }
        }) { followRedirects = false }
        try {
            val provider = GoogleDriveNetworkClient(config("GoogleDrive"), api(http))
            val initial = provider.startUpload(target, 300000).getOrThrow()
            val resumed = provider.queryUpload(initial).getOrThrow()
            assertTrue(query); assertEquals(262144L, resumed.confirmedOffset)
            assertTrue(provider.uploadChunk(initial, 0, ByteArray(17)).isFailure)
            val completed = provider.uploadChunk(resumed, resumed.confirmedOffset, ByteArray(37856)).getOrThrow()
            assertEquals(StorageUploadStatus.COMPLETED, completed.status)
            assertEquals("uploaded", completed.entry?.reference?.id)
            assertEquals(300000L, completed.confirmedOffset)
            assertEquals(completed, provider.finishUpload(completed).getOrThrow())
            assertEquals(1, chunks)
        } finally { http.close() }
    }

    @Test
    fun googleRetainsUncertaintyAfterFinalResponseLossAndSupportsEmptyFiles() = runSuspendTest {
        var loseResponse = true
        val http = HttpClient(MockEngine { request ->
            when {
                request.url.parameters["upload_id"] != null -> {
                    if (loseResponse) { loseResponse = false; error("Response lost after commit") }
                    respond(googleFile(0))
                }
                request.url.parameters["uploadType"] == "resumable" -> respond("", headers = headersOf(HttpHeaders.Location, sessionUrl))
                request.url.parameters["fields"]?.contains("capabilities") == true -> respond(permissions)
                request.url.encodedPath.endsWith("/root") -> respond(root)
                else -> respond("""{"files":[]}""")
            }
        }) { followRedirects = false }
        try {
            val provider = GoogleDriveNetworkClient(config("GoogleDrive"), api(http))
            val initial = provider.startUpload(target, 0).getOrThrow()
            val uncertain = provider.uploadChunk(initial, 0, byteArrayOf()).exceptionOrNull()
            assertIs<StorageUploadUncertainException>(uncertain)
            assertEquals(StorageUploadStatus.UNCERTAIN, uncertain.session.status)
            val complete = provider.queryUpload(uncertain.session).getOrThrow()
            assertEquals(StorageUploadStatus.COMPLETED, complete.status)
            assertEquals(0L, complete.confirmedOffset)
        } finally { http.close() }
    }

    @Test
    fun googleDetectsExpiryAndRefusesOtherAccountOrCancelledSession() = runSuspendTest {
        var calls = 0
        val http = HttpClient(MockEngine { calls++; respond("", HttpStatusCode.NotFound) }) { followRedirects = false }
        try {
            val provider = GoogleDriveNetworkClient(config("GoogleDrive"), api(http))
            val session = StorageUploadSession("GoogleDrive", target, 10, 0, secret = sessionUrl,
                binding = storageConnectionBinding(config("GoogleDrive"), "root"))
            assertEquals(StorageUploadStatus.EXPIRED, provider.queryUpload(session).getOrThrow().status)
            val other = GoogleDriveNetworkClient(config("GoogleDrive", "other"), api(http))
            assertTrue(other.queryUpload(session).isFailure)
            assertEquals(1, calls)
            provider.cancelUpload(session).getOrThrow()
            assertTrue(provider.queryUpload(session).isFailure)
            assertEquals(1, calls)
        } finally { http.close() }
    }

    @Test
    fun dropboxCorrectsOffsetRetriesChunksAndCommitsOnce() = runSuspendTest {
        var append = 0
        var finish = 0
        val http = HttpClient(MockEngine { request ->
            when {
                request.url.encodedPath.endsWith("list_folder") -> respond("""{"entries":[],"has_more":false}""")
                request.url.encodedPath.endsWith("upload_session/start") -> respond("""{"session_id":"private-session"}""")
                request.url.encodedPath.endsWith("append_v2") -> {
                    append++
                    if (append == 1) respond("""{"error":{".tag":"incorrect_offset","correct_offset":3}}""", HttpStatusCode.Conflict)
                    else {
                        assertTrue(request.headers["Dropbox-API-Arg"]!!.contains("\"offset\":3"))
                        assertEquals("load", request.body.toByteArray().decodeToString())
                        respond("")
                    }
                }
                request.url.encodedPath.endsWith("upload_session/finish") -> {
                    finish++
                    assertTrue(request.headers["Dropbox-API-Arg"]!!.contains("\"offset\":7"))
                    respond("""{"id":"id:uploaded","name":"file.bin","path_display":"/file.bin","size":7,"rev":"0123456789abc","server_modified":"2024-01-01T00:00:00Z"}""")
                }
                else -> error("Unexpected request")
            }
        }) { followRedirects = false }
        try {
            val provider = DropboxNetworkClient(config("Dropbox"), api(http))
            val initial = provider.startUpload(target, 7).getOrThrow()
            val resumed = provider.queryUpload(initial).getOrThrow()
            assertEquals(3L, resumed.confirmedOffset)
            val written = provider.uploadChunk(resumed, 3, "load".encodeToByteArray()).getOrThrow()
            assertEquals(7L, written.confirmedOffset)
            val completed = provider.finishUpload(written).getOrThrow()
            assertEquals(StorageUploadStatus.COMPLETED, completed.status)
            assertEquals("id:uploaded", completed.entry?.reference?.id)
            provider.finishUpload(completed).getOrThrow()
            assertEquals(1, finish)
        } finally { http.close() }
    }

    @Test
    fun dropboxMalformedFinishKeepsUncertaintyWithoutRetryOrIdentityFallback() = runSuspendTest {
        val invalid = listOf(
            """{"name":"file.bin","path_display":"/file.bin","size":7}""",
            """{"id":"id:expected","name":"file.bin","path_display":"/file.bin"}""",
            """{"id":"id:expected","name":"file.bin","path_display":"/file.bin","size":8}""",
            """{"id":"id:other","name":"file.bin","path_display":"/file.bin","size":7}""",
            """{".tag":"folder","id":"id:expected","name":"file.bin","path_display":"/file.bin","size":7}""",
        )
        for (metadata in invalid) {
            var finish = 0
            val http = HttpClient(MockEngine { request ->
                assertTrue(request.url.encodedPath.endsWith("upload_session/finish"))
                finish++
                respond(metadata)
            }) { followRedirects = false }
            try {
                val provider = DropboxNetworkClient(config("Dropbox"), api(http))
                val session = StorageUploadSession("Dropbox", target.copy(reference = StorageReference("id:expected", "__root__")), 7, 7,
                    secret = """{"session":"private-session","commit":{"path":"/file.bin"}}""",
                    binding = storageConnectionBinding(config("Dropbox"), "__root__"))
                val failure = assertIs<StorageUploadUncertainException>(provider.finishUpload(session).exceptionOrNull())
                assertEquals(StorageUploadStatus.UNCERTAIN, failure.session.status)
                assertNull(failure.session.entry)
                assertEquals(1, finish)
            } finally { http.close() }
        }
    }

    @Test
    fun dropboxStillRejectsMissingTagsOnMetadataUnionEndpoints() = runSuspendTest {
        var requests = 0
        val http = HttpClient(MockEngine { request ->
            assertTrue(request.url.encodedPath.endsWith("get_metadata"))
            requests++
            respond("""{"id":"id:root","name":"root","path_display":"/root","size":7}""")
        }) { followRedirects = false }
        try {
            val selected = config("Dropbox").let { it.copy(extras = it.extras.copy(cloud = it.extras.cloud.copy(rootId = "id:root"))) }
            val provider = DropboxNetworkClient(selected, api(http))
            assertTrue(provider.list("/").isFailure)
            assertEquals(1, requests)
        } finally { http.close() }
    }

    @Test
    fun dropboxExpiredClosedCancelledAndInterruptedSessionsAreDistinct() = runSuspendTest {
        var phase = "not_found"
        val http = HttpClient(MockEngine {
            if (phase == "cancel") throw CancellationException("stop")
            respond("""{"error":{".tag":"$phase"}}""", HttpStatusCode.Conflict)
        }) { followRedirects = false }
        try {
            val provider = DropboxNetworkClient(config("Dropbox"), api(http))
            val session = StorageUploadSession("Dropbox", target, 7, 3,
                secret = """{"session":"private-session","commit":{"path":"/file.bin"}}""",
                binding = storageConnectionBinding(config("Dropbox"), "__root__"))
            assertEquals(StorageUploadStatus.EXPIRED, provider.queryUpload(session).getOrThrow().status)
            phase = "closed"
            assertEquals(StorageUploadStatus.UNCERTAIN, provider.queryUpload(session).getOrThrow().status)
            phase = "cancel"
            assertFailsWith<CancellationException> { provider.queryUpload(session) }
            provider.cancelUpload(session).getOrThrow()
            assertTrue(provider.uploadChunk(session, 3, "load".encodeToByteArray()).isFailure)
        } finally { http.close() }
    }
}
