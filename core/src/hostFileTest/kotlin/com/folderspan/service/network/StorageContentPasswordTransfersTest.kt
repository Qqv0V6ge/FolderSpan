package com.folderspan.service.network

import com.folderspan.data.file.FileSimpleInfo
import com.folderspan.data.main.network.NetworkDriveExtras
import com.folderspan.test.createInMemorySettings
import com.folderspan.test.runSuspendTest
import kotlinx.coroutines.CancellationException
import okio.FileSystem
import okio.Path.Companion.toPath
import kotlin.test.*
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

@OptIn(ExperimentalUuidApi::class)
class StorageContentPasswordTransfersTest {
    private val password = "portable-content-password".toCharArray()
    private val fs = FileSystem.SYSTEM
    private class Fixture {
        val store = TestStorageContentVaultStore()
        val settings = createInMemorySettings()
        val cache = FileSystem.SYSTEM_TEMPORARY_DIRECTORY / ("password-transfer-test-" + Uuid.random())
        var loseCommitResponse = false
        var beforeRead: suspend (String) -> Unit = {}
        fun host() = StorageContentHostContext(StorageContentKeyStore(settings), settings, cache) {
            store.provider(beforeRead = { beforeRead(it) }, afterWrite = { name ->
                if (name.endsWith(".commit") && loseCommitResponse) {
                    loseCommitResponse = false
                    throw CancellationException("injected lost publication response")
                }
            })
        }
    }
    private suspend fun withFixture(test: suspend (Fixture, StorageContentHostContext, NetworkConnectionConfig) -> Unit) {
        val f = Fixture()
        val host = f.host()
        try {
            val raw = NetworkConnectionConfig("FTP", "fixture.invalid", "alice", "protocol-password",
                extras = NetworkDriveExtras())
            val ref = host.setupPassword(raw, password).getOrThrow()
            test(f, host, raw.copy(extras = raw.extras.copy(contentEncryption = ref)))
        } finally {
            host.close()
            fs.deleteRecursively(f.cache, mustExist = false)
        }
    }
    private fun source(bytes: ByteArray) = FileSimpleInfo.nullFileSimpleInfo().copy(
        path = "/source.txt", name = "source.txt", size = bytes.size.toLong(), updatedDate = 20)

    private suspend fun prepare(host: StorageContentHostContext, config: NetworkConnectionConfig, bytes: ByteArray,
        task: Long = 41): StoragePreparedContentUpload {
        val stage = host.createStage(task, "entry").getOrThrow()
        fs.write(stage.toPath()) { write(bytes) }
        return checkNotNull(host.prepareUpload(config, task, "entry", source(bytes), stage).getOrThrow())
    }
    private fun uploaded(prepared: StoragePreparedContentUpload) = NetworkFileEntry(
        checkNotNull(prepared.target).path.substringAfterLast('/'), prepared.target.path, false, prepared.size)

    @Test
    fun damagedDescriptorOrRootRejectsPreparationWithoutCreatingASpoolOrWritingRemotely() = runSuspendTest {
        for (suffix in listOf(".location", ".root")) withFixture { f, host, config ->
            val path = f.store.files.keys.single { it.endsWith(suffix) }
            f.store.files[path] = f.store.files.getValue(path).copyOf().also {
                it[it.lastIndex] = (it.last().toInt() xor 1).toByte()
            }
            val remote = f.store.snapshot()
            val writes = f.store.writes
            val spools = f.settings.keys.filter { it.startsWith("auth.storage.password-location.spool.") }
            val stage = host.createStage(41, "entry").getOrThrow()
            val bytes = "keep private source".encodeToByteArray()
            fs.write(stage.toPath()) { write(bytes) }
            assertTrue(host.prepareUpload(config, 41, "entry", source(bytes), stage).isFailure, suffix)
            assertEquals(remote, f.store.snapshot(), suffix)
            assertEquals(writes, f.store.writes, suffix)
            assertEquals(spools, f.settings.keys.filter { it.startsWith("auth.storage.password-location.spool.") }, suffix)
            assertFalse(fs.exists(stage.toPath()), suffix)
            assertTrue(fs.listRecursively(f.cache).none { it.name.startsWith(".cipher-") }, suffix)
        }
    }

