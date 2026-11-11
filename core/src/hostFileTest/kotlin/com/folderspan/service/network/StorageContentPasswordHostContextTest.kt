package com.folderspan.service.network

import com.folderspan.data.main.network.CloudDriveExtras
import com.folderspan.data.main.network.NetworkDriveExtras
import com.folderspan.data.main.network.StorageContentLocationReference
import com.folderspan.utils.LogCapture
import io.github.aakira.napier.LogLevel
import kotlin.test.assertFails
import com.folderspan.test.createInMemorySettings
import com.folderspan.test.runSuspendTest
import com.russhwolf.settings.Settings
import okio.FileSystem
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

@OptIn(ExperimentalUuidApi::class)
class StorageContentPasswordHostContextTest {
    private val password = "portable-content-password".toCharArray()
    private val first = NetworkConnectionConfig("FTP", "fixture.invalid", "alice", "protocol-login-a",
        extras = NetworkDriveExtras(cloud = CloudDriveExtras(accountId = "local-account-a", connectionId = "local-connection-a")))
    private val second = first.copy(username = "bob", password = "protocol-login-b",
        extras = NetworkDriveExtras(cloud = CloudDriveExtras(accountId = "local-account-b", connectionId = "local-connection-b")))

    private fun host(store: TestStorageContentVaultStore, settings: Settings = createInMemorySettings()) =
        StorageContentHostContext(StorageContentKeyStore(settings), settings,
            FileSystem.SYSTEM_TEMPORARY_DIRECTORY / ("password-host-test-" + Uuid.random()), { store.provider() })

    @Test
    fun offlineIntentBlocksPlaintextUploadsUntilTheLocationIsVerified() = runSuspendTest {
        val store = TestStorageContentVaultStore()
        val pending = first.copy(extras = first.extras.copy(
            contentEncryption = StorageContentLocationReference(enabled = true)))
        host(store).use { source ->
            val statusFailure = source.status(pending).exceptionOrNull()
            assertTrue(statusFailure is StorageContentException)
            assertEquals(StorageContentErrorCode.Locked, statusFailure.code)
            var read = false
            val upload = uploadManagedStorageContent(store.provider(), pending, StoragePath("/file.txt"), 5,
                onProgress = { _, _ -> }, readChunk = { read = true; byteArrayOf(1, 2, 3, 4, 5) },
                content = source, settings = createInMemorySettings())
            assertTrue(upload?.isFailure == true)
            assertFalse(read)
            assertEquals(0, store.writes)
            assertTrue(source.logicalList(pending, StoragePath("/")).isFailure)
            val inspected = source.inspectPasswordLocation(pending.copy(extras = pending.extras.copy(
                contentEncryption = pending.extras.contentEncryption?.copy(root = StoragePath("/unverified"))))).getOrThrow()
            assertEquals(StoragePath("/"), inspected.root)
            assertEquals(null, inspected.reference)
            assertEquals(0, store.writes)
            val reference = source.setupPassword(pending, password).getOrThrow()
            assertTrue(reference.vaultId.isNotEmpty())
            assertTrue(source.status(pending.copy(extras = pending.extras.copy(contentEncryption = reference))).getOrThrow().unlocked)
        }
    }

    @Test
    fun inspectBeforeFirstSaveNeverInitializesOrUnlocksAndCorruptKnownMaterialIsNotNew() = runSuspendTest {
        val store = TestStorageContentVaultStore()
        val config = first.copy(extras = NetworkDriveExtras())
        host(store).use { source ->
            val blank = source.inspectPasswordLocation(config).getOrThrow()
            assertTrue(blank.available)
            assertTrue(blank.writable)
            assertEquals(null, blank.reference)
            assertEquals(0, store.writes)
            val reference = source.setupPassword(config, password).getOrThrow()
            val configured = config.copy(extras = config.extras.copy(contentEncryption = reference))
            host(store).use { observer ->
                val writes = store.writes
                val found = observer.inspectPasswordLocation(config).getOrThrow()
                assertEquals(reference.vaultId, found.reference?.vaultId)
                assertFalse(observer.status(configured).getOrThrow().unlocked)
                assertEquals(writes, store.writes)
                val name = "/.folderspan-vault/" + reference.vaultId + ".root"
                store.files.remove(name)
                val failure = observer.inspectPasswordLocation(configured).exceptionOrNull()
                assertTrue(failure is StorageContentException)
                assertEquals(StorageContentErrorCode.DamagedLocation, failure.code)
                assertEquals(writes, store.writes)
            }
        }
    }

