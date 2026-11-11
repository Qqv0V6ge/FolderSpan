package com.folderspan.service.network

import com.folderspan.data.file.FileProtocol
import com.folderspan.data.file.FileSimpleInfo
import com.folderspan.data.main.network.CloudDriveExtras
import com.folderspan.data.main.network.NetworkDriveExtras
import com.folderspan.test.createInMemorySettings
import com.folderspan.test.runSuspendTest
import com.folderspan.ui.state.main.TaskRuntimeUploadSessionReference
import com.folderspan.ui.state.main.TaskRuntimeEndpointRef
import com.folderspan.ui.state.main.TaskRuntimeStoreLock
import okio.ByteString.Companion.toByteString
import okio.FileSystem
import okio.Path
import okio.Path.Companion.toPath
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid
import kotlin.test.*
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope

@OptIn(ExperimentalUuidApi::class)
class StorageContentHostContextTest {
    private val fs = FileSystem.SYSTEM
    private fun temporaryDirectory(prefix: String): Path =
        (FileSystem.SYSTEM_TEMPORARY_DIRECTORY / (prefix + Uuid.random())).also {
            StorageContentFileProtection.createPrivateDirectory(it)
        }
    private fun write(path: Path, bytes: ByteArray) = fs.write(path) { write(bytes) }
    private fun read(path: Path): ByteArray = fs.read(path) { readByteArray() }
    private fun exists(path: Path): Boolean = fs.exists(path)

    private val config = NetworkConnectionConfig(
        "GoogleDrive", "fixture.invalid", "", "",
        extras = NetworkDriveExtras(cloud = CloudDriveExtras(accountId = "account-a", connectionId = "connection-a", rootId = "root-a")),
    )
    private val source = FileSimpleInfo.nullFileSimpleInfo().copy(
        path = "/source/a", name = "a", size = 5, updatedDate = 100, protocol = FileProtocol.Local,
    )
    private val provider = TestStorageContentProvider()
    private val password = "verified-recovery-password".toCharArray()

    @Test
    fun completedCipherSpoolSurvivesRestartAndChangedSourceGetsFreshOutput() = runSuspendTest {
        val settings = createInMemorySettings()
        val root = temporaryDirectory("content-spool-test")
        try {
            val first = StorageContentHostContext(StorageContentKeyStore(settings), settings, root, { provider })
            enable(first)
            val encrypted = prepare(first, byteArrayOf(1, 2, 3, 4, 5))
            val original = read(encrypted.path.toPath())
            first.close()

            StorageContentHostContext(StorageContentKeyStore(settings), settings, root, { provider }).use { reopened ->
                reopened.unlock(config).getOrThrow()
                val same = prepare(reopened, byteArrayOf(1, 2, 3, 4, 5))
                assertEquals(encrypted.path, same.path)
                assertContentEquals(original, read(same.path.toPath()))
                val changed = prepare(reopened, byteArrayOf(9, 2, 3, 4, 5))
                assertNotEquals(same.path, changed.path)
                assertNotEquals(same.sourceVersion, changed.sourceVersion)
                assertFalse(original.contentEquals(read(changed.path.toPath())))
                reopened.cleanup(7).getOrThrow()
                assertFalse(exists(changed.path.toPath()))
            }
        } finally { fs.deleteRecursively(root) }
    }