    @Test
    fun freshSpoolAuthenticatesOnlyDescriptorAndRootBeforeBeginChecksFullHistory() = runSuspendTest {
        withFixture { f, host, config ->
            val existing = prepare(host, config, "existing managed file".encodeToByteArray())
            host.beginUpload(config, 41, "entry", StoragePath("/existing.txt")).getOrThrow()
            f.store.provider().upload(existing.path, checkNotNull(existing.target), existing.size).getOrThrow()
            host.recordUploaded(config, 41, "entry", uploaded(existing)).getOrThrow()
            host.cleanup(41).getOrThrow()
            assertEquals(1, f.store.files.keys.count { it.endsWith(".object") })
            val remote = f.store.snapshot()
            val writes = f.store.writes
            val reads = mutableListOf<String>()
            f.beforeRead = { reads += it }
            prepare(host, config, "new private spool".encodeToByteArray(), task = 42)
            f.beforeRead = {}
            assertEquals(1, reads.count { it.endsWith(".location") })
            assertEquals(1, reads.count { it.endsWith(".root") })
            assertEquals(2, reads.size)
            assertEquals(remote, f.store.snapshot())
            assertEquals(writes, f.store.writes)
            host.cleanup(42).getOrThrow()
        }
    }

    @Test
    fun sameLengthTargetDamageAfterPreparationRejectsOverwriteBeforeAnyRemoteWrite() = runSuspendTest {
        withFixture { f, host, config ->
            val existing = prepare(host, config, "existing managed file".encodeToByteArray())
            host.beginUpload(config, 41, "entry", StoragePath("/existing.txt")).getOrThrow()
            f.store.provider().upload(existing.path, checkNotNull(existing.target), existing.size).getOrThrow()
            host.recordUploaded(config, 41, "entry", uploaded(existing)).getOrThrow()
            host.cleanup(41).getOrThrow()
            val prepared = prepare(host, config, "new private spool".encodeToByteArray(), task = 42)
            val objectPath = checkNotNull(existing.target).path
            f.store.files[objectPath] = f.store.files.getValue(objectPath).copyOf().also {
                it[it.lastIndex] = (it.last().toInt() xor 1).toByte()
            }
            val remote = f.store.snapshot()
            val writes = f.store.writes
            assertTrue(host.beginUpload(config, 42, "entry", StoragePath("/existing.txt")).isFailure)
            assertEquals(remote, f.store.snapshot())
            assertEquals(writes, f.store.writes)
            assertFalse(checkNotNull(prepared.target).path in f.store.files)
            assertTrue(fs.exists(prepared.path.toPath()))
            host.cleanup(42).getOrThrow()
        }
    }

    @Test
    fun completedUploadRetryVerifiesItsObjectBeforeReportingSuccess() = runSuspendTest {
        withFixture { f, host, config ->
            val plain = "completed upload bytes".encodeToByteArray()
            val prepared = prepare(host, config, plain)
            host.beginUpload(config, 41, "entry", StoragePath("/existing.txt")).getOrThrow()
            f.store.provider().upload(prepared.path, checkNotNull(prepared.target), prepared.size).getOrThrow()
            host.recordUploaded(config, 41, "entry", uploaded(prepared)).getOrThrow()
            val path = checkNotNull(prepared.target).path
            f.store.files[path] = f.store.files.getValue(path).copyOf().also {
                it[it.lastIndex] = (it.last().toInt() xor 1).toByte()
            }
            val remote = f.store.snapshot()
            val writes = f.store.writes
            assertFails { prepare(host, config, plain) }
            assertTrue(host.beginUpload(config, 41, "entry", StoragePath("/existing.txt")).isFailure)
            assertTrue(host.recordUploaded(config, 41, "entry", uploaded(prepared)).isFailure)
            assertEquals(remote, f.store.snapshot())
            assertEquals(writes, f.store.writes)
        }
    }

