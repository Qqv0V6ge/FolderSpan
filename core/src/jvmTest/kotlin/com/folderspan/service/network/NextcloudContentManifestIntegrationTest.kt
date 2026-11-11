package com.folderspan.service.network

import com.folderspan.data.main.network.CloudDriveExtras
import com.folderspan.data.main.network.NetworkDriveExtras
import com.folderspan.service.http.client.createNoProxyHttpClient
import com.folderspan.test.runSuspendTest
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import kotlinx.serialization.json.*
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import org.junit.Assume.assumeTrue
import kotlin.test.*

/** Disposable-instance regression: an unqualified backend never enables content writes, while reads detect an existing catalog. */
class NextcloudContentManifestIntegrationTest {
    @Test fun unqualifiedNativeBackendRejectsEnableWithoutWritingAndStillDetectsExistingClassification() = runSuspendTest {
        val credentialFile = System.getenv("FOLDERSPAN_NEXTCLOUD_TEST_CONFIG")
        assumeTrue("Set FOLDERSPAN_NEXTCLOUD_TEST_CONFIG to use a disposable instance", !credentialFile.isNullOrBlank())
        val server = canonicalStorageServer(System.getenv("FOLDERSPAN_NEXTCLOUD_TEST_URL") ?: error("Missing Nextcloud test URL"))
        val secrets = Json.parseToJsonElement(Files.readString(Path.of(credentialFile!!))).jsonObject
        val username = secrets.getValue("username").jsonPrimitive.content
        val password = secrets.getValue("password").jsonPrimitive.content
        val folder = "/folderspan-manifest-" + UUID.randomUUID()
        val anchor = UUID.randomUUID().toString()
        val config = NetworkConnectionConfig("Nextcloud", server, "", "", extras = NetworkDriveExtras(
            cloud = CloudDriveExtras(accountId = "manifest-account", connectionId = "manifest-connection", rootId = folder)))
        val account = StorageCredential("manifest-account", StorageAccountKind.Nextcloud, username, "Integration", username, server, password)
        val http = createNoProxyHttpClient { expectSuccess = false; followRedirects = false }
        val provider = NextcloudNetworkClient(config, { account }, { StorageCapabilities.ReadWrite }, http)
        val folderUrl = storageUrl(server, listOf("remote.php", "dav", "files", username, folder.removePrefix("/")))
        var owned = false
        try {
            http.prepareRequest(folderUrl) { method = HttpMethod("MKCOL"); basicAuth(username, password) }.execute {
                check(it.status == HttpStatusCode.Created) { "Disposable manifest folder creation failed" }
                owned = true
            }
            assertNull(provider.readContentManifest(anchor).getOrThrow())
            assertTrue(provider.contentManifestReadable)
            assertFalse(provider.contentManifestConditionalWrites)
            for (revision in listOf(null, "\"old-revision\"")) assertIs<UnsupportedOperationException>(
                provider.compareAndSetContentManifest(anchor, revision, "{}".encodeToByteArray()).exceptionOrNull())
            assertNull(provider.readContentManifest(anchor).getOrThrow())
            val settings = com.russhwolf.settings.MapSettings()
            val cache = Files.createTempDirectory("nextcloud-unqualified-content")
            try {
                StorageContentHostContext(StorageContentKeyStore(settings), settings, cache, { provider }).use { host ->
                    assertFalse(host.status(config).getOrThrow().metadataProtectionAvailable)
                    assertTrue(host.create(config).isFailure)
                    assertFalse(host.status(config).getOrThrow().hasKey)
                    assertTrue(host.setEnabled(config, true).isFailure)
                }
            } finally { cache.toFile().deleteRecursively() }
            assertNull(provider.readContentManifest(anchor).getOrThrow())
            // Install only an owned synthetic catalog through the direct disposable-service fixture.
            // Provider itself must not offer a public CAS backdoor on an unqualified backend.
            val manifestUrl = folderUrl + "/" + contentManifestFileName(anchor)
            http.preparePut(manifestUrl) { basicAuth(username, password); setBody("{}") }.execute {
                check(it.status.isSuccess()) { "Disposable classification fixture creation failed" }
            }
            assertContentEquals("{}".toByteArray(), provider.readContentManifest(anchor).getOrThrow()!!.bytes)
            assertFails { requireNoExistingStorageContentManifest(provider) }

        } finally {
            if (owned) http.prepareDelete(folderUrl) { basicAuth(username, password) }.execute {
                check(it.status.isSuccess()) { "Owned disposable manifest folder cleanup failed" }
            }
            provider.close()
            http.close()
        }
    }
}
