package com.folderspan.service.network

import com.folderspan.data.main.network.CloudDriveExtras
import com.folderspan.data.main.network.NetworkDriveExtras
import com.folderspan.test.runSuspendTest
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.*
import io.ktor.http.*
import kotlinx.serialization.json.*
import kotlin.test.*

class StorageContentManifestProviderTest {
    private val anchor = "00000000-0000-0000-0000-000000000001"
    private fun config(protocol: String) = NetworkConnectionConfig(protocol, "https://storage.example.test", "", "",
        extras = NetworkDriveExtras(cloud = CloudDriveExtras(accountId = "account", connectionId = "connection")))

    @Test fun nextcloudKeepsClassificationReadsButRefusesAllMetadataWrites() = runSuspendTest {
        var gets = 0
        var writes = 0
        var revision = "\"initial\""
        val http = HttpClient(MockEngine { request ->
            assertTrue(request.headers[HttpHeaders.Authorization]?.startsWith("Basic ") == true)
            assertTrue(request.url.encodedPath.endsWith(contentManifestFileName(anchor)))
            if (request.method == HttpMethod.Get) { gets++; respond("{}", headers = headersOf(HttpHeaders.ETag, revision)) }
            else { writes++; error("Unqualified backend must never receive a classification write") }
        }) { followRedirects = false }
        val provider = NextcloudNetworkClient(config("Nextcloud"), { StorageCredential("account", StorageAccountKind.Nextcloud,
            "user", "Test", "user", "https://storage.example.test", "synthetic-token") }, { StorageCapabilities.ReadWrite }, http)
        try {
            assertTrue(provider.contentManifestReadable)
            assertFalse(provider.contentManifestConditionalWrites)
            assertEquals("{}", provider.readContentManifest(anchor).getOrThrow()!!.bytes.decodeToString())
            for (expected in listOf(null, revision)) assertIs<UnsupportedOperationException>(
                provider.compareAndSetContentManifest(anchor, expected, "{}".encodeToByteArray()).exceptionOrNull())
            assertEquals(0, writes)
            assertFails { requireNoExistingStorageContentManifest(provider) }
            assertEquals(2, gets)
            revision = "W/\"weak\""
            assertTrue(provider.readContentManifest(anchor).isFailure)
        } finally { http.close() }
    }

    @Test fun dropboxUsesStrictNativeRevisionWithoutAutorenameAndDistinguishesMissingFromDenied() = runSuspendTest {
        val arguments = mutableListOf<JsonObject>()
        var downloadError: String? = null
        var uploadError: String? = null
        val revision = "0123456789abc"
        val http = HttpClient(MockEngine { request ->
            assertEquals("Bearer synthetic-token", request.headers[HttpHeaders.Authorization])
            val argument = Json.parseToJsonElement(request.headers["Dropbox-API-Arg"]!!).jsonObject
            assertEquals("/" + contentManifestFileName(anchor), argument.getValue("path").jsonPrimitive.content)
            if (request.url.encodedPath.endsWith("/download")) {
                if (downloadError != null) respond("{\"error_summary\":\"$downloadError\"}", HttpStatusCode.Conflict)
                else respond("{}", headers = headersOf("Dropbox-API-Result", "{\"rev\":\"$revision\"}"))
            } else {
                arguments += argument
                if (uploadError != null) respond("{\"error_summary\":\"$uploadError\"}", HttpStatusCode.Conflict)
                else respond("{\"rev\":\"$revision\",\"size\":2}")
            }
        }) { followRedirects = false }
        val provider = DropboxNetworkClient(config("Dropbox"), StorageHttpApi({ "synthetic-token" }, http))
        try {
            assertEquals(revision, provider.readContentManifest(anchor).getOrThrow()!!.revision)
            provider.compareAndSetContentManifest(anchor, null, "{}".encodeToByteArray()).getOrThrow()
            provider.compareAndSetContentManifest(anchor, revision, "{}".encodeToByteArray()).getOrThrow()
            assertEquals("add", arguments[0].getValue("mode").jsonPrimitive.content)
            assertEquals(revision, arguments[1].getValue("mode").jsonObject.getValue("update").jsonPrimitive.content)
            for (argument in arguments) {
                assertFalse(argument.getValue("autorename").jsonPrimitive.boolean)
                assertTrue(argument.getValue("strict_conflict").jsonPrimitive.boolean)
            }
            uploadError = "path/conflict/file/.."
            assertIs<StorageContentManifestConflictException>(provider.compareAndSetContentManifest(anchor, revision,
                "{}".encodeToByteArray()).exceptionOrNull())
            downloadError = "path/not_found/.."
            assertNull(provider.readContentManifest(anchor).getOrThrow())
            downloadError = "path/restricted_content/.."
            assertTrue(provider.readContentManifest(anchor).isFailure)
        } finally { http.close() }
    }

    @Test fun legacyProvidersRemainUnsupportedWithoutPretendingToOfferSafeMetadata() {
        assertFalse(UnsupportedNetworkClient("unused").contentManifestConditionalWrites)
    }
}