    @Test
    fun corruptedCiphertextAndWrongAccountNeverReplaceExistingFile() = runSuspendTest {
        val settings = createInMemorySettings()
        val root = temporaryDirectory("content-publish-test")
        try {
            StorageContentHostContext(StorageContentKeyStore(settings), settings, root, { provider }).use { host ->
                enable(host)
                val encrypted = prepare(host, byteArrayOf(1, 2, 3, 4, 5))
                val ciphertext = read(encrypted.path.toPath())
                val destination = root.resolve("existing")
                write(destination, byteArrayOf(99))
                val damaged = ciphertext.copyOf().also { it[it.lastIndex] = (it.last().toInt() xor 1).toByte() }
                var stage = host.createStage(7, "download").getOrThrow()
                write(stage.toPath(), damaged)
                assertTrue(host.publishDownload(config, 7, source, stage, destination.toString()).isFailure)
                assertContentEquals(byteArrayOf(99), read(destination))
                assertFalse(exists(stage.toPath()))

                val other = config.copy(extras = config.extras.copy(cloud = config.extras.cloud.copy(accountId = "account-b")))
                stage = host.createStage(7, "download").getOrThrow()
                write(stage.toPath(), ciphertext)
                assertTrue(host.publishDownload(other, 7, source, stage, destination.toString()).isFailure)
                assertContentEquals(byteArrayOf(99), read(destination))

                host.setEnabled(config, false).getOrThrow()
                host.unlock(config).getOrThrow()
                stage = host.createStage(7, "download").getOrThrow()
                write(stage.toPath(), ciphertext)
                assertTrue(host.publishDownload(config, 7, source, stage, destination.toString()).getOrThrow())
                assertContentEquals(byteArrayOf(1, 2, 3, 4, 5), read(destination))
            }
        } finally { fs.deleteRecursively(root) }
    }

    @Test
    fun logicalMetadataRequiresExactRemoteIdentityAndVersion() = runSuspendTest {
        val settings = createInMemorySettings()
        val root = temporaryDirectory("content-logical-test")
        try {
            StorageContentHostContext(StorageContentKeyStore(settings), settings, root, { provider }).use { host ->
                enable(host)
                val encrypted = prepare(host, byteArrayOf(1, 2, 3, 4, 5))
                val reference = StorageReference("file-a", "parent-a")
                val entry = NetworkFileEntry("a", "/target/a", false, encrypted.size, updatedDate = 200, reference = reference)
                host.beginUpload(config, 7, "entry", StoragePath(entry.path)).getOrThrow()
                provider.put(entry.path, read(encrypted.path.toPath()), reference.id)
                host.recordUploaded(config, 7, "entry", entry).getOrThrow()
                val file = source.copy(size = encrypted.size, updatedDate = 200, storageReference = reference)
                val logical = assertNotNull(host.logicalFile(config, file).getOrThrow())
                assertEquals(source.size, logical.size)
                assertEquals(source.updatedDate, logical.updatedDate)
                assertNull(host.logicalFile(config, file.copy(updatedDate = 201)).getOrThrow())
                assertNull(host.logicalFile(config, file.copy(size = encrypted.size + 1)).getOrThrow())
                assertNull(host.logicalFile(config, file.copy(storageReference = reference.copy(id = "other"))).getOrThrow())
                val other = config.copy(extras = config.extras.copy(cloud = config.extras.cloud.copy(accountId = "account-b")))
                assertNull(host.logicalFile(other, file).getOrThrow())
                val damaged = read(encrypted.path.toPath()).also { it[0] = 0 }
                val stage = host.createStage(7, "damaged-header").getOrThrow()
                write(stage.toPath(), damaged)
                val destination = root.resolve("header-existing")
                write(destination, byteArrayOf(99))
                assertTrue(host.publishDownload(config, 7, file, stage, destination.toString()).isFailure)
                assertContentEquals(byteArrayOf(99), read(destination))
            }
        } finally { fs.deleteRecursively(root) }
    }

    @Test
    fun stagesBelongToTheirContextAndTaskWhileOrdinaryFilesPublishNormally() = runSuspendTest {
        val settings = createInMemorySettings()
        val root = temporaryDirectory("content-stage-test")
        try {
            StorageContentHostContext(StorageContentKeyStore(settings), settings, root, { provider }).use { first ->
                StorageContentHostContext(StorageContentKeyStore(settings), settings, root, { provider }).use { other ->
                    val stage = first.createStage(7, "plain").getOrThrow()
                    write(stage.toPath(), byteArrayOf(1, 2))
                    val destination = root.resolve("destination")
                    assertTrue(other.publishDownload(config, 7, source, stage, destination.toString()).isFailure)
                    assertTrue(first.publishDownload(config, 8, source, stage, destination.toString()).isFailure)
                    assertFalse(exists(destination))
                    assertTrue(first.publishDownload(config, 7, source, stage, destination.toString()).getOrThrow())
                    assertContentEquals(byteArrayOf(1, 2), read(destination))
                    assertFalse(exists(stage.toPath()))
                }
            }
        } finally { fs.deleteRecursively(root) }
    }

