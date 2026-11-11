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

class NextcloudStorageIdentityTest {
    private val davRoot = "/remote.php/dav/files/user/scope"

    private fun provider(http: HttpClient): NextcloudNetworkClient {
        val config = NetworkConnectionConfig("Nextcloud", "https://storage.example.test", "", "",
            extras = NetworkDriveExtras(cloud = CloudDriveExtras(rootId = "/scope",
                accountId = "account", connectionId = "connection")))
        return NextcloudNetworkClient(config, {
            StorageCredential("account", StorageAccountKind.Nextcloud, "user", "Test", "user",
                "https://storage.example.test", "synthetic-token")
        }, { StorageCapabilities.ReadWrite }, http)
    }

    private fun response(path: String, id: String, directory: Boolean = false, failedId: String? = null) =
        """<d:response><d:href>$path</d:href>
            ${failedId?.let { """<d:propstat><d:prop><oc:fileid>$it</oc:fileid></d:prop>
                <d:status>HTTP/1.1 404 Not Found</d:status></d:propstat>""" }.orEmpty()}
            <d:propstat><d:prop><oc:fileid>$id</oc:fileid>
            <d:getcontentlength>3</d:getcontentlength>
            <d:getlastmodified>Wed, 21 Oct 2015 07:28:00 GMT</d:getlastmodified>
            <d:resourcetype>${if (directory) "<d:collection/>" else ""}</d:resourcetype>
            </d:prop><d:status>HTTP/1.1 200 OK</d:status></d:propstat></d:response>"""

    private fun multistatus(vararg responses: String) =
        """<d:multistatus xmlns:d="DAV:" xmlns:oc="http://owncloud.org/ns">
            ${responses.joinToString("")}</d:multistatus>"""

    @Test
    fun replacedFileIsRejectedBeforeAnyStoragePathOperationStarts() = runSuspendTest {
        val requests = mutableListOf<String>()
        val http = HttpClient(MockEngine { request ->
            assertEquals(HttpMethod("PROPFIND"), request.method)
            assertEquals("0", request.headers["Depth"])
            requests += request.url.encodedPath
            respond(multistatus(response(request.url.encodedPath, "99")), HttpStatusCode.MultiStatus)
        }) { followRedirects = false }
        val client = provider(http)
        val selected = StoragePath("/file", StorageReference("23"))
        suspend fun rejected(label: String, operation: suspend () -> Result<*>) {
            val before = requests.size
            val error = assertIs<IllegalArgumentException>(operation().exceptionOrNull(), label)
            assertEquals("Nextcloud file identity changed", error.message, label)
            assertEquals(listOf("$davRoot/file"), requests.drop(before), label)
        }
        try {
            rejected("list") { client.list(selected) }
            rejected("stat") { client.stat(selected) }
            rejected("download") { client.download(selected, "/unused-nextcloud-identity", 3) }
            rejected("download chunks") {
                client.downloadByChunks(selected, 3) { _, _ -> fail("A replaced file must not deliver bytes") }
            }
            rejected("upload") { client.upload("/unused-nextcloud-identity", selected, 3) }
            rejected("upload source") {
                client.uploadFromSource(selected, 3) { fail("A replaced file must not consume source bytes") }
            }
            rejected("copy source") { client.copyFile(selected, StoragePath("/copy")) }
            rejected("rename") { client.rename(selected, "/renamed") }
            rejected("delete") { client.delete(selected, false) }
            rejected("create folder") { client.createFolder(selected) }
            rejected("create file") { client.createFile(selected) }
            rejected("content identity") { client.contentObjectIdentity(selected) }
        } finally {
            client.close()
            http.close()
        }
    }

    @Test
    fun copyChecksTheSelectedTargetIdentityAfterCheckingTheSource() = runSuspendTest {
        val paths = mutableListOf<String>()
        val http = HttpClient(MockEngine { request ->
            assertEquals(HttpMethod("PROPFIND"), request.method)
            assertEquals("0", request.headers["Depth"])
            paths += request.url.encodedPath
            val id = if (request.url.encodedPath == "$davRoot/source") "11" else "99"
            respond(multistatus(response(request.url.encodedPath, id)), HttpStatusCode.MultiStatus)
        }) { followRedirects = false }
        val client = provider(http)
        try {
            val result = client.copyFile(StoragePath("/source", StorageReference("11")),
                StoragePath("/target", StorageReference("23")))
            val error = assertIs<IllegalArgumentException>(result.exceptionOrNull())
            assertEquals("Nextcloud file identity changed", error.message)
            assertEquals(listOf("$davRoot/source", "$davRoot/target"), paths)
        } finally {
            client.close()
            http.close()
        }
    }

