package com.folderspan.service.network

import com.folderspan.data.main.network.CloudDriveExtras
import com.folderspan.data.main.network.NetworkDriveExtras
import com.folderspan.test.runSuspendTest
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.test.fail

class NextcloudContentVerificationTest {
    private val davRoot = "/remote.php/dav/files/user/scope"
    private val namespace = "$davRoot/.folderspan-vault"
    private val selected = StoragePath("/.folderspan-vault/record.commit",
        StorageReference(id = "23", parentId = "17", ancestorId = "11", ancestorPath = "/"))

    private fun provider(http: HttpClient): NextcloudNetworkClient {
        val config = NetworkConnectionConfig("Nextcloud", "https://storage.example.test", "", "",
            extras = NetworkDriveExtras(cloud = CloudDriveExtras(rootId = "/scope",
                accountId = "account", connectionId = "connection")))
        return NextcloudNetworkClient(config, {
            StorageCredential("account", StorageAccountKind.Nextcloud, "user", "Test", "user",
                "https://storage.example.test", "synthetic-token")
        }, { StorageCapabilities.ReadWrite }, http)
    }

    private fun metadata(id: String) =
        """<d:multistatus xmlns:d="DAV:" xmlns:oc="http://owncloud.org/ns"><d:response>
            <d:propstat><d:prop><oc:fileid>$id</oc:fileid></d:prop>
            <d:status>HTTP/1.1 200 OK</d:status></d:propstat></d:response></d:multistatus>"""

    @Test
    fun verificationSkipsOnlyFileIdentityWhileOrdinaryReadsStillRejectReplacement() = runSuspendTest {
        val requests = mutableListOf<Pair<HttpMethod, String>>()
        val http = HttpClient(MockEngine { request ->
            requests += request.method to request.url.encodedPath
            if (request.method == HttpMethod.Get) respond("caller must authenticate these bytes", HttpStatusCode.OK)
            else {
                val id = when (request.url.encodedPath) {
                    davRoot -> "11"
                    namespace -> "17"
                    else -> "99"
                }
                respond(metadata(id), HttpStatusCode.MultiStatus)
            }
        }) { followRedirects = false }
        val client = provider(http)
        try {
            assertIs<IllegalArgumentException>(client.downloadByChunks(selected, -1) { _, _ ->
                fail("An ordinary read must reject the replaced native file")
            }.exceptionOrNull())
            val propfind = HttpMethod("PROPFIND")
            assertEquals(listOf(propfind to davRoot, propfind to namespace,
                propfind to "$namespace/record.commit"), requests)
            requests.clear()
            var body = ""
            assertTrue(client.downloadForContentVerification(selected, -1) { bytes, _ ->
                body += bytes.decodeToString()
                Result.success(Unit)
            }.getOrThrow())
            assertEquals("caller must authenticate these bytes", body)
            assertEquals(listOf(propfind to davRoot, propfind to namespace,
                HttpMethod.Get to "$namespace/record.commit"), requests)
        } finally {
            client.close()
            http.close()
        }
    }

    @Test
    fun verificationRejectsAReplacedParentBeforeReadingAnyBody() = runSuspendTest {
        val paths = mutableListOf<String>()
        val http = HttpClient(MockEngine { request ->
            assertEquals(HttpMethod("PROPFIND"), request.method)
            paths += request.url.encodedPath
            respond(metadata(if (request.url.encodedPath == davRoot) "11" else "99"), HttpStatusCode.MultiStatus)
        }) { followRedirects = false }
        val client = provider(http)
        try {
            assertIs<IllegalArgumentException>(client.downloadForContentVerification(selected, -1) { _, _ ->
                fail("A replaced parent must reject the body")
            }.exceptionOrNull())
            assertEquals(listOf(davRoot, namespace), paths)
        } finally {
            client.close()
            http.close()
        }
    }

    @Test
    fun verificationRejectsAReplacedAncestorAndBoundaryEscapeBeforeReadingAnyBody() = runSuspendTest {
        var requests = 0
        val http = HttpClient(MockEngine { request ->
            assertEquals(HttpMethod("PROPFIND"), request.method)
            requests++
            respond(metadata("99"), HttpStatusCode.MultiStatus)
        }) { followRedirects = false }
        val client = provider(http)
        try {
            assertIs<IllegalArgumentException>(client.downloadForContentVerification(selected, -1) { _, _ ->
                fail("A replaced ancestor must reject the body")
            }.exceptionOrNull())
            assertEquals(1, requests)
            val outside = StoragePath("/other/record.commit",
                StorageReference(id = "23", ancestorId = "17", ancestorPath = "/.folderspan-vault"))
            assertIs<IllegalArgumentException>(client.downloadForContentVerification(outside, -1) { _, _ ->
                fail("An ancestor escape must reject the body")
            }.exceptionOrNull())
            assertEquals(1, requests)
        } finally {
            client.close()
            http.close()
        }
    }
}