    @Test
    fun failedVerificationNeverSendsBytesToAChunkConsumer() = runSuspendTest {
        val settings = createInMemorySettings()
        val root = temporaryDirectory("content-verified-chunks")
        try {
            StorageContentHostContext(StorageContentKeyStore(settings), settings, root, { provider }).use { host ->
                enable(host)
                val prepared = prepare(host, byteArrayOf(1, 2, 3, 4, 5))
                val cipher = read(prepared.path.toPath())
                val file = source.copy(size = cipher.size.toLong())
                var received = 0
                suspend fun attempt(bytes: ByteArray, chosenConfig: NetworkConnectionConfig = config) =
                    downloadVerifiedStorageChunks(
                        provider = BytesProvider(bytes), config = chosenConfig, taskKey = 7, file = file,
                        ensureRunning = {}, onChunk = { bytes, _ -> received += bytes.size; Result.success(Unit) },
                        content = host,
                    )
                val corrupted = cipher.copyOf().also { it[it.lastIndex] = (it.last().toInt() xor 1).toByte() }
                assertTrue(attempt(corrupted).isFailure)
                assertEquals(0, received)
                host.lock(config).getOrThrow()
                assertTrue(attempt(cipher).isFailure)
                assertEquals(0, received)
                host.unlock(config).getOrThrow()
                val other = config.copy(extras = config.extras.copy(cloud = config.extras.cloud.copy(accountId = "other")))
                assertTrue(attempt(cipher, other).isFailure)
                assertEquals(0, received)
                val wrongSettings = createInMemorySettings()
                val wrongProvider = TestStorageContentProvider()
                StorageContentHostContext(StorageContentKeyStore(wrongSettings), wrongSettings, root.resolve("wrong-key"), { wrongProvider }).use { wrong ->
                    enable(wrong)
                    assertTrue(downloadVerifiedStorageChunks(
                        provider = BytesProvider(cipher), config = config, taskKey = 9, file = file,
                        ensureRunning = {}, onChunk = { bytes, _ -> received += bytes.size; Result.success(Unit) },
                        content = wrong,
                    ).isFailure)
                    assertEquals(0, received)
                }
                assertTrue(attempt(cipher).getOrThrow())
                assertEquals(5, received)
            }
        } finally { fs.deleteRecursively(root) }
    }

    private class BytesProvider(private val bytes: ByteArray) : StorageProvider {
        override suspend fun download(remotePath: String, localPath: String, size: Long, onProgress: (Long, Long) -> Unit): Result<Boolean> {
            FileSystem.SYSTEM.write(localPath.toPath()) { write(bytes) }
            return Result.success(true)
        }
        override suspend fun list(path: String, requestId: String?, batchId: String?) = Result.success(emptyList<NetworkFileEntry>())
        override suspend fun upload(localPath: String, remotePath: String, size: Long, onProgress: (Long, Long) -> Unit) = Result.success(true)
        override suspend fun uploadFromSource(remotePath: String, size: Long, onProgress: (Long, Long) -> Unit, readChunk: suspend () -> ByteArray?) = Result.success(true)
        override suspend fun rename(path: String, newPath: String) = Result.success(true)
        override suspend fun delete(path: String, isDirectory: Boolean) = Result.success(true)
        override suspend fun createFolder(path: String) = Result.success(true)
        override suspend fun createFile(path: String) = Result.success(true)
    }

