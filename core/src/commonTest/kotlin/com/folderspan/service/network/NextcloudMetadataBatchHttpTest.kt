package com.folderspan.service.network

import com.folderspan.data.main.network.CloudDriveExtras
import com.folderspan.data.main.network.NetworkDriveExtras
import com.folderspan.test.runSuspendTest
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.*
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlin.io.encoding.Base64
import kotlin.test.*

class NextcloudMetadataBatchHttpTest {
    private val server = "https://storage.example.test"
    private val davRoot = "/remote.php/dav/files/user/scope"
    private val namespace = "$davRoot/.folderspan-vault"
    private val name = "a7b9adcb-5a0d-4739-b6c7-67ae827d9f1c.commit"
    private val payload = byteArrayOf(0, -1, 3)
    private val directory = StoragePath("/.folderspan-vault",
        StorageReference("17", ancestorId = "11", ancestorPath = "/"))
    private val entry = NetworkFileEntry(name, "/.folderspan-vault/$name", false, payload.size.toLong())
    private val account = StorageCredential("account", StorageAccountKind.Nextcloud, "user", "Test", "user",
        server, "synthetic-token")
    private val config = NetworkConnectionConfig("Nextcloud", server, "", "",
        extras = NetworkDriveExtras(cloud = CloudDriveExtras(rootId = "/scope",
            accountId = "account", connectionId = "connection")))

    private fun provider(http: HttpClient, credential: suspend () -> StorageCredential = { account }) =
        NextcloudNetworkClient(config, credential, { StorageCapabilities.ReadWrite }, http)

    private fun metadata(id: String) =
        """<d:multistatus xmlns:d="DAV:" xmlns:oc="http://owncloud.org/ns"><d:response>
            <d:propstat><d:prop><oc:fileid>$id</oc:fileid></d:prop>
            <d:status>HTTP/1.1 200 OK</d:status></d:propstat></d:response></d:multistatus>"""

