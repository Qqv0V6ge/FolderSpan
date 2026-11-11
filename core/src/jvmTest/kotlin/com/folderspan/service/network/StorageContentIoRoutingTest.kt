package com.folderspan.service.network

import com.folderspan.data.file.FileSimpleInfo
import com.folderspan.data.main.network.Network
import com.folderspan.data.main.network.CloudDriveExtras
import com.folderspan.data.main.network.NetworkDriveExtras
import com.folderspan.data.main.network.StorageContentLocationReference
import com.russhwolf.settings.MapSettings
import com.russhwolf.settings.Settings
import com.russhwolf.settings.PreferencesSettings
import com.folderspan.settings.SensitiveSettingKeys
import com.folderspan.settings.SensitiveSettings
import com.folderspan.settings.FileAesGcmSecretVault
import java.util.UUID
import java.util.prefs.Preferences
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.*

class StorageContentIoRoutingTest {
    private val reference = StorageContentLocationReference(
        root = StoragePath("/vault"), vaultId = "7395784c-8f4f-4d64-b126-4179ffbdb0a1", enabled = true)
    private val config = NetworkConnectionConfig("FTP", "fixture.invalid", "alice", "protocol-secret",
        extras = NetworkDriveExtras(contentEncryption = reference))
    private val target = StoragePath("/vault/file.txt")
    private val source = FileSimpleInfo.nullFileSimpleInfo().copy(path = "/local/source.txt", size = 5)
    private val payload = "hello".encodeToByteArray()

    private class Content(private val directory: Path) : StorageContentOperations by UnsupportedStorageContentOperations {
        val actual = StoragePath("/vault/.folderspan-vault/objects/immutable")
        val cipher = Files.write(directory.resolve("cipher"), byteArrayOf(8, 9, 10))
        val taskKeys = mutableListOf<Long>()
        var uploaded = false
        var committed = false
        var failRecord = false
        var failBegin = false
        var preparedPresent = false
        var records = 0
        var begins = 0
        var cleanups = 0
        var stages = 0
        override suspend fun status(config: NetworkConnectionConfig) = Result.success(
            StorageContentStatus("routing-fixture", enabled = config.extras.contentEncryption?.enabled == true, unlocked = true))
        override suspend fun uploadEncryptionEnabled(config: NetworkConnectionConfig, taskKey: Long, entryId: String) =
            Result.success(config.extras.contentEncryption?.enabled == true || preparedPresent)
        override suspend fun createStage(taskKey: Long, entryId: String): Result<String> {
            stages++
            return Result.success(Files.createTempFile(directory, "stage-", ".tmp").toString())
        }
        override suspend fun stageIdentity(taskKey: Long, path: String) = Result.success("fixture-identity")
        override suspend fun completeStageWrite(taskKey: Long, path: String, expectedIdentity: String) = Result.success(Unit)
        override suspend fun prepareUpload(config: NetworkConnectionConfig, taskKey: Long, entryId: String,
            source: FileSimpleInfo, stagedPath: String): Result<StoragePreparedContentUpload?> {
            taskKeys += taskKey
            preparedPresent = true
            return Result.success(StoragePreparedContentUpload(cipher.toString(), 3, "frozen-source", actual, uploaded, committed))
        }
        override suspend fun beginUpload(config: NetworkConnectionConfig, taskKey: Long, entryId: String, target: StoragePath): Result<Unit> {
            begins++
            assertEquals("/vault/file.txt", target.path)
            return if (failBegin) Result.failure(StorageContentException(StorageContentErrorCode.Locked, "authorization changed"))
                else Result.success(Unit)
        }
        override suspend fun recordUploaded(config: NetworkConnectionConfig, taskKey: Long, entryId: String,
            entry: NetworkFileEntry): Result<Unit> {
            records++
            assertEquals(actual.path, entry.path)
            return if (failRecord) Result.failure(StorageContentException(StorageContentErrorCode.UncertainResult, "reply lost"))
                else Result.success(Unit)
        }
        override suspend fun cleanup(taskKey: Long): Result<Unit> { cleanups++; preparedPresent = false; return Result.success(Unit) }
    }