    @Test
    fun uncertainUploadKeepsCipherSpoolWhenTaskCleanupIsRequested() = runSuspendTest {
        val settings = createInMemorySettings()
        val root = temporaryDirectory("content-uncertain-cleanup")
        try {
            StorageContentHostContext(StorageContentKeyStore(settings), settings, root, { provider }).use { host ->
                enable(host)
                val prepared = prepare(host, byteArrayOf(1, 2, 3, 4, 5))
                val sessions = StorageUploadSessionStore(settings)
                val reference = TaskRuntimeUploadSessionReference(
                    sessionId = "019a3bd0-0000-7000-8000-000000000001",
                    ownerRuntimeId = sessions.ownerRuntimeId, connectionId = "connection-a",
                    source = TaskRuntimeEndpointRef(path = "/source"),
                    target = TaskRuntimeEndpointRef(path = "/target"),
                    sourceVersion = "fixed-source",
                )
                sessions.save(7, reference, StorageUploadSession(
                    "fixture", StoragePath("/target"), prepared.size, 0,
                    status = StorageUploadStatus.UNCERTAIN, secret = "secret", binding = "account",
                ))
                assertTrue(host.cleanup(7).isFailure)
                assertTrue(exists(prepared.path.toPath()))
                assertNotNull(sessions.load(7, reference))
            }
        } finally { fs.deleteRecursively(root) }
    }

    @Test
    fun recoveryIdentifiesDestroyedHeadersAcrossDevicesRenameAndSameNameReplacement() = runSuspendTest {
        val firstSettings = createInMemorySettings()
        val root = temporaryDirectory("content-cross-device")
        val target = StoragePath("/managed")
        val bytes = byteArrayOf(1, 2, 3, 4, 5)
        try {
            lateinit var backup: ByteArray
            lateinit var ciphertext: ByteArray
            lateinit var remote: NetworkFileEntry
            StorageContentHostContext(StorageContentKeyStore(firstSettings), firstSettings, root.resolve("first"), { provider }).use { first ->
                enable(first)
                val spool = prepare(first, bytes)
                ciphertext = read(spool.path.toPath())
                first.beginUpload(config, 7, "entry", target).getOrThrow()
                remote = provider.put(target.path, ciphertext)
                first.recordUploaded(config, 7, "entry", remote).getOrThrow()
                backup = first.exportRecovery(config, password).getOrThrow()
            }
            val refusedSettings = createInMemorySettings()
            StorageContentHostContext(StorageContentKeyStore(refusedSettings), refusedSettings, root.resolve("unrestored"), { provider }).use { unrestored ->
                assertTrue(unrestored.create(config).isFailure)
                assertTrue(unrestored.exportRecovery(config, password).isFailure)
                assertFalse(unrestored.status(config).getOrThrow().hasKey)
                val stage = unrestored.createStage(9, "unrecovered").getOrThrow()
                write(stage.toPath(), ciphertext.copyOf().also { it.fill(0x55, 0, 8) })
                assertTrue(unrestored.publishDownload(config, 9, source.copy(path = target.path, storageReference = remote.reference),
                    stage, root.resolve("must-not-publish").toString()).isFailure)
                assertFalse(exists(root.resolve("must-not-publish")))
                assertTrue(unrestored.beginUpload(config, 9, "ordinary", target).isFailure)
                unrestored.importRecovery(config, backup, password).getOrThrow()
            }
            val secondSettings = createInMemorySettings()
            val restoredKeys = StorageContentKeyStore(secondSettings)
            StorageContentHostContext(restoredKeys, secondSettings, root.resolve("second"), { provider }).use { second ->
                second.importRecovery(config, backup, password).getOrThrow()
                second.unlock(config).getOrThrow()
                val destination = root.resolve("existing")
                write(destination, byteArrayOf(99))
                val destroyed = ciphertext.copyOf().also { it.fill(0x55, 0, 8) }
                suspend fun publish(path: String, data: ByteArray, reference: StorageReference?): Result<Boolean> {
                    val stage = second.createStage(8, "download").getOrThrow()
                    write(stage.toPath(), data)
                    return second.publishDownload(config, 8, source.copy(path = path, storageReference = reference), stage, destination.toString())
                }
                assertTrue(publish(target.path, destroyed, remote.reference).isFailure)
                assertContentEquals(byteArrayOf(99), read(destination))
                second.beforeMutation(config, target).getOrThrow()
                provider.rename(target, "/renamed").getOrThrow()
                assertTrue(publish("/renamed", destroyed, remote.reference).isFailure)
                val ordinary = "FSPAN ordinary historical bytes".encodeToByteArray()
                val replacement = provider.put(target.path, ordinary)
                assertNotEquals(remote.reference!!.id, replacement.reference!!.id)
                assertTrue(publish(target.path, ordinary, replacement.reference).getOrThrow())
                assertContentEquals(ordinary, read(destination))
                second.setEnabled(config, false).getOrThrow()
                assertTrue(second.beginUpload(config, 8, "ordinary", StoragePath("/renamed")).isFailure)
                second.beginUpload(config, 8, "ordinary", target).getOrThrow()
                provider.removeManifest(restoredKeys.manifestAnchor(storageContentScope(config)))
                assertTrue(publish(target.path, ordinary, replacement.reference).isFailure)
                assertTrue(second.create(config).isFailure) // Never rebuild an empty catalog from an established anchor.
                assertContentEquals(ordinary, read(destination))
            }
        } finally { fs.deleteRecursively(root) }
    }

