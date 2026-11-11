package com.folderspan.service.network

import com.folderspan.data.main.network.CloudDriveExtras
import com.folderspan.data.main.network.NetworkDriveExtras
import com.folderspan.service.http.client.createNoProxyHttpClient
import com.folderspan.test.runSuspendTest
import io.ktor.client.request.*
import io.ktor.client.request.forms.FormDataContent
import io.ktor.client.statement.*
import io.ktor.http.*
import kotlinx.coroutines.delay
import kotlinx.serialization.json.*
import org.junit.Assume.assumeTrue
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import kotlin.test.*

/** Opt in against this task's disposable Seafile; never prints credentials or share URLs. */
class SeafileNativeIntegrationTest {
    @Test fun nativeVersionsRestoreAndSharingRoundTrip() = runSuspendTest {
        val configFile = System.getenv("FOLDERSPAN_SEAFILE_TEST_CONFIG")
        assumeTrue("Set FOLDERSPAN_SEAFILE_TEST_CONFIG to use a disposable Seafile instance", !configFile.isNullOrBlank())
        val privateConfig = Json.parseToJsonElement(Files.readString(Path.of(configFile!!))).jsonObject
        val server = canonicalStorageServer(privateConfig.getValue("url").jsonPrimitive.content)
        val token = privateConfig.getValue("token").jsonPrimitive.content
        // Native sharing uses the account ID returned by admin account creation, not its contact email.
        val recipient = privateConfig.getValue("recipient").jsonPrimitive.content
        val http = createNoProxyHttpClient { expectSuccess = false; followRedirects = false }
        val api = StorageHttpApi({ token }, http)
        suspend fun request(method: HttpMethod, path: List<String>, form: Map<String, String>? = null): JsonElement =
            http.prepareRequest(storageUrl(server, path + "")) {
                this.method = method
                header(HttpHeaders.Authorization, "Bearer " + token)
                if (form != null) setBody(FormDataContent(Parameters.build {
                    form.forEach { (key, value) -> append(key, value) }
                }))
            }.execute { response ->
                check(response.status.isSuccess()) { "Seafile integration operation failed (HTTP " + response.status.value + ")" }
                val text = response.bodyAsText()
                if (text.isBlank()) JsonNull else Json.parseToJsonElement(text)
            }
        var repoId: String? = null
        var provider: SeafileNetworkClient? = null
        val shares = mutableListOf<StorageShare>()
        try {
            val libraryId = request(HttpMethod.Post, listOf("api2", "repos"), mapOf(
                "name" to "folderspan-contract-" + UUID.randomUUID())).jsonObject.getValue("repo_id").jsonPrimitive.content
            repoId = libraryId
            val config = NetworkConnectionConfig("Seafile", server, "", "", extras = NetworkDriveExtras(
                cloud = CloudDriveExtras(accountId = "integration-account", connectionId = "integration-connection", libraryId = libraryId)))
            val selectedProvider = SeafileNetworkClient(config, api)
            provider = selectedProvider
            val path = StoragePath("/version.txt")
            suspend fun upload(value: String) {
                var bytes: ByteArray? = value.encodeToByteArray()
                selectedProvider.uploadFromSource(path.path, bytes!!.size.toLong(), readChunk = {
                    bytes.also { bytes = null }
                }).getOrThrow()
            }
            upload("old")
            delay(1100)
            upload("new")
            val capabilities = selectedProvider.advancedCapabilities(path).getOrThrow()
            assertEquals(StorageFeatureAvailability.UNSUPPORTED,
                capabilities.availability(StorageAdvancedFeature.RESUMABLE_UPLOAD))
            assertEquals(StorageFeatureAvailability.AVAILABLE,
                capabilities.availability(StorageAdvancedFeature.LIST_VERSIONS))
            val versions = selectedProvider.listVersions(path).getOrThrow()
            assertTrue(versions.entries.size >= 2, "Seafile must retain both overwritten contents")
            assertNull(versions.nextCursor) // Small native history; paging behavior also has mock coverage.
            var historicalVersion: StorageVersion? = null
            for (version in versions.entries) {
                var content = ""
                selectedProvider.readVersion(version) { bytes, _ ->
                    content += bytes.decodeToString(); Result.success(Unit)
                }.getOrThrow()
                if (content == "old") historicalVersion = version
            }
            val selected = historicalVersion ?: error("Seafile did not retain the selected historical content")
            assertTrue(selectedProvider.restoreVersion(selected, false).isFailure)
            selectedProvider.restoreVersion(selected, true).getOrThrow()
            var restored = ""
            selectedProvider.downloadByChunks(path.path, 3) { bytes, _ ->
                restored += bytes.decodeToString(); Result.success(Unit)
            }.getOrThrow()
            assertEquals("old", restored)

            val requestedExpiry = kotlin.time.Clock.System.now().toEpochMilliseconds() + 7 * 24 * 60 * 60 * 1000L
            val link = selectedProvider.createShare(path,
                StorageShareRequest(StorageShareKind.LINK, "reader", expiresAt = requestedExpiry), true).getOrThrow()
            shares += link
            assertNotNull(link.url)
            val actualExpiry = assertNotNull(link.expiresAt)
            assertTrue(actualExpiry > kotlin.time.Clock.System.now().toEpochMilliseconds() && actualExpiry <= requestedExpiry)
            assertTrue(selectedProvider.listShares(path).getOrThrow().entries.any { it.id == link.id && it.role == "reader" && it.expiresAt == actualExpiry })
            assertTrue(selectedProvider.createShare(path,
                StorageShareRequest(StorageShareKind.INVITATION, "reader", recipient), true).isFailure)
            assertTrue(selectedProvider.createFolder("/invited").getOrThrow())
            val folder = StoragePath("/invited")
            assertTrue(selectedProvider.createShare(folder,
                StorageShareRequest(StorageShareKind.INVITATION, "reader", recipient, requestedExpiry), true).isFailure)
            val invitation = selectedProvider.createShare(folder,
                StorageShareRequest(StorageShareKind.INVITATION, "reader", recipient), true).getOrThrow()
            shares += invitation
            assertEquals(recipient, invitation.recipient)
            assertTrue(selectedProvider.listShares(folder).getOrThrow().entries.any { it.id == invitation.id && it.role == "reader" })
            for (share in shares.toList()) {
                selectedProvider.revokeShare(share).getOrThrow()
                shares.remove(share)
            }
            assertTrue(selectedProvider.listShares(path).getOrThrow().entries.isEmpty())
            assertTrue(selectedProvider.listShares(folder).getOrThrow().entries.isEmpty())
        } finally {
            for (share in shares) runCatching { provider?.revokeShare(share)?.getOrThrow() }
            repoId?.let { runCatching { request(HttpMethod.Delete, listOf("api2", "repos", it)) } }
            provider?.close()
            http.close()
        }
    }
}