    private suspend fun write(content: Content, provider: StorageProvider, settings: Settings,
        config: NetworkConnectionConfig = this.config): Result<Boolean>? {
        var supplied = false
        return uploadManagedStorageContent(provider, config, target, payload.size.toLong(), source,
            onProgress = { _, _ -> }, readChunk = { if (supplied) null else payload.also { supplied = true } },
            content = content, settings = settings)
    }

    @Test
    fun failedCommitKeepsOperationAndDisabledRetrySkipsAlreadyUploadedObject(): Unit = runBlocking {
        val directory = Files.createTempDirectory("content-upload-routing-")
        try {
            val content = Content(directory)
            val store = TestStorageContentVaultStore()
            val provider = store.provider()
            val settings = MapSettings()
            content.failRecord = true
            assertTrue(write(content, provider, settings)!!.isFailure)
            assertEquals(1, store.writes)
            assertEquals(setOf(content.actual.path), store.files.keys)
            assertEquals(0, content.cleanups)
            assertEquals(1, settings.keys.size)
            assertFalse(settings.keys.any { it.contains("protocol-secret") || it.contains("source.txt") })

            content.failRecord = false
            content.uploaded = true
            val disabled = config.copy(extras = config.extras.copy(contentEncryption = reference.copy(enabled = false)))
            assertTrue(write(content, provider, settings, disabled)!!.getOrThrow())
            assertEquals(1, store.writes, "an uploaded immutable object must not be truncated on commit retry")
            assertEquals(1, content.taskKeys.toSet().size)
            assertEquals(2, content.records)
            assertEquals(1, content.cleanups)
            assertTrue(settings.keys.isEmpty())
        } finally { directory.toFile().deleteRecursively() }
    }

    @Test
    fun preparedOperationSurvivesAuthorizationFailureBeforeBeginAndDisablingEncryption(): Unit = runBlocking {
        val directory = Files.createTempDirectory("content-prepared-policy-")
        try {
            val content = Content(directory).apply { failBegin = true }
            val store = TestStorageContentVaultStore()
            val settings = MapSettings()
            val first = write(content, store.provider(), settings)
            assertEquals(StorageContentErrorCode.Locked, assertIs<StorageContentException>(first?.exceptionOrNull()).code)
            assertEquals(0, store.writes)
            assertEquals(0, content.cleanups, "a prepared operation must retain its frozen encryption policy")
            assertTrue(content.preparedPresent)
            content.failBegin = false
            val disabled = config.copy(extras = config.extras.copy(contentEncryption = reference.copy(enabled = false)))
            assertTrue(write(content, store.provider(), settings, disabled)!!.getOrThrow())
            assertEquals(1, content.taskKeys.toSet().size)
            assertEquals(setOf(content.actual.path), store.files.keys)
            assertEquals(1, content.records)
            assertEquals(1, content.cleanups)
            assertTrue(settings.keys.isEmpty())
        } finally { directory.toFile().deleteRecursively() }
    }

