package com.folderspan.service.network

import com.folderspan.data.file.FileProtocol
import com.folderspan.data.file.FileSimpleInfo
import com.folderspan.data.main.network.CloudDriveExtras
import com.folderspan.data.main.network.NetworkDriveExtras
import com.folderspan.service.http.client.createNoProxyHttpClient
import com.folderspan.test.runSuspendTest
import com.folderspan.settings.restrictOwnerOnly
import com.russhwolf.settings.MapSettings
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.*
import org.junit.Assume.assumeTrue
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermission
import java.util.UUID
import kotlin.test.*

/**
 * Real official APIs, disabled unless a private execution-host grant file is explicitly supplied.
 * Each test owns one random folder. No third-party invitations or existing user files are changed.
 */
class OfficialCloudStorageAdvancedIntegrationTest {
    @Test fun googleDriveResumesRestoresAndRevokesOnlyItsOwnLink() = runSuspendTest {
        withSandbox("GoogleDrive") { verifyAdvancedRoundTrip() }
    }

    @Test fun dropboxResumesRestoresAndRevokesOnlyItsOwnLink() = runSuspendTest {
        withSandbox("Dropbox") { verifyAdvancedRoundTrip() }
    }

    @Test fun dropboxContentManifestRejectsStaleAndCompetingRevisions() = runSuspendTest {
        withSandbox("Dropbox") {
            assertTrue(provider.contentManifestConditionalWrites)
            val anchor = UUID.randomUUID().toString()
            assertNull(step("read absent manifest") { provider.readContentManifest(anchor).getOrThrow() })
            val initial = step("create manifest") {
                provider.compareAndSetContentManifest(anchor, null, "initial".encodeToByteArray()).getOrThrow()
            }
            val read = assertNotNull(step("read created manifest") { provider.readContentManifest(anchor).getOrThrow() })
            assertEquals(initial.revision, read.revision)
            assertContentEquals(initial.bytes, read.bytes)
            assertIs<StorageContentManifestConflictException>(step("reject duplicate create") {
                provider.compareAndSetContentManifest(anchor, null, "duplicate".encodeToByteArray()).exceptionOrNull()
            })
            val updated = step("update current manifest revision") {
                provider.compareAndSetContentManifest(anchor, read.revision, "updated".encodeToByteArray()).getOrThrow()
            }
            assertNotEquals(read.revision, updated.revision)
            assertIs<StorageContentManifestConflictException>(step("reject stale manifest revision") {
                provider.compareAndSetContentManifest(anchor, read.revision, "stale".encodeToByteArray()).exceptionOrNull()
            })
            assertContentEquals(updated.bytes, assertNotNull(step("check rejected write did not change manifest") {
                provider.readContentManifest(anchor).getOrThrow()
            }).bytes)

            val attempts = step("race two conditional manifest writers") {
                coroutineScope {
                    listOf("writer-a", "writer-b").map { value ->
                        async {
                            provider.compareAndSetContentManifest(anchor, updated.revision, value.encodeToByteArray())
                        }
                    }.awaitAll()
                }
            }
            assertEquals(1, attempts.count { it.isSuccess })
            assertEquals(1, attempts.count { it.exceptionOrNull() is StorageContentManifestConflictException })
            val winner = attempts.single { it.isSuccess }.getOrThrow()
            val final = assertNotNull(step("read winning manifest") { provider.readContentManifest(anchor).getOrThrow() })
            assertEquals(winner.revision, final.revision)
            assertContentEquals(winner.bytes, final.bytes)
        }
    }