    @Test
    fun reservedRootAndAnOpaqueRootExposingVaultMaterialNeverCreateNestedLocations() = runSuspendTest {
        val store = TestStorageContentVaultStore()
        host(store).use { ordinary ->
            val ref = ordinary.setupPassword(first, password).getOrThrow()
            val snapshot = store.snapshot()
            val writes = store.writes
            val nested = first.copy(extras = first.extras.copy(contentEncryption = ref.copy(
                root = StoragePath("/.folderspan-vault"))))
            assertTrue(ordinary.setupPassword(nested, password).isFailure)
            assertTrue(ordinary.inspectPasswordLocation(nested).isFailure)
            assertEquals(snapshot, store.snapshot())
            assertEquals(writes, store.writes)
            val raw = store.provider()
            val alias = object : StorageProvider by raw, ChunkReadableNetworkClient {
                override suspend fun list(path: StoragePath, requestId: String?, batchId: String?) =
                    raw.list(StoragePath("/.folderspan-vault"), requestId, batchId)
                override suspend fun downloadByChunks(remotePath: String, size: Long,
                    onChunk: suspend (ByteArray, Long) -> Result<Unit>): Result<Boolean> =
                    error("An internal root must be rejected before any nested data read")
            }
            val settings = createInMemorySettings()
            StorageContentHostContext(StorageContentKeyStore(settings), settings,
                FileSystem.SYSTEM_TEMPORARY_DIRECTORY / ("password-root-test-" + Uuid.random()), { alias }).use {
                assertTrue(it.setupPassword(first, password).isFailure)
                assertTrue(it.inspectPasswordLocation(first).isFailure)
            }
            assertEquals(snapshot, store.snapshot())
            assertEquals(writes, store.writes)
        }
    }

    @Test
    fun knownUnlockedLocationCanValidateEnableWithoutPasswordAndClosingIntentKeepsKeys() = runSuspendTest {
        val store = TestStorageContentVaultStore()
        host(store).use { source ->
            val reference = source.setupPassword(first, password).getOrThrow()
            val config = first.copy(extras = first.extras.copy(contentEncryption = reference))
            val writes = store.writes
            source.setEnabled(config, true).getOrThrow()
            source.setEnabled(config, false).getOrThrow()
            assertTrue(source.status(config).getOrThrow().unlocked)
            assertTrue(source.status(config).getOrThrow().hasKey)
            val name = "/.folderspan-vault/" + reference.vaultId + ".root"
            store.files[name] = store.files.getValue(name).copyOf().also { it[it.lastIndex] = (it.last().toInt() xor 1).toByte() }
            val failure = source.setEnabled(config, true).exceptionOrNull()
            assertTrue(failure is StorageContentException)
            assertEquals(StorageContentErrorCode.DamagedLocation, failure.code)
            assertEquals(writes, store.writes)
        }
    }

    @Test
    fun differentLocalAccountsAndConnectionIdsRecoverWithPasswordButNeverInheritUnlock() = runSuspendTest {
        val store = TestStorageContentVaultStore()
        host(store).use { a ->
            val reference = a.setupPassword(first, password).getOrThrow()
            val aConfig = first.copy(extras = first.extras.copy(contentEncryption = reference))
            assertTrue(a.status(aConfig).getOrThrow().unlocked)
            val bConfig = second.copy(extras = second.extras.copy(contentEncryption = reference))
            assertFalse(a.status(bConfig).getOrThrow().unlocked)
            host(store).use { b ->
                assertEquals(storageContentScope(aConfig), storageContentScope(bConfig))
                assertFalse(b.status(bConfig).getOrThrow().unlocked)
                assertTrue(b.unlock(bConfig).isFailure)
                assertTrue(b.unlockPassword(bConfig, "wrong".toCharArray()).isFailure)
                assertFalse(b.status(bConfig).getOrThrow().unlocked)
                b.unlockPassword(bConfig, password).getOrThrow()
                assertTrue(b.status(bConfig).getOrThrow().unlocked)
                assertFalse(b.status(aConfig).getOrThrow().unlocked)
                b.lock(bConfig).getOrThrow()
                assertFalse(b.status(bConfig).getOrThrow().unlocked)
                assertTrue(b.status(bConfig).getOrThrow().hasKey)
            }
        }
    }