    @Test
    fun pendingUnboundIntentKeepsSpoolAndBlocksUnknownReadsUntilTheWriteIsVerified() = runSuspendTest {
        val settings = createInMemorySettings()
        val root = temporaryDirectory("content-pending-intent")
        try {
            StorageContentHostContext(StorageContentKeyStore(settings), settings, root, { provider }).use { host ->
                enable(host)
                val spool = prepare(host, byteArrayOf(1, 2, 3, 4, 5))
                val target = StoragePath("/interrupted")
                host.beginUpload(config, 7, "entry", target).getOrThrow()
                assertTrue(host.cleanup(7).isFailure)
                assertTrue(exists(spool.path.toPath()))
                assertTrue(host.beforeMutation(config, target).isFailure)
                val ordinary = provider.put("/ordinary", byteArrayOf(8))
                suspend fun publishOrdinary(): Result<Boolean> {
                    val stage = host.createStage(8, "plain").getOrThrow()
                    write(stage.toPath(), byteArrayOf(8))
                    return host.publishDownload(config, 8, source.copy(path = ordinary.path, storageReference = ordinary.reference),
                        stage, root.resolve("published").toString())
                }
                assertTrue(publishOrdinary().isFailure)
                val result = provider.put(target.path, read(spool.path.toPath()))
                host.recordUploaded(config, 7, "entry", result).getOrThrow()
                assertTrue(publishOrdinary().getOrThrow())
                host.cleanup(7).getOrThrow()
                assertFalse(exists(spool.path.toPath()))
            }
        } finally { fs.deleteRecursively(root) }
    }

    @Test
    fun failedFirstOverwritePreservesOriginalOrdinaryDigestAndCannotPublishAlteredBytes() = runSuspendTest {
        val settings = createInMemorySettings()
        val root = temporaryDirectory("content-original-digest")
        val original = byteArrayOf(8, 9)
        val target = provider.put("/existing", original)
        try {
            StorageContentHostContext(StorageContentKeyStore(settings), settings, root, { provider }).use { host ->
                enable(host)
                prepare(host, byteArrayOf(1, 2, 3, 4, 5))
                host.beginUpload(config, 7, "entry", StoragePath(target.path, target.reference)).getOrThrow()
                suspend fun publish(data: ByteArray): Result<Boolean> {
                    val stage = host.createStage(8, "plain").getOrThrow()
                    write(stage.toPath(), data)
                    return host.publishDownload(config, 8, source.copy(path = target.path, storageReference = target.reference),
                        stage, root.resolve("published").toString())
                }
                assertTrue(publish(original).getOrThrow())
                assertTrue(publish(byteArrayOf(8, 10)).isFailure)
                assertContentEquals(original, read(root.resolve("published")))
            }
        } finally { fs.deleteRecursively(root) }
    }