    @Test fun dropboxEncryptedContentRecoversAcrossHostsAndRejectsDestroyedHeaders() = runSuspendTest {
        withSandbox("Dropbox") {
            val directory = Files.createTempDirectory("official-dropbox-content-")
            restrictOwnerOnly(directory, directory = true)
            val password = "disposable verified recovery password".toCharArray()
            val plaintext = ByteArray(StorageContentEncryptionV1.CHUNK_BYTES + 137) { ((it * 17 + 23) % 251).toByte() }
            val target = StoragePath("/encrypted-content.bin")
            val source = FileSimpleInfo.nullFileSimpleInfo().copy(path = "/generated/content-source", name = "content-source",
                size = plaintext.size.toLong(), updatedDate = 100, protocol = FileProtocol.Local)
            val factory: (NetworkConnectionConfig) -> StorageProvider = { selected ->
                createProvider(selected, api).also { providers += it }
            }
            try {
                val firstSettings = MapSettings()
                val firstKeys = StorageContentKeyStore(firstSettings)
                val backupFile = directory.resolve("recovery-v2.bin")
                step("initialize encrypted classification and read the saved recovery backup") {
                    StorageContentHostContext(firstKeys, firstSettings, directory.resolve("first"), factory).use { first ->
                        first.create(config).getOrThrow()
                        assertTrue(first.status(config).getOrThrow().metadataProtectionAvailable)
                        assertFalse(first.status(config).getOrThrow().enabled)
                        val backup = first.exportRecovery(config, password).getOrThrow()
                        try {
                            Files.createFile(backupFile)
                            restrictOwnerOnly(backupFile, directory = false)
                            Files.write(backupFile, backup)
                        } finally { backup.fill(0) }
                        assertEquals(setOf(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE),
                            Files.getPosixFilePermissions(backupFile))
                    }
                }
                val savedBackup = Files.readAllBytes(backupFile)
                val uploadSettings = MapSettings()
                val uploadKeys = StorageContentKeyStore(uploadSettings)
                lateinit var uploaded: NetworkFileEntry
                lateinit var ciphertext: ByteArray
                step("restore disabled by default and publish the registered encrypted output") {
                    StorageContentHostContext(uploadKeys, uploadSettings, directory.resolve("uploader"), factory).use { uploader ->
                        uploader.importRecovery(config, savedBackup, password).getOrThrow()
                        val restored = uploader.status(config).getOrThrow()
                        assertTrue(restored.hasKey && restored.backupVerified)
                        assertFalse(restored.enabled || restored.unlocked)
                        uploader.setEnabled(config, true).getOrThrow()
                        uploader.unlock(config).getOrThrow()
                        val stage = Path.of(uploader.createStage(701, "encrypted").getOrThrow())
                        Files.write(stage, plaintext)
                        assertEquals(setOf(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE),
                            Files.getPosixFilePermissions(stage))
                        val prepared = assertNotNull(uploader.prepareUpload(config, 701, "encrypted", source, stage.toString()).getOrThrow())
                        assertFalse(Files.exists(stage))
                        ciphertext = Files.readAllBytes(Path.of(prepared.path))
                        assertTrue(ciphertext.size > plaintext.size)
                        assertContentEquals("FSPANENC".toByteArray(Charsets.US_ASCII), ciphertext.copyOf(8))
                        assertEquals(setOf(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE),
                            Files.getPosixFilePermissions(Path.of(prepared.path)))
                        uploader.beginUpload(config, 701, "encrypted", target).getOrThrow()
                        assertTrue(provider.upload(prepared.path, target, prepared.size).getOrThrow())
                        uploaded = provider.stat(target).getOrThrow()
                        val stableId = assertNotNull(uploaded.reference).id
                        assertTrue(stableId.isNotBlank())
                        assertEquals(stableId, provider.contentObjectIdentity(StoragePath(target.path, uploaded.reference)).getOrThrow())
                        uploader.recordUploaded(config, 701, "encrypted", uploaded).getOrThrow()
                        uploader.cleanup(701).getOrThrow()
                        assertFalse(Files.exists(Path.of(prepared.path)))
                    }
                }
                val readerSettings = MapSettings()
                val readerKeys = StorageContentKeyStore(readerSettings)
                step("recover on another host and publish only authenticated cloud content") {
                    StorageContentHostContext(readerKeys, readerSettings, directory.resolve("reader"), factory).use { reader ->
                        reader.importRecovery(config, savedBackup, password).getOrThrow()
                        assertFalse(reader.status(config).getOrThrow().enabled)
                        reader.unlock(config).getOrThrow()
                        val destination = directory.resolve("downloaded-content")
                        Files.write(destination, byteArrayOf(99))
                        val selected = StoragePath(target.path, uploaded.reference)
                        val file = source.copy(path = target.path, size = uploaded.size, updatedDate = uploaded.updatedDate,
                            storageReference = uploaded.reference, protocol = FileProtocol.Network)
                        var stage = reader.createStage(702, "download").getOrThrow()
                        assertTrue(provider.download(selected, stage, uploaded.size).getOrThrow())
                        assertTrue(reader.publishDownload(config, 702, file, stage, destination.toString()).getOrThrow())
                        assertContentEquals(plaintext, Files.readAllBytes(destination))
                        assertFalse(Files.exists(Path.of(stage)))

                        val damaged = ciphertext.copyOf().also { it.fill(0x55, 0, 8) }
                        val corruptFile = write(selected, damaged)
                        assertEquals(uploaded.reference!!.id, corruptFile.reference?.id)
                        assertEquals(uploaded.reference!!.id, provider.contentObjectIdentity(selected).getOrThrow())
                        stage = reader.createStage(702, "destroyed-header").getOrThrow()
                        assertTrue(provider.download(selected, stage, damaged.size.toLong()).getOrThrow())
                        assertTrue(reader.publishDownload(config, 702, file.copy(updatedDate = corruptFile.updatedDate),
                            stage, destination.toString()).isFailure)
                        assertContentEquals(plaintext, Files.readAllBytes(destination))
                        assertFalse(Files.exists(Path.of(stage)))
                        reader.cleanup(702).getOrThrow()
                    }
                }
                savedBackup.fill(0)
            } finally {
                password.fill('\u0000')
                directory.toFile().deleteRecursively()
            }
        }
    }