    @Test
    fun observedHistoryRollbackAfterPreparationRejectsBeginWithoutAdvancingCheckpoint() = runSuspendTest {
        withFixture { f, host, config ->
            val existing = prepare(host, config, "existing managed file".encodeToByteArray())
            host.beginUpload(config, 41, "entry", StoragePath("/existing.txt")).getOrThrow()
            f.store.provider().upload(existing.path, checkNotNull(existing.target), existing.size).getOrThrow()
            host.recordUploaded(config, 41, "entry", uploaded(existing)).getOrThrow()
            host.cleanup(41).getOrThrow()
            val checkpointName = f.settings.keys.single { it.startsWith("auth.storage.password-location.checkpoint.") }
            val checkpoint = f.settings.getString(checkpointName, "")
            val prepared = prepare(host, config, "new private spool".encodeToByteArray(), task = 42)
            f.store.files.remove(f.store.files.keys.single { it.endsWith(".commit") })
            val remote = f.store.snapshot()
            val writes = f.store.writes
            assertTrue(host.beginUpload(config, 42, "entry", StoragePath("/new.txt")).isFailure)
            assertEquals(remote, f.store.snapshot())
            assertEquals(writes, f.store.writes)
            assertEquals(checkpoint, f.settings.getString(checkpointName, ""))
            assertFalse(checkNotNull(prepared.target).path in f.store.files)
            host.cleanup(42).getOrThrow()
        }
    }

    @Test
    fun pathProtocolWithoutCloudIdentityRoundTripsAcrossIndependentOwnersAndPreservesOrdinaryMagic() = runSuspendTest {
        withFixture { f, host, config ->
            val original = "hello 密码位置".encodeToByteArray()
            f.store.files["/ordinary.txt"] = "FSPANENC this is an ordinary text file".encodeToByteArray()
            val prepared = prepare(host, config, original)
            host.beginUpload(config, 41, "entry", StoragePath("/secret.txt")).getOrThrow()
            f.store.provider().upload(prepared.path, checkNotNull(prepared.target), prepared.size).getOrThrow()
            host.recordUploaded(config, 41, "entry", uploaded(prepared)).getOrThrow()
            assertFalse(f.store.files.containsKey("/secret.txt"))
            val other = config.copy(username = "another-local-connection", password = "another-login")
            f.host().use { reader ->
                assertFalse(reader.status(other).getOrThrow().unlocked)
                assertTrue(reader.logicalList(other, StoragePath("/")).isFailure)
                reader.unlockPassword(other, password).getOrThrow()
                val entries = checkNotNull(reader.logicalList(other, StoragePath("/")).getOrThrow())
                assertEquals(setOf("secret.txt", "ordinary.txt"), entries.map { it.name }.toSet())
                for (entry in entries) {
                    val logical = FileSimpleInfo.nullFileSimpleInfo().copy(
                        name = entry.name, path = entry.path, size = entry.size, storageReference = entry.reference)
                    val download = checkNotNull(reader.resolveDownload(other, logical).getOrThrow())
                    val stage = reader.createStage(82, "download").getOrThrow()
                    fs.write(stage.toPath()) { write(f.store.files.getValue(download.target.path)) }
                    val target = f.cache / ("download-" + entry.name)
                    fs.write(target) { writeUtf8("existing destination") }
                    reader.publishDownload(other, 82, download.file, stage, target.toString()).getOrThrow()
                    assertContentEquals(if (entry.name == "secret.txt") original else f.store.files.getValue("/ordinary.txt"),
                        fs.read(target) { readByteArray() })
                }
                reader.lock(other).getOrThrow()
                assertTrue(reader.beginUpload(other, 99, "locked", StoragePath("/blocked.txt")).isFailure)
            }
            host.cleanup(41).getOrThrow()
        }
    }