    @Test
    fun concurrentHostCreationKeepsOneRecoverableKeyAndCatalog() = runSuspendTest {
        val settings = createInMemorySettings()
        val root = temporaryDirectory("content-concurrent-create")
        val readsLock = TaskRuntimeStoreLock()
        var activeReads = 0
        var peakReads = 0
        fun <T> withReadsLock(block: () -> T): T {
            readsLock.lock()
            return try { block() } finally { readsLock.unlock() }
        }
        val delayedProvider = object : StorageProvider by provider {
            override suspend fun readContentManifest(anchor: String): Result<StorageContentManifestSnapshot?> {
                withReadsLock {
                    activeReads += 1
                    peakReads = maxOf(peakReads, activeReads)
                }
                return try { kotlinx.coroutines.delay(25); provider.readContentManifest(anchor) }
                finally { withReadsLock { activeReads -= 1 } }
            }
        }
        val firstKeys = StorageContentKeyStore(settings)
        val secondKeys = StorageContentKeyStore(settings)
        try {
            StorageContentHostContext(firstKeys, settings, root.resolve("first"), { delayedProvider }).use { first ->
                StorageContentHostContext(secondKeys, settings, root.resolve("second"), { delayedProvider }).use { second ->
                    coroutineScope { listOf(first, second).map { host -> async { host.create(config).getOrThrow() } }.awaitAll() }
                    assertEquals(1, withReadsLock { peakReads }, "One process must serialize initialization across separate content contexts")
                    val scope = storageContentScope(config)
                    assertEquals(firstKeys.manifestAnchor(scope), secondKeys.manifestAnchor(scope))
                    val firstBackup = first.exportRecovery(config, password).getOrThrow()
                    val secondBackup = second.exportRecovery(config, password).getOrThrow()
                    first.verifyBackupAndEnable(config, firstBackup, password).getOrThrow()
                    second.verifyBackupAndEnable(config, secondBackup, password).getOrThrow()
                    val left = firstKeys.keyForOperation(scope)
                    val right = secondKeys.keyForOperation(scope)
                    try { assertContentEquals(left, right) } finally { left.fill(0); right.fill(0) }
                }
            }
        } finally { fs.deleteRecursively(root) }
    }