    @Test fun googleDriveIfMatchProbeDoesNotEnableUnverifiedContentManifestWrites() = runSuspendTest {
        withSandbox("GoogleDrive") {
            assertFalse(provider.contentManifestConditionalWrites)
            val selected = write(StoragePath("/conditional-probe.bin"), byteArrayOf(1, 2, 3))
            val id = assertNotNull(selected.reference).id
            val target = storageUrl("https://www.googleapis.com/drive/v3/files", listOf(id),
                mapOf("fields" to "id,description", "supportsAllDrives" to "true"))
            val marker = "conditional-probe-" + UUID.randomUUID()
            val response = step("probe an intentionally mismatched If-Match on the test file") {
                val authorization = api.bearerHeaders()
                api.client.prepareRequest(target) {
                    method = HttpMethod.Patch
                    authorization.forEach { (name, value) -> header(name, value) }
                    header(HttpHeaders.IfMatch, "\"" + UUID.randomUUID() + "\"")
                    contentType(ContentType.Application.Json)
                    setBody(buildJsonObject { put("description", marker) }.toString())
                }.execute { it.status.value }
            }
            assertTrue(response == 200 || response == 412,
                "If-Match probe must be an authenticated successful write or an explicit precondition rejection")
            val metadata = step("verify If-Match probe result") {
                api.json(HttpMethod.Get, target).storageObject()
            }
            assertEquals(response == 200, metadata.storageOptionalString("description") == marker)
            // One metadata probe is insufficient evidence for safe media CAS. Keep production fail-closed.
            assertFalse(provider.contentManifestConditionalWrites)
            println("Google Drive test-file If-Match precondition rejected: " + (response == 412))
        }
    }