    @Test
    fun durableOperationNonceSurvivesFileVaultReopenAndRejectsCorruptIdentity(): Unit = runBlocking {
        val directory = Files.createTempDirectory("content-durable-operation-")
        val node = Preferences.userRoot().node("folderspan-encryption-fg-" + UUID.randomUUID())
        val vaultDirectory = directory.resolve("secure-settings")
        try {
            val content = Content(directory).apply { failRecord = true }
            val store = TestStorageContentVaultStore()
            val first = SensitiveSettings(PreferencesSettings(node), FileAesGcmSecretVault(vaultDirectory))
            val failed = write(content, store.provider(), first)
            assertEquals(StorageContentErrorCode.UncertainResult,
                assertIs<StorageContentException>(failed?.exceptionOrNull()).code)
            assertEquals(1, content.begins)
            assertEquals(1, content.records)
            val key = first.keys.single()
            assertTrue(SensitiveSettingKeys.isSensitive(key))
            assertTrue(node.keys().isEmpty(), "the durable nonce belongs in the existing vault")
            val operation = checkNotNull(first.getStringOrNull(key))
            assertTrue(operation.toLong() > 0)

            val restored = SensitiveSettings(PreferencesSettings(node), FileAesGcmSecretVault(vaultDirectory))
            assertTrue(restored.hasKey(key))
            assertEquals(operation, restored.getStringOrNull(key))
            val stagesBeforeCorruption = content.stages
            restored.putString(key, "invalid-operation")
            val rejected = write(content, store.provider(), restored)
            assertEquals(StorageContentErrorCode.DamagedLocation,
                assertIs<StorageContentException>(rejected?.exceptionOrNull()).code)
            assertEquals("invalid-operation", restored.getStringOrNull(key))
            assertEquals(stagesBeforeCorruption, content.stages, "a corrupt identity must not allocate a new operation")
            restored.putString(key, operation)

            content.failRecord = false
            content.uploaded = true
            val disabled = config.copy(extras = config.extras.copy(contentEncryption = reference.copy(enabled = false)))
            assertTrue(write(content, store.provider(), restored, disabled)!!.getOrThrow())
            assertEquals(setOf(operation.toLong()), content.taskKeys.toSet())
            assertEquals(1, store.writes, "reopening the vault must reconcile the original operation without another raw put")
            assertFalse(restored.hasKey(key))
            assertNull(FileAesGcmSecretVault(vaultDirectory).get(key))
            assertTrue(node.keys().isEmpty())
        } finally {
            node.removeNode()
            Preferences.userRoot().flush()
            directory.toFile().deleteRecursively()
        }
    }

    @Test
    fun confirmedCommitSkipsRawUploadAndPublication(): Unit = runBlocking {
        val directory = Files.createTempDirectory("content-committed-routing-")
        try {
            val content = Content(directory).apply { committed = true; uploaded = true }
            val store = TestStorageContentVaultStore()
            val settings = MapSettings()
            assertTrue(write(content, store.provider(), settings)!!.getOrThrow())
            assertEquals(0, store.writes)
            assertEquals(0, content.records)
            assertEquals(1, content.begins)
            assertEquals(1, content.cleanups)
            assertTrue(settings.keys.isEmpty())
        } finally { directory.toFile().deleteRecursively() }
    }

    @Test
    fun enabledLocationDoesNotEncryptTargetsOutsideItsRoot(): Unit = runBlocking {
        val directory = Files.createTempDirectory("content-outside-routing-")
        try {
            var ordinaryChecks = 0
            val content = object : StorageContentOperations by UnsupportedStorageContentOperations {
                override suspend fun beginUpload(config: NetworkConnectionConfig, taskKey: Long, entryId: String,
                    target: StoragePath): Result<Unit> { ordinaryChecks++; return Result.success(Unit) }
            }
            val store = TestStorageContentVaultStore()
            val settings = MapSettings()
            assertNull(uploadManagedStorageContent(store.provider(), config, StoragePath("/outside.txt"), 0,
                onProgress = { _, _ -> }, readChunk = { null }, content = content, settings = settings))
            assertEquals(1, ordinaryChecks)
            assertEquals(0, store.writes)
            assertTrue(settings.keys.isEmpty())
        } finally { directory.toFile().deleteRecursively() }
    }