    @Test
    fun restartReusesUploadedObjectAndFrozenPolicyAfterEncryptionIsDisabled() = runSuspendTest {
        withFixture { f, host, config ->
            val bytes = "resume existing ciphertext".encodeToByteArray()
            val prepared = prepare(host, config, bytes)
            host.beginUpload(config, 41, "entry", StoragePath("/resume.txt")).getOrThrow()
            f.store.provider().upload(prepared.path, checkNotNull(prepared.target), prepared.size).getOrThrow()
            val before = f.store.snapshot()
            fs.delete(prepared.path.toPath()) // The verified remote object is sufficient after cache eviction.
            host.close()
            f.host().use { restarted ->
                val disabled = config.copy(extras = config.extras.copy(
                    contentEncryption = checkNotNull(config.extras.contentEncryption).copy(enabled = false)))
                assertTrue(restarted.uploadEncryptionEnabled(disabled, 41, "entry").getOrThrow())
                assertTrue(restarted.prepareUpload(disabled, 41, "entry", source(bytes),
                    restarted.createStage(999, "wrong-task").getOrThrow()).isFailure)
                restarted.unlockPassword(disabled, password).getOrThrow()
                val recovered = prepare(restarted, disabled, bytes)
                assertEquals(prepared.target, recovered.target)
                assertTrue(recovered.uploaded)
                assertFalse(recovered.committed)
                assertEquals(before, f.store.snapshot())
                restarted.beginUpload(disabled, 41, "entry", StoragePath("/resume.txt")).getOrThrow()
                restarted.recordUploaded(disabled, 41, "entry", uploaded(recovered)).getOrThrow()
                assertEquals(1, f.store.files.keys.count { it.endsWith(".commit") })
                assertContentEquals(f.store.files.getValue(checkNotNull(prepared.target).path),
                    f.store.files.getValue(checkNotNull(recovered.target).path))
                restarted.cleanup(41).getOrThrow()
            }
        }
    }

    @Test
    fun lostCommitResponseIsReconciledWithoutRewritingItsObjectAndChangedSourcesRemainPreserved() = runSuspendTest {
        withFixture { f, host, config ->
            val bytes = "do not upload twice".encodeToByteArray()
            val prepared = prepare(host, config, bytes)
            host.beginUpload(config, 41, "entry", StoragePath("/confirmed.txt")).getOrThrow()
            f.store.provider().upload(prepared.path, checkNotNull(prepared.target), prepared.size).getOrThrow()
            f.loseCommitResponse = true
            assertFailsWith<CancellationException> {
                host.recordUploaded(config, 41, "entry", uploaded(prepared)).getOrThrow()
            }
            assertTrue(host.cleanup(41).isFailure)
            val snapshot = f.store.snapshot()
            val writes = f.store.writes
            val recovered = prepare(host, config, bytes)
            assertTrue(recovered.uploaded)
            assertTrue(recovered.committed)
            host.beginUpload(config, 41, "entry", StoragePath("/confirmed.txt")).getOrThrow()
            assertEquals(writes, f.store.writes)
            assertEquals(snapshot, f.store.snapshot())
            val changed = "keep new local data".encodeToByteArray()
            val stage = host.createStage(41, "entry").getOrThrow()
            fs.write(stage.toPath()) { write(changed) }
            val failure = host.prepareUpload(config, 41, "entry", source(changed), stage).exceptionOrNull()
            assertEquals(StorageContentErrorCode.SourceChanged, (failure as StorageContentException).code)
            assertEquals(snapshot, f.store.snapshot())
            host.cleanup(41).getOrThrow()
        }
    }