    private suspend fun Sandbox.verifyAdvancedRoundTrip() {
        val original = ByteArray(2 * 256 * 1024 + 37) { ((it * 31 + 17) % 251).toByte() }
        val target = StoragePath("/same-name.bin")
        var session = step("start resumable upload") { provider.startUpload(target, original.size.toLong()).getOrThrow() }
        assertEquals(0L, session.confirmedOffset)
        session = step("upload first aligned chunk") {
            provider.uploadChunk(session, 0, original.copyOfRange(0, 256 * 1024)).getOrThrow()
        }
        assertEquals((256 * 1024).toLong(), session.confirmedOffset)
        assertEquals(StorageUploadStatus.UPLOADING, session.status)

        // A fresh adapter must ask the server about the same protected session, not trust local progress.
        val resumed = createProvider(config, api)
        providers += resumed
        session = step("query confirmed offset from a fresh adapter") { resumed.queryUpload(session).getOrThrow() }
        assertEquals((256 * 1024).toLong(), session.confirmedOffset)
        session = step("resume remaining bytes") {
            resumed.uploadChunk(session, session.confirmedOffset,
                original.copyOfRange(session.confirmedOffset.toInt(), original.size)).getOrThrow()
        }
        session = step("finish resumed upload") { resumed.finishUpload(session).getOrThrow() }
        assertEquals(StorageUploadStatus.COMPLETED, session.status)
        assertEquals(original.size.toLong(), session.confirmedOffset)
        val first = assertNotNull(session.entry)
        val selected = StoragePath(target.path, first.reference)
        val selectedId = assertNotNull(first.reference).id
        assertTrue(selectedId.isNotBlank())
        assertContentEquals(original, read(selected))
        val originalVersions = versions(selected)
        val retained = originalVersions.firstOrNull() ?: error("Official service did not return the initial binary revision")
        assertEquals(selectedId, retained.file.reference?.id)
        assertTrue(retained.connectionBinding.isNotBlank())

        step("create a same-name neighbour") { provider.createFolder(StoragePath("/other")).getOrThrow() }
        val neighbourBytes = "another file with the same basename".encodeToByteArray()
        val neighbour = write(StoragePath("/other/same-name.bin"), neighbourBytes)
        val replacement = "replacement bytes for the selected stable file".encodeToByteArray()
        val overwritten = write(selected, replacement)
        assertEquals(selectedId, overwritten.reference?.id)
        assertContentEquals(replacement, read(selected))
        assertTrue(versions(selected).any { it.id == retained.id })
        assertTrue(step("require explicit restore confirmation") { provider.restoreVersion(retained, false).isFailure })
        assertContentEquals(replacement, read(selected))
        val restored = step("restore the selected original revision") { provider.restoreVersion(retained, true).getOrThrow() }
        assertEquals(selectedId, restored.reference?.id)
        assertContentEquals(original, read(selected))
        assertContentEquals(neighbourBytes, read(StoragePath(neighbour.path, neighbour.reference)))
        val historical = ByteArrayOutputStream()
        step("read the selected retained revision") {
            provider.readVersion(retained) { bytes, _ -> historical.write(bytes); Result.success(Unit) }.getOrThrow()
        }
        assertContentEquals(original, historical.toByteArray())

        val role = if (protocol == "GoogleDrive") "reader" else "viewer"
        val request = StorageShareRequest(StorageShareKind.LINK, role)
        assertTrue(step("require explicit link creation confirmation") {
            provider.createShare(selected, request, false).isFailure
        })
        assertTrue(shares(selected).none { it.kind == StorageShareKind.LINK })
        val share = step("create a link with the requested role") { provider.createShare(selected, request, true).getOrThrow() }
        createdShares += share
        assertEquals(StorageShareKind.LINK, share.kind)
        assertEquals(role, share.role)
        assertEquals(selectedId, share.file.reference?.id)
        assertNull(share.recipient)
        assertNull(share.expiresAt)
        assertTrue(share.canRevoke)
        assertTrue(share.connectionBinding.isNotBlank())
        assertTrue(!share.url.isNullOrBlank())
        assertTrue(shares(selected).any { it.id == share.id && it.kind == share.kind && it.role == role })
        step("revoke only the newly created link") { provider.revokeShare(share).getOrThrow() }
        createdShares.remove(share)
        assertTrue(shares(selected).none { it.id == share.id && it.kind == share.kind })
        assertContentEquals(original, read(selected))
    }