    @Test
    fun replacedParentOrAncestorRejectsChildOperationsWithoutLookingUpTheChild() = runSuspendTest {
        val paths = mutableListOf<String>()
        val http = HttpClient(MockEngine { request ->
            assertEquals(HttpMethod("PROPFIND"), request.method)
            assertEquals("0", request.headers["Depth"])
            paths += request.url.encodedPath
            respond(multistatus(response(request.url.encodedPath, "99", directory = true)), HttpStatusCode.MultiStatus)
        }) { followRedirects = false }
        val client = provider(http)
        try {
            val locations = listOf(
                StoragePath("/child", StorageReference(parentId = "23")) to davRoot,
                StoragePath("/dir/child", StorageReference(parentId = "23")) to "$davRoot/dir",
                StoragePath("/dir/nested/child", StorageReference(ancestorId = "23", ancestorPath = "/dir")) to "$davRoot/dir",
            )
            for ((path, expectedContainer) in locations) {
                suspend fun rejected(operation: suspend () -> Result<*>) {
                    val before = paths.size
                    val error = assertIs<IllegalArgumentException>(operation().exceptionOrNull())
                    assertEquals("Nextcloud file identity changed", error.message)
                    assertEquals(listOf(expectedContainer), paths.drop(before))
                }
                rejected { client.stat(path) }
                rejected { client.list(path) }
                rejected { client.createFolder(path) }
                rejected { client.uploadFromSource(path, 3) { fail("A replaced container must not consume source bytes") } }
            }
        } finally {
            client.close()
            http.close()
        }
    }

    @Test
    fun ancestorBoundaryEscapeIsRejectedWithoutSendingAnyRequest() = runSuspendTest {
        var requests = 0
        val http = HttpClient(MockEngine {
            requests++
            fail("An out-of-ancestor path must be rejected before HTTP")
        }) { followRedirects = false }
        val client = provider(http)
        try {
            val outside = StoragePath("/directory-other/file",
                StorageReference(ancestorId = "23", ancestorPath = "/directory"))
            val statError = assertIs<IllegalArgumentException>(client.stat(outside).exceptionOrNull())
            assertEquals("Nextcloud path is outside its selected ancestor", statError.message)
            val uploadError = assertIs<IllegalArgumentException>(client.uploadFromSource(outside, 3) {
                fail("An out-of-ancestor path must not consume source bytes")
            }.exceptionOrNull())
            assertEquals(statError.message, uploadError.message)
            assertEquals(0, requests)
        } finally {
            client.close()
            http.close()
        }
    }

    @Test
    fun rootStatUsesItsOwnMetadataAndMissingStatIsNotAnAuthorizationFailure() = runSuspendTest {
        val paths = mutableListOf<String>()
        val http = HttpClient(MockEngine { request ->
            assertEquals(HttpMethod("PROPFIND"), request.method)
            assertEquals("0", request.headers["Depth"])
            paths += request.url.encodedPath
            if (request.url.encodedPath == davRoot) {
                respond(multistatus(response(davRoot, "23", directory = true)), HttpStatusCode.MultiStatus)
            } else respond("", HttpStatusCode.NotFound)
        }) { followRedirects = false }
        val client = provider(http)
        try {
            val root = client.stat(StoragePath("/")).getOrThrow()
            assertTrue(root.isDirectory)
            assertEquals("/", root.path)
            assertEquals("23", root.reference?.id)
            assertEquals(-1L, root.size)
            assertIs<StorageEntryNotFoundException>(client.stat(StoragePath("/missing")).exceptionOrNull())
            assertEquals(listOf(davRoot, "$davRoot/missing"), paths)
        } finally {
            client.close()
            http.close()
        }
    }

    @Test
    fun nextcloudListingKeepsOnlySuccessfulStableIdsAndParsesHttpModificationDates() = runSuspendTest {
        var requests = 0
        val http = HttpClient(MockEngine { request ->
            requests++
            assertEquals(HttpMethod("PROPFIND"), request.method)
            assertEquals("1", request.headers["Depth"])
            assertEquals(davRoot, request.url.encodedPath)
            respond(multistatus(response("$davRoot/", "1", directory = true),
                response("$davRoot/file", "23", failedId = "99")), HttpStatusCode.MultiStatus)
        }) { followRedirects = false }
        val client = provider(http)
        try {
            val file = client.list("/").getOrThrow().single()
            assertEquals("/file", file.path)
            assertEquals("23", file.reference?.id)
            assertEquals(3L, file.size)
            assertEquals(1445412480000L, file.updatedDate)
            assertEquals(1, requests)
        } finally {
            client.close()
            http.close()
        }
    }
}