    @Test
    fun downloadUsesRawTargetSizeAndPublishesTheFrozenLogicalIdentity(): Unit = runBlocking {
        val directory = Files.createTempDirectory("content-download-routing-")
        try {
            val actual = StoragePath("/vault/.folderspan-vault/objects/download")
            val frozen = source.copy(path = "/vault/renamed.txt", size = 7)
            val store = TestStorageContentVaultStore().apply { files[actual.path] = byteArrayOf(1, 2, 3) }
            val content = object : StorageContentOperations by UnsupportedStorageContentOperations {
                override suspend fun resolveDownload(config: NetworkConnectionConfig, file: FileSimpleInfo) =
                    Result.success(StorageContentDownload(frozen, actual, 3))
                override suspend fun createStage(taskKey: Long, entryId: String) =
                    Result.success(Files.createTempFile(directory, "stage-", ".tmp").toString())
                override suspend fun stageIdentity(taskKey: Long, path: String) = Result.success("fixture-identity")
                override suspend fun completeStageWrite(taskKey: Long, path: String, expectedIdentity: String) = Result.success(Unit)
                override suspend fun publishDownload(config: NetworkConnectionConfig, taskKey: Long, file: FileSimpleInfo,
                    stagedPath: String, destinationPath: String): Result<Boolean> {
                    assertEquals(frozen, file)
                    assertContentEquals(byteArrayOf(1, 2, 3), Files.readAllBytes(Path.of(stagedPath)))
                    Files.writeString(Path.of(destinationPath), "verified")
                    return Result.success(true)
                }
            }
            val received = mutableListOf<Byte>()
            assertTrue(downloadVerifiedStorageChunks(store.provider(), config, 17, source,
                ensureRunning = {}, onChunk = { bytes, _ -> received += bytes.toList(); Result.success(Unit) },
                content = content).getOrThrow())
            assertContentEquals("verified".encodeToByteArray(), received.toByteArray())
        } finally { directory.toFile().deleteRecursively() }
    }

    @Test
    fun failedDownloadResolutionCannotFallBackToRawBytes(): Unit = runBlocking {
        var stages = 0
        val failure = StorageContentException(StorageContentErrorCode.Locked, "locked")
        val content = object : StorageContentOperations by UnsupportedStorageContentOperations {
            override suspend fun resolveDownload(config: NetworkConnectionConfig, file: FileSimpleInfo) =
                Result.failure<StorageContentDownload?>(failure)
            override suspend fun createStage(taskKey: Long, entryId: String): Result<String> {
                stages++; error("must not stage before resolving")
            }
        }
        val result = downloadVerifiedStorageChunks(TestStorageContentVaultStore().provider(), config, 18, source,
            ensureRunning = {}, onChunk = { _, _ -> error("must not publish raw bytes") }, content = content)
        assertSame(failure, result.exceptionOrNull())
        assertEquals(0, stages)
    }

    @Test
    fun foreignOrOrphanedContentReferencesAndLocationAncestorsCannotStartRawUploads(): Unit = runBlocking {
        val store = TestStorageContentVaultStore()
        val settings = MapSettings()
        val opaque = StoragePath("/vault/file.txt", StorageReference(id = "fsp-content:orphan"))
        val foreign = StoragePath("/vault/file.txt", StorageReference(content =
            StorageContentEntryReference("another-vault", "entry")))
        for (path in listOf(opaque, foreign)) {
            val result = uploadManagedStorageContent(store.provider(), config, path, 0,
                onProgress = { _, _ -> }, readChunk = { null },
                content = UnsupportedStorageContentOperations, settings = settings)
            assertEquals(StorageContentErrorCode.SourceChanged,
                assertIs<StorageContentException>(result?.exceptionOrNull()).code)
        }
        val ancestor = uploadManagedStorageContent(store.provider(), config, StoragePath("/"), 0,
            onProgress = { _, _ -> }, readChunk = { null },
            content = UnsupportedStorageContentOperations, settings = settings)
        assertEquals(StorageContentErrorCode.Conflict,
            assertIs<StorageContentException>(ancestor?.exceptionOrNull()).code)
        assertEquals(0, store.writes)
        assertTrue(settings.keys.isEmpty())
    }

