package com.folderspan.service.network

import com.folderspan.data.StatusEnum
import com.folderspan.data.file.FileProtocol
import com.folderspan.data.file.FileSimpleInfo
import com.folderspan.data.main.Local
import com.folderspan.data.main.network.Network
import com.folderspan.ui.state.main.*
import com.folderspan.data.main.network.CloudDriveExtras
import com.folderspan.data.main.network.NetworkDriveExtras
import com.folderspan.service.http.client.createNoProxyHttpClient
import com.folderspan.test.runSuspendTest
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import kotlinx.coroutines.delay
import kotlinx.serialization.json.*
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import org.junit.Assume.assumeTrue
import kotlin.test.*

/** Opt in only against a disposable instance; credentials remain in a private execution-host file. */
class NextcloudNativeIntegrationTest {
    @Test fun nativeVersionsRestoreAndSharingRoundTrip() = runSuspendTest {
        val credentialFile = System.getenv("FOLDERSPAN_NEXTCLOUD_TEST_CONFIG")
        assumeTrue("Set FOLDERSPAN_NEXTCLOUD_TEST_CONFIG to use a disposable Nextcloud instance", !credentialFile.isNullOrBlank())
        val server = canonicalStorageServer(System.getenv("FOLDERSPAN_NEXTCLOUD_TEST_URL") ?: error("Missing Nextcloud test URL"))
        val recipient = System.getenv("FOLDERSPAN_NEXTCLOUD_TEST_RECIPIENT") ?: error("Missing Nextcloud receiver")
        val secrets = Json.parseToJsonElement(Files.readString(Path.of(credentialFile!!))).jsonObject
        val username = secrets.getValue("username").jsonPrimitive.content
        val password = secrets.getValue("password").jsonPrimitive.content
        val folder = "/folderspan-contract-" + UUID.randomUUID()
        val config = NetworkConnectionConfig("Nextcloud", server, "", "", extras = NetworkDriveExtras(
            cloud = CloudDriveExtras(accountId = "integration-account", connectionId = "integration-connection", rootId = folder)))
        val account = StorageCredential("integration-account", StorageAccountKind.Nextcloud, username,
            "Integration", username, server, password)
        val http = createNoProxyHttpClient { expectSuccess = false; followRedirects = false }
        val provider = NextcloudNetworkClient(config, { account }, { StorageCapabilities.ReadWrite }, http)
        val selected = StoragePath("/version.txt")
        fun fileUrl(vararg parts: String) = storageUrl(server,
            listOf("remote.php", "dav", "files", username, folder.removePrefix("/")) + parts)
        suspend fun request(method: HttpMethod, url: String, content: String? = null): String =
            http.prepareRequest(url) {
                this.method = method; basicAuth(username, password)
                if (Url(url).encodedPath.startsWith("/ocs/")) header("OCS-APIRequest", "true")
                if (content != null) setBody(content)
            }.execute { response ->
                check(response.status.isSuccess()) { "Nextcloud integration operation failed (HTTP " + response.status.value + ")" }
                response.bodyAsText()
            }
        val createdShares = mutableListOf<StorageShare>()
        var createdFolder = false
        try {
            request(HttpMethod("MKCOL"), fileUrl()); createdFolder = true
            request(HttpMethod.Put, fileUrl("version.txt"), "old")
            request(HttpMethod.Put, fileUrl("version.txt"), "new")
            val capabilities = provider.advancedCapabilities(selected).getOrThrow()
            assertEquals(StorageFeatureAvailability.UNSUPPORTED,
                capabilities.availability(StorageAdvancedFeature.RESUMABLE_UPLOAD))
            assertEquals(StorageFeatureAvailability.AVAILABLE,
                capabilities.availability(StorageAdvancedFeature.LIST_VERSIONS))
            var versions = provider.listVersions(selected).getOrThrow()
            repeat(10) {
                if (versions.entries.isEmpty()) {
                    delay(250)
                    versions = provider.listVersions(selected).getOrThrow()
                }
            }
            assertNull(versions.nextCursor) // Native WebDAV versions have no cursor.
            val version = versions.entries.firstOrNull() ?: error("Nextcloud did not retain the overwritten version")
            var historical = ""
            provider.readVersion(version) { bytes, _ -> historical += bytes.decodeToString(); Result.success(Unit) }.getOrThrow()
            assertEquals("old", historical)
            assertTrue(provider.restoreVersion(version, false).isFailure)
            provider.restoreVersion(version, true).getOrThrow()
            assertEquals("old", request(HttpMethod.Get, fileUrl("version.txt")))

            // This disposable instance enables federation and adds the explicit reshare bit.
            // A caller asking for reader alone must not silently receive it.
            assertTrue(provider.createShare(selected, StorageShareRequest(StorageShareKind.LINK, "reader"), true).isFailure)
            val requestedExpiry = kotlin.time.Clock.System.now().toEpochMilliseconds() + 7 * 24 * 60 * 60 * 1000L
            val link = provider.createShare(selected, StorageShareRequest(StorageShareKind.LINK, "reader+reshare",
                expiresAt = requestedExpiry), true).getOrThrow()
            createdShares += link
            assertNotNull(link.url)
            val actualExpiry = assertNotNull(link.expiresAt)
            assertTrue(actualExpiry > kotlin.time.Clock.System.now().toEpochMilliseconds() && actualExpiry <= requestedExpiry)
            val capabilityData = Json.parseToJsonElement(request(HttpMethod.Get,
                storageUrl(server, listOf("ocs", "v2.php", "cloud", "capabilities"), mapOf("format" to "json"))))
                .jsonObject.getValue("ocs").jsonObject.getValue("data").jsonObject
            val timezone = TimeZone.of(capabilityData.getValue("capabilities").jsonObject.getValue("core").jsonObject
                .getValue("user").jsonObject.getValue("timezone").jsonPrimitive.content)
            val chosenDate = nextcloudExpiryDate(requestedExpiry, timezone, kotlin.time.Clock.System.now().toEpochMilliseconds())
            assertEquals(chosenDate, kotlin.time.Instant.fromEpochMilliseconds(actualExpiry).toLocalDateTime(timezone).date.toString())
            assertTrue(provider.createShare(selected,
                StorageShareRequest(StorageShareKind.INVITATION, "reader", recipient, requestedExpiry), true).isFailure)
            val invitation = provider.createShare(selected,
                StorageShareRequest(StorageShareKind.INVITATION, "reader", recipient), true).getOrThrow()
            createdShares += invitation
            val shares = provider.listShares(selected).getOrThrow()
            assertNull(shares.nextCursor)
            assertTrue(shares.entries.any { it.id == link.id && it.role == "reader+reshare" && it.expiresAt == actualExpiry })
            assertTrue(shares.entries.any { it.id == invitation.id && it.recipient == recipient })
            for (share in createdShares.toList()) {
                provider.revokeShare(share).getOrThrow()
                createdShares.remove(share)
            }
            assertTrue(provider.listShares(selected).getOrThrow().entries.isEmpty())
        } finally {
            for (share in createdShares) runCatching { provider.revokeShare(share).getOrThrow() }
            if (createdFolder) runCatching { request(HttpMethod.Delete, fileUrl()) }
            provider.close()
            http.close()
        }
    }
    @Test fun nativeSyncScanIncrementalCopyAndFailedItemRetry() = runSuspendTest {
        val credentialFile = System.getenv("FOLDERSPAN_NEXTCLOUD_TEST_CONFIG")
        assumeTrue("Set FOLDERSPAN_NEXTCLOUD_TEST_CONFIG for disposable sync integration", !credentialFile.isNullOrBlank())
        val server = canonicalStorageServer(System.getenv("FOLDERSPAN_NEXTCLOUD_TEST_URL") ?: error("Missing Nextcloud test URL"))
        require(Url(server).host in setOf("localhost", "127.0.0.1", "::1"))
        val secrets = Json.parseToJsonElement(Files.readString(Path.of(credentialFile!!))).jsonObject
        val username = secrets.getValue("username").jsonPrimitive.content
        val password = secrets.getValue("password").jsonPrimitive.content
        val folder = "/folderspan-sync-" + UUID.randomUUID()
        val config = NetworkConnectionConfig("Nextcloud", server, "", "", extras = NetworkDriveExtras(
            cloud = CloudDriveExtras(accountId = "integration-account", connectionId = "sync-" + UUID.randomUUID(), rootId = folder)))
        val account = StorageCredential("integration-account", StorageAccountKind.Nextcloud, username,
            "Integration", username, server, password)
        val http = createNoProxyHttpClient { expectSuccess = false; followRedirects = false }
        val provider = NextcloudNetworkClient(config, { account }, { StorageCapabilities.ReadWrite }, http)
        val localTaskState = TaskState(runtimeStore = TestTaskRuntimePersistenceStore())
        val network = object : Network("Disposable Nextcloud", "/", "Nextcloud", server, "", "") {
            override val client: StorageProvider = provider
            override val taskState: TaskState = localTaskState
        }
        val store = object : SyncTaskStore {
            override fun loadTasks() = emptyList<SyncTask>()
            override fun saveTask(task: SyncTask) = task
            override fun deleteTask(taskId: Long) = Unit
            override fun appendRun(record: SyncRunRecord) = Unit
            override fun loadRuns(taskId: Long) = emptyList<SyncRunRecord>()
            override fun deleteRuns(taskId: Long) = Unit
        }
        val sync = SyncState(store)
        val local = Files.createTempDirectory("folderspan-native-sync-")
        val task = Task(taskType = TaskType.Copy, status = StatusEnum.LOADING)
        localTaskState.addOrUpdate(task)
        val syncTask = SyncTask(1, "Disposable sync", sourceType = SyncEndpointType.Network,
            sourceRef = network.protocolId, sourcePath = "/", targetType = SyncEndpointType.Local,
            targetPath = local.toString())
        fun fileUrl(vararg parts: String) = storageUrl(server,
            listOf("remote.php", "dav", "files", username, folder.removePrefix("/")) + parts)
        suspend fun request(method: HttpMethod, url: String, content: String? = null) =
            http.prepareRequest(url) {
                this.method = method; basicAuth(username, password)
                if (content != null) setBody(content)
            }.execute { response ->
                check(response.status.isSuccess()) { "Nextcloud sync fixture failed (HTTP " + response.status.value + ")" }
                response.bodyAsText()
            }
        suspend fun scan(): SyncSourceSnapshot {
            val root = SyncEndpointFileOperations().readPathInfo(
                SyncEndpointType.Network, network.protocolId, network, "/").getOrThrow()
                ?: error("Disposable sync root is missing")
            return sync.collectSourceSnapshot(syncTask, network, root, listOf("*.tmp")).getOrThrow()
        }
        suspend fun copy(source: SyncSourceFileEntry): Boolean {
            val destination = local.resolve(source.relativePath)
            Files.createDirectories(destination.parent)
            val result = network.copyTo(task, source.file, source.file.copy(path = destination.toString(),
                protocol = FileProtocol.Local, protocolId = "", storageReference = null))
            if (result.isSuccess && result.getOrThrow()) {
                Files.setLastModifiedTime(destination, java.nio.file.attribute.FileTime.fromMillis(source.file.updatedDate))
                return true
            }
            return false
        }
        var created = false
        try {
            request(HttpMethod("MKCOL"), fileUrl()); created = true
            request(HttpMethod("MKCOL"), fileUrl("nested"))
            request(HttpMethod.Put, fileUrl("first.txt"), "first original")
            request(HttpMethod.Put, fileUrl("nested", "second.txt"), "second original")
            request(HttpMethod.Put, fileUrl("ignored.tmp"), "skip")
            val initial = scan()
            assertEquals(listOf("first.txt", "nested/second.txt"), initial.files.map { it.relativePath })
            assertTrue(initial.files.all { !it.file.storageReference?.id.isNullOrBlank() })
            Files.writeString(local.resolve("user-only.txt"), "retain")
            val second = initial.files.single { it.relativePath == "nested/second.txt" }
            Files.createDirectories(local.resolve(second.relativePath))
            assertTrue(copy(initial.files.first()))
            assertFalse(copy(second), "A local directory collision must remain a failed item")
            assertTrue(Files.isDirectory(local.resolve(second.relativePath)))
            Files.delete(local.resolve(second.relativePath))
            assertTrue(copy(second), "Retry the failed file with the same stable source identity")
            assertEquals("second original", Files.readString(local.resolve(second.relativePath)))
            suspend fun changedFiles(): List<SyncSourceFileEntry> {
                val target = sync.collectTargetSnapshot(syncTask, Local(), local.toString()).getOrThrow()
                return scan().files.filter { source ->
                    val destination = target.filesByRelative[source.relativePath]
                    destination == null || syncFileChanged(source.file, destination)
                }
            }
            assertTrue(changedFiles().isEmpty(), "An unchanged second scan must not copy files again")
            request(HttpMethod.Put, fileUrl("first.txt"), "first updated with different size")
            val changed = changedFiles()
            assertEquals(listOf("first.txt"), changed.map { it.relativePath })
            assertEquals(initial.files.first().file.storageReference?.id, changed.single().file.storageReference?.id)
            assertTrue(copy(changed.single()))
            assertEquals("first updated with different size", Files.readString(local.resolve("first.txt")))
            assertEquals("retain", Files.readString(local.resolve("user-only.txt")))
            assertFalse(Files.exists(local.resolve("ignored.tmp")))
            assertEquals("second original", request(HttpMethod.Get, fileUrl("nested", "second.txt")))
        } finally {
            try {
                if (created) request(HttpMethod.Delete, fileUrl())
            } finally {
                sync.stop()
                local.toFile().deleteRecursively()
                provider.close()
                http.close()
            }
        }
    }

}