    @Test
    fun batchesSelectedBytesInOneAuthenticatedGetAndUsesFreshCredentials() = runSuspendTest {
        var credential = account
        val methods = mutableListOf<Pair<HttpMethod, String>>()
        val headers = mutableListOf<String?>()
        val archive = archive()
        val http = HttpClient(MockEngine { request ->
            methods += request.method to request.url.encodedPath
            headers += request.headers[HttpHeaders.Authorization]
            if (request.method == HttpMethod.Get) {
                assertEquals(namespace, request.url.encodedPath)
                assertEquals("application/x-tar", request.headers[HttpHeaders.Accept])
                assertEquals("identity", request.headers[HttpHeaders.AcceptEncoding])
                assertEquals(JsonArray(listOf(JsonPrimitive(name))), Json.parseToJsonElement(request.url.parameters["files"]!!))
                assertNull(request.headers["X-NC-Files"])
                respond(archive, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/x-tar"))
            } else {
                assertEquals("0", request.headers["Depth"])
                respond(metadata(if (request.url.encodedPath == davRoot) "11" else "17"), HttpStatusCode.MultiStatus)
            }
        }) { followRedirects = false }
        val client = provider(http) { credential }
        try {
            repeat(2) { iteration ->
                if (iteration == 1) credential = account.copy(username = "updated-user", accessToken = "updated-token")
                val actual = client.downloadContentMetadata(directory, listOf(entry), 65584).getOrThrow()!!
                assertEquals(setOf(name), actual.keys)
                assertContentEquals(payload, actual.getValue(name))
                val basic = "Basic " + Base64.encode((credential.username + ":" + credential.accessToken).encodeToByteArray())
                assertTrue(headers.takeLast(3).all { it == basic })
            }
            val expected = listOf(HttpMethod("PROPFIND") to davRoot, HttpMethod("PROPFIND") to namespace,
                HttpMethod.Get to namespace)
            assertEquals(expected + expected, methods)
            credential = account.copy(server = "https://other.example.test")
            assertIs<IllegalStateException>(client.downloadContentMetadata(directory, listOf(entry), 65584).exceptionOrNull())
            assertEquals(6, methods.size)
        } finally { client.close(); http.close() }
    }

    @Test
    fun replacedDirectoryOrAncestorRejectsBeforeTheArchiveGet() = runSuspendTest {
        for (changed in listOf(davRoot, namespace)) {
            val requests = mutableListOf<String>()
            val http = HttpClient(MockEngine { request ->
                assertEquals(HttpMethod("PROPFIND"), request.method)
                requests += request.url.encodedPath
                respond(metadata(if (request.url.encodedPath == changed) "99"
                    else if (request.url.encodedPath == davRoot) "11" else "17"), HttpStatusCode.MultiStatus)
            }) { followRedirects = false }
            val client = provider(http)
            try {
                assertIs<IllegalArgumentException>(client.downloadContentMetadata(directory, listOf(entry), 65584).exceptionOrNull())
                assertEquals(if (changed == davRoot) listOf(davRoot) else listOf(davRoot, namespace), requests)
            } finally { client.close(); http.close() }
        }
    }

    @Test
    fun unsupportedStatusOrExplicitNonTarResponseAllowsIndividualFallback() = runSuspendTest {
        for ((status, type) in listOf(404 to null, 405 to null, 406 to null, 415 to null, 501 to null,
            200 to "application/json")) {
            val http = HttpClient(MockEngine { request ->
                if (request.method != HttpMethod.Get) respond(metadata("17"), HttpStatusCode.MultiStatus)
                else respond("not an archive", HttpStatusCode.fromValue(status),
                    if (type == null) headersOf() else headersOf(HttpHeaders.ContentType, type))
            }) { followRedirects = false }
            val client = provider(http)
            try {
                assertNull(client.downloadContentMetadata(StoragePath("/.folderspan-vault", StorageReference("17")),
                    listOf(entry), 65584).getOrThrow())
            } finally { client.close(); http.close() }
        }
    }

    @Test
    fun malformedOversizedMissingTypeAndServerErrorsRemainFailures() = runSuspendTest {
        val limit = maxNextcloudMetadataTarBytes(mapOf(name to payload.size.toLong())).toInt()
        for ((status, type, bytes) in listOf(
            Triple(200, "application/x-tar", byteArrayOf(1)),
            Triple(200, "application/x-tar", ByteArray(limit + 1)),
            Triple(200, null, archive()),
            Triple(500, "text/html", byteArrayOf()),
        )) {
            val http = HttpClient(MockEngine { request ->
                if (request.method != HttpMethod.Get) respond(metadata("17"), HttpStatusCode.MultiStatus)
                else respond(bytes, HttpStatusCode.fromValue(status),
                    if (type == null) headersOf() else headersOf(HttpHeaders.ContentType, type))
            }) { followRedirects = false }
            val client = provider(http)
            try {
                val result = client.downloadContentMetadata(StoragePath("/.folderspan-vault", StorageReference("17")),
                    listOf(entry), 65584)
                if (status == 500) assertIs<StorageApiException>(result.exceptionOrNull())
                else assertIs<IllegalArgumentException>(result.exceptionOrNull())
            } finally { client.close(); http.close() }
        }
    }

    @Test
    fun invalidSelectionIsRejectedBeforeAnyRequest() = runSuspendTest {
        val http = HttpClient(MockEngine { fail("Invalid selections must not make a request") })
        val client = provider(http)
        try {
            for (entries in listOf(emptyList(), List(33) { entry }, listOf(entry, entry),
                listOf(entry.copy(path = "/elsewhere/$name")), listOf(entry.copy(isDirectory = true)),
                listOf(entry.copy(isSymbolicLink = true)), listOf(entry.copy(size = 65585)),
                listOf(entry.copy(name = "../$name", path = "/.folderspan-vault/../$name")))) {
                assertIs<IllegalArgumentException>(client.downloadContentMetadata(directory, entries, 65584).exceptionOrNull())
            }
            assertIs<IllegalArgumentException>(client.downloadContentMetadata(directory, listOf(entry), 65585).exceptionOrNull())
        } finally { client.close(); http.close() }
    }

    @Test
    fun cancellationIsNotConvertedIntoFallbackOrResultFailure() = runSuspendTest {
        val http = HttpClient(MockEngine { throw CancellationException("cancelled archive") })
        val client = provider(http)
        try {
            assertFailsWith<CancellationException> {
                client.downloadContentMetadata(StoragePath("/.folderspan-vault"), listOf(entry), 65584)
            }
        } finally { client.close(); http.close() }
    }

    private fun archive(): ByteArray {
        val result = ByteArray(2048)
        ("//" + name).encodeToByteArray().copyInto(result)
        (payload.size.toString(8).padStart(11, '0') + "\u0000").encodeToByteArray().copyInto(result, 124)
        result[156] = '0'.code.toByte()
        "ustar\u000000".encodeToByteArray().copyInto(result, 257)
        for (index in 148..155) result[index] = ' '.code.toByte()
        val checksum = (0 until 512).sumOf { result[it].toInt() and 0xff }
        (checksum.toString(8).padStart(6, '0') + "\u0000 ").encodeToByteArray().copyInto(result, 148)
        payload.copyInto(result, 512)
        return result
    }
}