    @Test
    fun reauthorizingTheSameAccountRetiresUnlockButKeepsItsPendingOperationAndEncryptionPolicy() = runSuspendTest {
        withFixture { f, host, config ->
            val id = Uuid.random().toString()
            val credential = StorageCredential(id, StorageAccountKind.Nextcloud, "fixture-provider",
                "Disposable pending authorization", server = "https://fixture.invalid", accessToken = "fixture-token")
            val accountConfig = config.copy(protocol = "Nextcloud", host = credential.server, username = "",
                extras = config.extras.copy(cloud = com.folderspan.data.main.network.CloudDriveExtras(
                    accountId = id, connectionId = Uuid.random().toString())))
            try {
                StorageAccounts.install(credential)
                host.unlockPassword(accountConfig, password).getOrThrow()
                val bytes = "preserve this pending operation".encodeToByteArray()
                val original = prepare(host, accountConfig, bytes)
                host.beginUpload(accountConfig, 41, "entry", StoragePath("/pending.txt")).getOrThrow()
                f.store.provider().upload(original.path, checkNotNull(original.target), original.size).getOrThrow()
                val disabled = accountConfig.copy(extras = accountConfig.extras.copy(
                    contentEncryption = checkNotNull(accountConfig.extras.contentEncryption).copy(enabled = false)))
                StorageAccounts.install(credential, id)
                assertFalse(host.status(disabled).getOrThrow().unlocked)
                assertTrue(host.uploadEncryptionEnabled(disabled, 41, "entry").getOrThrow())
                assertFails { prepare(host, disabled, bytes) }
                host.unlockPassword(disabled, password).getOrThrow()
                val recovered = prepare(host, disabled, bytes)
                assertEquals(original.target, recovered.target)
                assertTrue(recovered.uploaded)
                host.beginUpload(disabled, 41, "entry", StoragePath("/pending.txt")).getOrThrow()
                host.recordUploaded(disabled, 41, "entry", uploaded(recovered)).getOrThrow()
                assertEquals(1, f.store.files.keys.count { it.endsWith(".commit") })
                host.cleanup(41).getOrThrow()
            } finally {
                runCatching { StorageAccounts.store.remove(id) }
                com.folderspan.createSettings().remove("auth.storage.account-generation." + id)
            }
        }
    }

    @Test
    fun providerStageReplacementRequiresItsOriginalTaskAndIdentityBeforePublishing() = runSuspendTest {
        withFixture { f, host, _ ->
            val stage = host.createStage(74, "download").getOrThrow().toPath()
            val original = host.stageIdentity(74, stage.toString()).getOrThrow()
            val replacement = StorageContentFileProtection.createPrivateTempFile(checkNotNull(stage.parent), ".replacement-")
            fs.write(replacement) { writeUtf8("received through the admitted provider") }
            StorageContentFileProtection.atomicMove(replacement, stage)
            assertTrue(host.stageIdentity(74, stage.toString()).isFailure)
            assertTrue(host.completeStageWrite(75, stage.toString(), original).isFailure)
            assertTrue(host.completeStageWrite(74, stage.toString(), "another-file").isFailure)
            f.host().use { other ->
                assertTrue(other.completeStageWrite(74, stage.toString(), original).isFailure)
            }
            host.completeStageWrite(74, stage.toString(), original).getOrThrow()
            val current = host.stageIdentity(74, stage.toString()).getOrThrow()
            assertNotEquals(original, current)
            assertEquals(StorageContentFileProtection.fileIdentity(stage), current)
            assertEquals("received through the admitted provider", fs.read(stage) { readUtf8() })
            host.cleanup(74).getOrThrow()
            assertFalse(fs.exists(stage))
        }
    }

