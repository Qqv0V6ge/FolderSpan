package com.folderspan.service.network

import com.folderspan.data.main.network.CloudDriveExtras
import com.folderspan.data.main.network.NetworkDriveExtras
import com.folderspan.test.runSuspendTest
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import kotlin.io.encoding.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class NextcloudHttpLifecycleTest {
    private val server = "https://storage.example.test"
    private val config = NetworkConnectionConfig("Nextcloud", server, "", "",
        extras = NetworkDriveExtras(cloud = CloudDriveExtras(rootId = "/scope",
            accountId = "account", connectionId = "connection")))
    private val account = StorageCredential("account", StorageAccountKind.Nextcloud, "user", "Test", "user",
        server, "first-token")

    private fun authorization(credential: StorageCredential) =
        "Basic " + Base64.encode((credential.username + ":" + credential.accessToken).encodeToByteArray())

    private suspend fun read(client: NextcloudNetworkClient) {
        var contents = ""
        assertTrue(client.downloadByChunks("/file", 3) { bytes, _ ->
            contents += bytes.decodeToString()
            Result.success(Unit)
        }.getOrThrow())
        assertEquals("abc", contents)
    }

    @Test
    fun sharedTransportUsesFreshCredentialsAndSurvivesOperationAndProviderClose() = runSuspendTest {
        var credential = account
        val requests = mutableListOf<Pair<String, String?>>()
        val http = HttpClient(MockEngine { request ->
            assertEquals(HttpMethod.Get, request.method)
            requests += request.url.encodedPath to request.headers[HttpHeaders.Authorization]
            respond("abc", HttpStatusCode.OK)
        }) { followRedirects = false }
        val client = NextcloudNetworkClient(config, { credential }, { StorageCapabilities.ReadWrite }, http)
        try {
            read(client)
            credential = credential.copy(username = "updated-user", accessToken = "updated-token")
            read(client)
            client.close()
            assertEquals("abc", http.get("$server/probe").bodyAsText())
            assertEquals(listOf(
                "/remote.php/dav/files/user/scope/file" to authorization(account),
                "/remote.php/dav/files/user/scope/file" to authorization(credential),
                "/probe" to null,
            ), requests)
        } finally {
            client.close()
            http.close()
        }
    }

    @Test
    fun changedAccountIsRejectedBeforeReusingTheTransport() = runSuspendTest {
        var credential = account
        var requests = 0
        val http = HttpClient(MockEngine {
            requests++
            respond("abc", HttpStatusCode.OK)
        }) { followRedirects = false }
        val client = NextcloudNetworkClient(config, { credential }, { StorageCapabilities.ReadWrite }, http)
        try {
            read(client)
            credential = account.copy(server = "https://other.example.test")
            assertIs<IllegalStateException>(client.downloadByChunks("/file", 3) { _, _ ->
                Result.success(Unit)
            }.exceptionOrNull())
            credential = account.copy(kind = StorageAccountKind.GoogleDrive)
            assertIs<IllegalStateException>(client.downloadByChunks("/file", 3) { _, _ ->
                Result.success(Unit)
            }.exceptionOrNull())
            assertEquals(1, requests)
            credential = account
            read(client)
            assertEquals(2, requests)
        } finally {
            client.close()
            http.close()
        }
    }
}