    private class Grant(val token: String, val providerAccountId: String) {
        override fun toString() = "OfficialIntegrationGrant()"
    }

    private fun readGrant(protocol: String): Grant {
        val variable = if (protocol == "GoogleDrive") "FOLDERSPAN_OFFICIAL_GOOGLE_GRANT_FILE"
            else "FOLDERSPAN_OFFICIAL_DROPBOX_GRANT_FILE"
        val configured = System.getenv(variable)
        assumeTrue("Set $variable to opt in to real official cloud API verification", !configured.isNullOrBlank())
        // Never attach the JSON parser exception: it may contain the private input.
        return try {
            val path = Path.of(configured!!).toAbsolutePath()
            check(Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) && Files.size(path) <= 256 * 1024)
            val permissions = Files.getPosixFilePermissions(path, LinkOption.NOFOLLOW_LINKS)
            check(permissions.none { it in setOf(PosixFilePermission.GROUP_READ, PosixFilePermission.GROUP_WRITE,
                PosixFilePermission.GROUP_EXECUTE, PosixFilePermission.OTHERS_READ,
                PosixFilePermission.OTHERS_WRITE, PosixFilePermission.OTHERS_EXECUTE) })
            val json = Json.parseToJsonElement(Files.readString(path)).jsonObject
            check(json["status"]?.jsonPrimitive?.content == "authorized")
            val scopes = json["grantedScopes"]?.jsonArray?.map { it.jsonPrimitive.content }?.toSet().orEmpty()
            val request = StorageAuthorizationRequest(protocol, replaceAccountId = "integration-existing",
                permissions = setOf(StorageAuthorizationPermission.SHARING))
            check(request.hasGrantedPermissions(scopes))
            check((json["expiresAt"]?.jsonPrimitive?.longOrNull ?: 0L) > System.currentTimeMillis() + 120_000)
            val token = json["accessToken"]?.jsonPrimitive?.content.orEmpty()
            val accountId = json["providerAccountId"]?.jsonPrimitive?.content.orEmpty()
            check(token.isNotBlank() && accountId.isNotBlank())
            Grant(token, accountId)
        } catch (_: Throwable) {
            throw AssertionError("Official cloud grant file must be private, current, and contain confirmed sharing scopes")
        }
    }

    private inner class Sandbox(
        val protocol: String,
        val provider: StorageProvider,
        val config: NetworkConnectionConfig,
        val api: StorageHttpApi,
    ) {
        val providers = mutableListOf(provider)
        val createdShares = mutableListOf<StorageShare>()

        suspend fun <T> step(operation: String, block: suspend () -> T): T = safeStep(protocol, operation, block)

        suspend fun write(path: StoragePath, bytes: ByteArray): NetworkFileEntry {
            var pending: ByteArray? = bytes
            assertTrue(step("upload bytes to the selected test file") {
                provider.uploadFromSource(path, bytes.size.toLong(), readChunk = {
                    pending.also { pending = null }
                }).getOrThrow()
            })
            return step("stat the selected test file") { provider.stat(path).getOrThrow() }
        }

        suspend fun read(path: StoragePath): ByteArray {
            val buffer = ByteArrayOutputStream()
            assertTrue(step("read the selected test file") {
                (provider as ChunkReadableNetworkClient).downloadByChunks(path, -1) { bytes, _ ->
                    buffer.write(bytes); Result.success(Unit)
                }.getOrThrow()
            })
            return buffer.toByteArray()
        }

        suspend fun versions(path: StoragePath): List<StorageVersion> = pages { cursor ->
            step("list revisions of the selected test file") { provider.listVersions(path, cursor).getOrThrow() }
        }

        suspend fun shares(path: StoragePath): List<StorageShare> = pages { cursor ->
            step("list grants of the selected test file") { provider.listShares(path, cursor).getOrThrow() }
        }
    }

    private suspend fun <T> pages(fetch: suspend (String?) -> StoragePage<T>): List<T> {
        var cursor: String? = null
        val seen = mutableSetOf<String>()
        val result = mutableListOf<T>()
        repeat(20) {
            val page = fetch(cursor)
            result += page.entries
            cursor = page.nextCursor ?: return result
            check(seen.add(cursor!!)) { "Official cloud pagination cycle" }
        }
        error("Official cloud test page limit exceeded")
    }

    private fun createProvider(config: NetworkConnectionConfig, api: StorageHttpApi): StorageProvider =
        when (config.protocol) {
            "GoogleDrive" -> GoogleDriveNetworkClient(config, api)
            "Dropbox" -> DropboxNetworkClient(config, api)
            else -> error("Unexpected official integration provider")
        }

    private suspend fun withSandbox(protocol: String, block: suspend Sandbox.() -> Unit) {
        val grant = readGrant(protocol)
        val http = createNoProxyHttpClient {
            expectSuccess = false
            followRedirects = false
            install(HttpTimeout) { requestTimeoutMillis = 45_000; connectTimeoutMillis = 15_000; socketTimeoutMillis = 45_000 }
        }
        val api = StorageHttpApi({ grant.token }, http)
        val scopeId = UUID.randomUUID().toString()
        val baseConfig = NetworkConnectionConfig(protocol, "", "", "", extras = NetworkDriveExtras(
            cloud = CloudDriveExtras(accountId = grant.providerAccountId, connectionId = scopeId)))
        val base = createProvider(baseConfig, api)
        val folderPath = "/codex-unified-storage-" + scopeId
        var ownedFolder: StoragePath? = null
        var sandbox: Sandbox? = null
        var failure: Throwable? = null
        try {
            safeStep(protocol, "create the isolated test folder") { base.createFolder(StoragePath(folderPath)).getOrThrow() }
            ownedFolder = StoragePath(folderPath)
            val directory = safeStep(protocol, "select the isolated test folder by stable ID") {
                base.stat(ownedFolder!!).getOrThrow()
            }
            val reference = assertNotNull(directory.reference)
            assertTrue(directory.isDirectory && reference.id.isNotBlank())
            ownedFolder = StoragePath(folderPath, reference)
            val scoped = baseConfig.copy(extras = baseConfig.extras.copy(
                cloud = baseConfig.extras.cloud.copy(rootId = reference.id)))
            val active = Sandbox(protocol, createProvider(scoped, api), scoped, api)
            sandbox = active
            withTimeout(300_000) { active.block() }
        } catch (caught: Throwable) {
            failure = caught
            throw caught
        } finally {
            val cleanupFailed = withContext(NonCancellable) {
                var failed = false
                try {
                    withTimeout(90_000) {
                        sandbox?.let { owned ->
                            for (share in owned.createdShares.toList()) {
                                if (runCatching { owned.provider.revokeShare(share).getOrThrow() }.isFailure) failed = true
                            }
                        }
                        ownedFolder?.let { selected ->
                            if (runCatching { base.delete(selected, true).getOrThrow() }.isFailure) failed = true
                        }
                    }
                } catch (_: Throwable) { failed = true }
                sandbox?.providers?.forEach { runCatching { it.close() } }
                runCatching { base.close() }
                http.close()
                failed
            }
            if (cleanupFailed) {
                val cleanup = AssertionError("Official integration cleanup was not confirmed for this test's random folder and grants")
                if (failure != null) failure.addSuppressed(cleanup) else throw cleanup
            }
        }
    }

    private suspend fun <T> safeStep(protocol: String, operation: String, block: suspend () -> T): T = try {
        block()
    } catch (failure: CancellationException) {
        throw failure
    } catch (failure: Throwable) {
        val category = if (failure is StorageApiException) "HTTP " + failure.status else failure.javaClass.simpleName
        // A session URL or response body can carry a secret, so do not include exception messages/causes.
        throw AssertionError("$protocol: $operation failed ($category)")
    }
}