    @Test
    fun admittedStageWriteCannotAdoptASymlinkOrReviveAClosedContext() = runSuspendTest {
        withFixture { f, host, _ ->
            val stage = host.createStage(75, "download").getOrThrow().toPath()
            val original = host.stageIdentity(75, stage.toString()).getOrThrow()
            val outside = f.cache / "keep-unrelated"
            fs.write(outside) { writeUtf8("do not publish this file") }
            fs.delete(stage)
            fs.createSymlink(stage, outside)
            assertTrue(host.completeStageWrite(75, stage.toString(), original).isFailure)
            assertEquals("do not publish this file", fs.read(outside) { readUtf8() })
            host.close()
            assertTrue(host.completeStageWrite(75, stage.toString(), original).isFailure)
            assertTrue(host.stageIdentity(75, stage.toString()).isFailure)
            assertEquals("do not publish this file", fs.read(outside) { readUtf8() })
        }
    }

    @Test
    fun onlyPreparedCiphertextGetsTaskReadIdentityAndClosePreservesItsRecoveryBytes() = runSuspendTest {
        withFixture { f, host, config ->
            val bytes = "identity checked private ciphertext".encodeToByteArray()
            val prepared = prepare(host, config, bytes)
            val cipher = prepared.path.toPath()
            val identity = host.stageIdentity(41, prepared.path).getOrThrow()
            assertEquals(StorageContentFileProtection.fileIdentity(cipher), identity)
            assertTrue(host.stageIdentity(42, prepared.path).isFailure)
            val unrelated = StorageContentFileProtection.createPrivateTempFile(checkNotNull(cipher.parent), ".cipher-")
            fs.write(unrelated) { writeUtf8("not prepared by this task") }
            assertTrue(host.stageIdentity(41, unrelated.toString()).isFailure)
            host.close()
            assertTrue(fs.exists(cipher))
            assertTrue(host.stageIdentity(41, prepared.path).isFailure)
            f.host().use { restarted ->
                restarted.unlockPassword(config, password).getOrThrow()
                val resumed = prepare(restarted, config, bytes)
                assertEquals(prepared.path, resumed.path)
                assertEquals(identity, restarted.stageIdentity(41, resumed.path).getOrThrow())
                restarted.cleanup(41).getOrThrow()
                assertFalse(fs.exists(cipher))
            }
        }
    }

    @Test
    fun damagedManagedHeaderNeverFallsBackToPlaintextOrReplacesExistingDestination() = runSuspendTest {
        withFixture { f, host, config ->
            val prepared = prepare(host, config, "authenticated payload".encodeToByteArray())
            host.beginUpload(config, 41, "entry", StoragePath("/managed.txt")).getOrThrow()
            f.store.provider().upload(prepared.path, checkNotNull(prepared.target), prepared.size).getOrThrow()
            host.recordUploaded(config, 41, "entry", uploaded(prepared)).getOrThrow()
            val entry = checkNotNull(host.logicalList(config, StoragePath("/")).getOrThrow()).single()
            val file = FileSimpleInfo.nullFileSimpleInfo().copy(name = entry.name, path = entry.path,
                size = entry.size, storageReference = entry.reference)
            val download = checkNotNull(host.resolveDownload(config, file).getOrThrow())
            val damaged = f.store.files.getValue(download.target.path).copyOf().also { it.fill(0, 0, 124) }
            f.store.files[download.target.path] = damaged
            val stage = host.createStage(92, "damaged").getOrThrow()
            fs.write(stage.toPath()) { write(damaged) }
            val destination = f.cache / "keep-existing"
            fs.write(destination) { writeUtf8("existing destination") }
            assertTrue(host.publishDownload(config, 92, download.file, stage, destination.toString()).isFailure)
            assertEquals("existing destination", fs.read(destination) { readUtf8() })
            assertFalse(fs.exists(stage.toPath()))
            val stillManaged = checkNotNull(host.resolveDownload(config, file).getOrThrow())
            assertEquals(download.file.storageReference?.content, stillManaged.file.storageReference?.content)
            assertEquals(download.target, stillManaged.target)
            host.cleanup(41).getOrThrow()
        }
    }
}