    @Test
    fun anotherDeviceVerifiesInterruptedRenamedOutputOnlyAfterFullAuthentication() = runSuspendTest {
        val originalSettings = createInMemorySettings()
        val root = temporaryDirectory("content-pending-recovery")
        val target = StoragePath("/interrupted-recovery")
        lateinit var backup: ByteArray
        lateinit var ciphertext: ByteArray
        try {
            StorageContentHostContext(StorageContentKeyStore(originalSettings), originalSettings, root.resolve("first"), { provider }).use { first ->
                enable(first)
                val spool = prepare(first, byteArrayOf(1, 2, 3, 4, 5))
                ciphertext = read(spool.path.toPath())
                first.beginUpload(config, 7, "entry", target).getOrThrow()
                provider.put(target.path, ciphertext)
                backup = first.exportRecovery(config, password).getOrThrow()
            }
            val restoredSettings = createInMemorySettings()
            val restoredKeys = StorageContentKeyStore(restoredSettings)
            StorageContentHostContext(restoredKeys, restoredSettings, root.resolve("second"), { provider }).use { restored ->
                restored.importRecovery(config, backup, password).getOrThrow()
                restored.unlock(config).getOrThrow()
                val scope = storageContentScope(config)
                val key = restoredKeys.keyForManifest(scope)
                try {
                    val access = StorageContentManifestAccess(provider, scope, restoredKeys.manifestAnchor(scope), key)
                    val damaged = ciphertext.copyOf().also { it[it.lastIndex] = (it.last().toInt() xor 1).toByte() }
                    // Even an authenticated digest for these bytes cannot substitute for frame/footer verification.
                    val damagedDigest = StorageContentCrypto.sha256(damaged).toByteString().hex()
                    access.update { it.copy(records = it.records.map { record -> record.copy(ciphertextDigest = damagedDigest) }) }
                    var selected = provider.put(target.path, damaged)
                    val destination = root.resolve("existing")
                    write(destination, byteArrayOf(99))
                    suspend fun publish(bytes: ByteArray): Result<Boolean> {
                        val stage = restored.createStage(8, "verify").getOrThrow()
                        write(stage.toPath(), bytes)
                        return restored.publishDownload(config, 8, source.copy(path = selected.path, storageReference = selected.reference),
                            stage, destination.toString())
                    }
                    assertTrue(publish(damaged).isFailure)
                    assertFalse(access.read().records.single().committed)
                    assertContentEquals(byteArrayOf(99), read(destination))
                    assertFalse(fs.list(root).any { it.name.startsWith(".folderspan-verified-") })
                    val goodDigest = StorageContentCrypto.sha256(ciphertext).toByteString().hex()
                    access.update { it.copy(records = it.records.map { record -> record.copy(ciphertextDigest = goodDigest) }) }
                    provider.put(target.path, ciphertext)
                    provider.rename(target, "/renamed-recovery").getOrThrow()
                    selected = provider.stat(StoragePath("/renamed-recovery")).getOrThrow()
                    assertTrue(publish(ciphertext).getOrThrow())
                    val verified = access.read().records.single()
                    assertTrue(verified.committed)
                    assertEquals(selected.reference!!.id, verified.objectId)
                    assertEquals(selected.path, verified.path)
                    assertContentEquals(byteArrayOf(1, 2, 3, 4, 5), read(destination))
                } finally { key.fill(0) }
            }
        } finally { fs.deleteRecursively(root) }
    }

    @Test
    fun authenticatedCatalogCannotBeDeletedRenamedOrOverwrittenAsAnOrdinaryObject() = runSuspendTest {
        val settings = createInMemorySettings()
        val root = temporaryDirectory("content-reserved-catalog")
        val keys = StorageContentKeyStore(settings)
        try {
            StorageContentHostContext(keys, settings, root, { provider }).use { host ->
                enable(host)
                val reserved = StoragePath("/" + contentManifestFileName(keys.manifestAnchor(storageContentScope(config))))
                assertTrue(host.beforeMutation(config, reserved).isFailure)
                assertTrue(host.beforeMutation(config, StoragePath("/")).isFailure)
                assertTrue(host.beginUpload(config, 7, "ordinary", reserved).isFailure)
                host.setEnabled(config, false).getOrThrow()
                assertTrue(host.beforeMutation(config, reserved).isFailure)
                assertTrue(host.beginUpload(config, 7, "ordinary", reserved).isFailure)
                host.beforeMutation(config, StoragePath("/new-ordinary-target")).getOrThrow()
            }
        } finally { fs.deleteRecursively(root) }
    }

    @Test
    fun disablingAfterPreparationCannotAuthorizeAnUnregisteredEncryptedUpload() = runSuspendTest {
        val settings = createInMemorySettings()
        val root = temporaryDirectory("content-disabled-before-begin")
        val keys = StorageContentKeyStore(settings)
        try {
            StorageContentHostContext(keys, settings, root, { provider }).use { host ->
                enable(host)
                val prepared = prepare(host, byteArrayOf(1, 2, 3, 4, 5))
                host.setEnabled(config, false).getOrThrow()
                val target = StoragePath("/must-not-upload")
                assertTrue(host.beginUpload(config, 7, "entry", target).isFailure)
                assertTrue(provider.stat(target).isFailure)
                assertTrue(exists(prepared.path.toPath()))
                val scope = storageContentScope(config)
                val key = keys.keyForManifest(scope)
                try {
                    assertTrue(StorageContentManifestAccess(provider, scope, keys.manifestAnchor(scope), key).read().records.isEmpty())
                } finally { key.fill(0) }
                // An actual ordinary write with no prepared output still uses the disabled-location path.
                host.beginUpload(config, 8, "ordinary", StoragePath("/ordinary-target")).getOrThrow()
                host.cleanup(7).getOrThrow()
                assertFalse(exists(prepared.path.toPath()))
            }
        } finally { fs.deleteRecursively(root) }
    }