    @Test
    fun sessionOrLocationChangesAndRestartRequireIndependentUnlockWhileStoredKeysSurvive() = runSuspendTest {
        val store = TestStorageContentVaultStore()
        val settings = createInMemorySettings()
        val a = host(store, settings)
        val ordinary = first.copy(extras = NetworkDriveExtras())
        val reference = a.setupPassword(ordinary, password).getOrThrow()
        val configured = ordinary.copy(extras = ordinary.extras.copy(contentEncryption = reference))
        assertTrue(a.status(configured).getOrThrow().unlocked)
        assertFalse(a.status(configured.copy(sessionOwner = "another-session")).getOrThrow().unlocked)
        val anotherRoot = configured.copy(extras = configured.extras.copy(contentEncryption = reference.copy(root = StoragePath("/other"))))
        assertFalse(a.status(anotherRoot).getOrThrow().unlocked)
        assertTrue(a.unlockPassword(anotherRoot, password).isFailure)
        val anotherVault = configured.copy(extras = configured.extras.copy(contentEncryption = reference.copy(vaultId = Uuid.random().toString())))
        assertFalse(a.status(anotherVault).getOrThrow().unlocked)
        assertTrue(a.unlockPassword(anotherVault, password).isFailure)
        a.close()
        assertFalse(a.status(configured).getOrThrow().unlocked)
        assertTrue(a.unlockPassword(configured, password).isFailure)
        host(store, settings).use { restarted ->
            assertTrue(restarted.status(configured).getOrThrow().hasKey)
            assertFalse(restarted.status(configured).getOrThrow().unlocked)
            assertTrue(restarted.unlock(configured).isFailure)
            restarted.unlockPassword(configured, password).getOrThrow()
            assertTrue(restarted.status(configured).getOrThrow().unlocked)
        }
    }

    @Test
    fun reauthorizingOrRemovingTheSameLocalAccountCannotInheritContentUnlock() = runSuspendTest {
        val accountId = Uuid.random().toString()
        val credential = StorageCredential(accountId, StorageAccountKind.Nextcloud, "fixture-provider-user",
            "Disposable content authorization fixture", server = "https://fixture.invalid", username = "fixture",
            accessToken = "disposable-fixture-token")
        val config = NetworkConnectionConfig("Nextcloud", credential.server, "", "",
            extras = NetworkDriveExtras(cloud = CloudDriveExtras(accountId = accountId, connectionId = Uuid.random().toString())))
        val store = TestStorageContentVaultStore()
        try {
            StorageAccounts.install(credential)
            val settings = createInMemorySettings()
            val keys = StorageContentKeyStore(settings)
            StorageContentHostContext(keys, settings,
                FileSystem.SYSTEM_TEMPORARY_DIRECTORY / ("password-lock-test-" + Uuid.random()),
                { store.provider() }).use { context ->
                val ref = context.setupPassword(config, password).getOrThrow()
                val configured = config.copy(extras = config.extras.copy(contentEncryption = ref))
                assertTrue(context.status(configured).getOrThrow().unlocked)
                val originalOwner = storageContentPasswordOwner(configured, ref.root) + "|" +
                    StorageAccounts.authorizationGeneration(configured)
                StorageAccounts.install(credential.copy(accessToken = "reauthorized-fixture-token"), accountId)
                assertFalse(context.status(configured).getOrThrow().unlocked)
                context.lock(configured).getOrThrow()
                assertFails { keys.keyForPasswordOperation(storageContentScope(configured), originalOwner) }
                assertTrue(context.resolveDownload(configured,
                    com.folderspan.data.file.FileSimpleInfo.nullFileSimpleInfo().copy(path = "/file")).isFailure)
                context.unlockPassword(configured, password).getOrThrow()
                assertTrue(context.status(configured).getOrThrow().unlocked)
                val previousOwner = storageContentPasswordOwner(configured, ref.root) + "|" +
                    StorageAccounts.authorizationGeneration(configured)
                StorageAccounts.install(credential, accountId)
                context.unlockPassword(configured, password).getOrThrow()
                assertFails { keys.keyForPasswordOperation(storageContentScope(configured), previousOwner) }
                StorageAccounts.store.remove(accountId)
                assertFalse(context.status(configured).getOrThrow().unlocked)
                StorageAccounts.install(credential)
                assertFalse(context.status(configured).getOrThrow().unlocked)
            }
        } finally {
            runCatching { StorageAccounts.store.remove(accountId) }
            com.folderspan.createSettings().remove("auth.storage.account-generation." + accountId)
        }
    }