    @Test
    fun ordinaryChildrenOfALocationAncestorKeepTheirOriginalCreateAndRenameRoutes(): Unit = runBlocking {
        val store = TestStorageContentVaultStore().apply { directories += "/vault" }
        val backend = store.provider()
        val provider = object : StorageProvider by backend {
            override suspend fun createFile(path: StoragePath): Result<Boolean> {
                store.files[path.path] = byteArrayOf()
                store.writes++
                return Result.success(true)
            }
        }
        val network = object : Network("scoped", "/", config.protocol, config.host, config.username, config.password,
            extras = config.extras) {
            override val client = provider
        }
        val parent = FileSimpleInfo.nullFileSimpleInfo().copy(path = "/", isDirectory = true)
        assertTrue(network.createFolder(parent, "outside-dir").getOrThrow())
        assertTrue(network.createFile(parent, "outside.txt").getOrThrow())
        assertTrue(network.createFolder("/", "string-dir").getOrThrow())
        assertTrue(network.createFile("/", "string.txt").getOrThrow())
        assertTrue(network.rename("/", "outside.txt", "renamed.txt").getOrThrow())
        assertTrue("/renamed.txt" in store.files && "/outside.txt" !in store.files)
        assertTrue("/outside-dir" in store.directories && "/string-dir" in store.directories)
        assertTrue(network.delete(parent).isFailure, "deleting the ancestor itself still needs protection")
        assertTrue("/vault" in store.directories)
    }

    @Test
    fun passwordLocationProtocolIdsBindPublicPositionButIgnoreEnabledAndCredentials() {
        fun identity(ref: StorageContentLocationReference?, cloud: CloudDriveExtras = CloudDriveExtras(),
            password: String = "protocol-secret") =
            Network("identity", "/", "SFTP", "fixture.invalid", "alice", password,
                extras = NetworkDriveExtras(cloud = cloud, contentEncryption = ref)).protocolId

        assertEquals("network:SFTP:fixture.invalid:alice", identity(null))
        val local = identity(reference)
        val cloud = CloudDriveExtras(connectionId = "local-one")
        val positions = listOf(
            local,
            identity(reference.copy(vaultId = "bd4d5d53-028f-445c-996e-51ce36f07d30")),
            identity(reference.copy(root = StoragePath("/another-root"))),
            identity(reference.copy(root = reference.root.copy(reference = StorageReference(id = "native-root")))),
            identity(reference.copy(formatVersion = 2)),
            identity(reference, cloud),
            identity(reference, cloud.copy(connectionId = "local-two")),
            identity(reference, cloud.copy(accountId = "account-two")),
            identity(reference, cloud.copy(rootId = "remote-root")),
            identity(reference, cloud.copy(libraryId = "remote-library")),
        )
        assertEquals(positions.size, positions.toSet().size)
        assertEquals(local, identity(reference.copy(enabled = false)))
        assertEquals(local, identity(reference, password = "different-protocol-secret"))
        assertEquals(identity(reference, cloud), identity(reference.copy(enabled = false), cloud))
        assertEquals("network:GoogleDrive:local-one",
            Network("cloud", "/", "GoogleDrive", "", "", "", extras = NetworkDriveExtras(cloud = cloud)).protocolId)
    }

    @Test
    fun privateVaultNamespaceCannotBeReachedThroughPublicNetworkPaths(): Unit = runBlocking {
        val store = TestStorageContentVaultStore()
        val network = object : Network("ordinary", "/", "FTP", "fixture.invalid", "alice", "protocol-secret") {
            override val client = store.provider()
        }
        for (path in listOf("/.folderspan-vault", "/dir/.folderspan-vault/object", "/.FOLDERSPAN-VAULT/object")) {
            assertTrue(network.getFile(path).isFailure)
            assertTrue(network.getList(path, null, null).isFailure)
            assertTrue(network.createFolder(StoragePath(path)).isFailure)
            assertTrue(network.downloadFileToLocal(source.copy(path = path), "/unused", { _, _ -> }).isFailure)
        }
        assertEquals(0, store.writes)
    }
}