    @Test
    fun closeLocksKeysRemovesOnlyOwnedPlaintextAndRejectsNewStages() = runSuspendTest {
        val settings = createInMemorySettings()
        val root = temporaryDirectory("content-close")
        val keys = StorageContentKeyStore(settings)
        val host = StorageContentHostContext(keys, settings, root, { provider })
        try {
            enable(host)
            val encrypted = prepare(host, ByteArray(2 * StorageContentEncryptionV1.CHUNK_BYTES + 13) { (it % 251).toByte() })
            val owned = host.createStage(7, "owned-plain").getOrThrow().toPath()
            write(owned, byteArrayOf(1, 2, 3))
            val unrelated = root / "other-session"
            write(unrelated, byteArrayOf(9))
            host.close()
            assertFalse(exists(owned))
            assertTrue(exists(encrypted.path.toPath()), "Encrypted output remains available for interrupted-write recovery")
            assertContentEquals(byteArrayOf(9), read(unrelated))
            assertFalse(keys.status(storageContentScope(config)).second)
            assertTrue(host.createStage(7, "after-close").isFailure)
        } finally {
            host.close()
            fs.deleteRecursively(root)
        }
    }

    @Test
    fun replacedStageSymlinkCannotReadOrDeleteItsTarget() = runSuspendTest {
        val settings = createInMemorySettings()
        val root = temporaryDirectory("content-stage-symlink")
        try {
            StorageContentHostContext(StorageContentKeyStore(settings), settings, root, { provider }).use { host ->
                val stage = host.createStage(7, "stage").getOrThrow().toPath()
                val target = root / "private-original"
                write(target, byteArrayOf(4, 5, 6))
                fs.delete(stage)
                fs.createSymlink(stage, target)
                assertNull(StorageContentFileProtection.fileIdentity(stage))
                assertTrue(host.publishDownload(config, 7, source, stage.toString(), (root / "destination").toString()).isFailure)
                host.close()
                assertContentEquals(byteArrayOf(4, 5, 6), read(target))
                assertFalse(exists(root / "destination"))
            }
        } finally { fs.deleteRecursively(root) }
    }

    @Test
    fun changedSourceIdentityCannotReuseAnExistingCipherSpool() = runSuspendTest {
        val settings = createInMemorySettings()
        val root = temporaryDirectory("content-source-identity")
        try {
            StorageContentHostContext(StorageContentKeyStore(settings), settings, root, { provider }).use { host ->
                enable(host)
                val bytes = byteArrayOf(1, 2, 3, 4, 5)
                val original = prepare(host, bytes)
                val stage = host.createStage(7, "entry").getOrThrow()
                write(stage.toPath(), bytes)
                val changed = assertNotNull(host.prepareUpload(config, 7, "entry", source.copy(path = "/source/other"), stage).getOrThrow())
                assertNotEquals(original.path, changed.path)
                assertFalse(read(original.path.toPath()).contentEquals(read(changed.path.toPath())))
            }
        } finally { fs.deleteRecursively(root) }
    }

    private suspend fun enable(host: StorageContentHostContext) {
        host.create(config).getOrThrow()
        val backup = host.exportRecovery(config, password).getOrThrow()
        host.verifyBackupAndEnable(config, backup, password).getOrThrow()
    }

    private suspend fun prepare(host: StorageContentHostContext, bytes: ByteArray): StoragePreparedContentUpload {
        val stage = host.createStage(7, "entry").getOrThrow()
        write(stage.toPath(), bytes)
        return assertNotNull(host.prepareUpload(config, 7, "entry", source.copy(size = bytes.size.toLong()), stage).getOrThrow())
    }
}