    @Test
    fun reauthorizationDuringInitializationRejectsItsLateUnlock() = runSuspendTest {
        val accountId = Uuid.random().toString()
        val credential = StorageCredential(accountId, StorageAccountKind.Nextcloud, "fixture-provider-user",
            "Disposable late authorization fixture", server = "https://fixture.invalid", accessToken = "fixture-token")
        val config = NetworkConnectionConfig("Nextcloud", credential.server, "", "",
            extras = NetworkDriveExtras(cloud = CloudDriveExtras(accountId = accountId)))
        val store = TestStorageContentVaultStore()
        val settings = createInMemorySettings()
        try {
            StorageAccounts.install(credential)
            val context = StorageContentHostContext(StorageContentKeyStore(settings), settings,
                FileSystem.SYSTEM_TEMPORARY_DIRECTORY / ("password-host-test-" + Uuid.random()),
                { store.provider(afterWrite = { name ->
                    if (name.endsWith(".location")) StorageAccounts.install(credential, accountId)
                }) })
            context.use {
                assertTrue(it.setupPassword(config, password).isFailure)
                assertFalse(settings.keys.any { key -> key.startsWith("auth.storage.password-location.key.") })
                assertEquals(2, store.files.size)
            }
        } finally {
            runCatching { StorageAccounts.store.remove(accountId) }
            com.folderspan.createSettings().remove("auth.storage.account-generation." + accountId)
        }
    }

    @Test
    fun damagedKeyRecordsCannotExposeContentSecretsInFailuresOrLogs() {
        val settings = createInMemorySettings()
        val key = ByteArray(32) { (65 + it % 26).toByte() }
        val keys = StorageContentKeyStore(settings)
        val secret = "CONTENT_PASSWORD_MUST_NOT_LEAK"
        keys.unlockPasswordLocation("fixture-vault", "fixture-owner", key)
        val name = settings.keys.single { it.startsWith("auth.storage.password-location.key.") }
        LogCapture.setEnabled(true)
        try {
            for (corrupt in listOf("{\"key\":\"" + secret, "{\"version\":1,\"key\":\"" + secret + "\"}")) {
                settings.putString(name, corrupt)
                val failure = assertFails { keys.unlockPasswordLocation("fixture-vault", "fixture-owner", key) }
                LogCapture.append(LogLevel.ERROR, "content-key", failure, failure.toString())
                assertFalse(failure.toString().contains(secret))
            }
            val logs = LogCapture.snapshot().decodeToString()
            assertFalse(logs.contains(secret))
            assertFalse(logs.contains(key.decodeToString()))
        } finally {
            LogCapture.setEnabled(false)
            LogCapture.clear()
            keys.close()
            key.fill(0)
        }
    }

    @Test
    fun contextClosedDuringInitializationDoesNotAcceptLateUnlockOrDeleteRemoteMaterial() = runSuspendTest {
        val store = TestStorageContentVaultStore()
        val settings = createInMemorySettings()
        lateinit var context: StorageContentHostContext
        context = StorageContentHostContext(StorageContentKeyStore(settings), settings,
            FileSystem.SYSTEM_TEMPORARY_DIRECTORY / ("password-host-test-" + Uuid.random()),
            { store.provider(afterWrite = { name -> if (name.endsWith(".location")) context.close() }) })
        assertTrue(context.setupPassword(first, password).isFailure)
        assertEquals(2, store.files.size)
        host(store).use { fresh ->
            val recovered = fresh.setupPassword(second, password).getOrThrow()
            assertTrue(fresh.status(second.copy(extras = second.extras.copy(contentEncryption = recovered))).getOrThrow().unlocked)
        }
    }
}
