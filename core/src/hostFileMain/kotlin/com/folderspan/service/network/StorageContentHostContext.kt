package com.folderspan.service.network

import com.folderspan.data.file.FileSimpleInfo
import com.folderspan.data.main.network.StorageContentLocationReference
import com.folderspan.ui.state.main.TaskRuntimeEndpointRef
import com.folderspan.ui.state.main.TaskRuntimeStoreLock
import com.folderspan.utils.PathUtils
import com.russhwolf.settings.Settings
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okio.FileSystem
import okio.Path
import okio.Path.Companion.toPath
import okio.ByteString.Companion.toByteString
import okio.HashingSource
import okio.buffer
import okio.use
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

@OptIn(ExperimentalUuidApi::class)
internal class StorageContentHostContext(
    private val keys: StorageContentKeyStore = StorageContentKeyStore(),
    private val settings: Settings = createStorageContentSettings(),
    private val cacheRoot: Path = PathUtils.getCachePath().toPath().resolve("content-transfers"),
    private val providerFactory: (NetworkConnectionConfig) -> StorageProvider = NetworkClientFactory::create,
) : StorageContentOperations, AutoCloseable {
    private val stages = mutableMapOf<Path, Pair<Long, String>>()
    private val cipherSources = mutableMapOf<Path, Pair<Long, String>>()
    private val stageLock = TaskRuntimeStoreLock()
    private var closed = false
    private val fileSystem = FileSystem.SYSTEM
    private val json = Json { encodeDefaults = true }

    override suspend fun inspectPasswordLocation(config: NetworkConnectionConfig): Result<StorageContentLocationStatus> = storageResult {
        withStageLock { check(!closed) { "Content context is closed" } }
        config.extras.contentEncryption?.let {
            if (it.formatVersion != 1) throw StorageContentException(StorageContentErrorCode.UnsupportedFormat,
                "Unsupported password location reference")
        }
        withProvider(config) { provider ->
            val root = config.extras.contentEncryption?.takeIf { it.vaultId.isNotEmpty() }?.root ?: provider.rootLocation
            requireSafeNetworkWritePath(root.path)
            require(root.path.startsWith('/') && root.path.none(Char::isISOControl)) { "Invalid password location root" }
            val capabilities = provider.effectiveCapabilities().getOrThrow()
            val available = provider is ChunkReadableNetworkClient && capabilities.read
            val id = if (available) StorageContentVaultAccess(provider, root, settings, passwordOwner(config, root))
                .inspect(config.extras.contentEncryption?.vaultId.orEmpty()) else null
            StorageContentLocationStatus(available = available,
                writable = available && capabilities.write && !config.extras.cloud.readOnly, root = root,
                reference = id?.let { StorageContentLocationReference(root = root, vaultId = it) })
        }
    }

    override suspend fun setupPassword(config: NetworkConnectionConfig, password: CharArray): Result<StorageContentLocationReference> = storageResult {
        withStageLock { check(!closed) { "Content context is closed" } }
        config.extras.contentEncryption?.let {
            require(it.formatVersion == 1) { "Unsupported password location reference" }
            if (it.vaultId.isNotEmpty()) storageContentScope(config)
        }
        withProvider(config) { provider ->
            if (!provider.effectiveCapabilities().getOrThrow().write || config.extras.cloud.readOnly) {
                throw StorageContentException(StorageContentErrorCode.ReadOnly, "This location is read-only")
            }
            val root = config.extras.contentEncryption?.root ?: provider.rootLocation
            requireSafeNetworkWritePath(root.path)
            require(root.path.startsWith('/')) { "Password location requires an absolute root" }
            val owner = passwordOwner(config, root)
            val unlocked = StorageContentVaultAccess(provider, root, settings, owner)
                .initializeOrUnlock(password, config.extras.contentEncryption?.vaultId.orEmpty())
            try {
                val reference = StorageContentLocationReference(enabled = true, root = root, vaultId = unlocked.vaultId)
                val configured = config.copy(extras = config.extras.copy(contentEncryption = reference))
                withStageLock {
                    check(!closed) { "Content context is closed" }
                    check(owner == passwordOwner(configured, root)) { "Storage authorization changed while setting the content password" }
                    keys.unlockPasswordLocation(storageContentScope(configured), owner, unlocked.key,
                        authorizationOwner = storageContentPasswordOwner(configured, root))
                }
                reference
            } finally { unlocked.key.fill(0) }
        }
    }

    override suspend fun unlockPassword(config: NetworkConnectionConfig, password: CharArray): Result<Unit> = storageResult {
        withStageLock { check(!closed) { "Content context is closed" } }
        val reference = checkNotNull(config.extras.contentEncryption) { "Password location reference is missing" }
        val scope = storageContentScope(config)
        withProvider(config) { provider ->
            val owner = passwordOwner(config, reference.root)
            val unlocked = StorageContentVaultAccess(provider, reference.root, settings, owner)
                .unlock(password, reference.vaultId)
            try {
                withStageLock {
                    check(!closed) { "Content context is closed" }
                    check(owner == passwordOwner(config, reference.root)) { "Storage authorization changed while unlocking content" }
                    keys.unlockPasswordLocation(scope, owner, unlocked.key,
                        authorizationOwner = storageContentPasswordOwner(config, reference.root))
                }
            } finally { unlocked.key.fill(0) }
        }
    }

    override suspend fun status(config: NetworkConnectionConfig): Result<StorageContentStatus> = storageResult {
        val reference = config.extras.contentEncryption
        if (reference != null) withProvider(config) { provider ->
            val scope = storageContentScope(config)
            val state = keys.passwordLocationStatus(scope, passwordOwner(config, reference.root))
            val permissions = provider.effectiveCapabilities().getOrThrow()
            StorageContentStatus(scope = scope, enabled = reference.enabled, unlocked = state.second,
                available = provider is ChunkReadableNetworkClient && permissions.read, hasKey = state.first,
                metadataProtectionAvailable = provider is ChunkReadableNetworkClient && permissions.write)
        }
        else if (config.extras.cloud.connectionId.isBlank()) StorageContentStatus("", available = false)
        else withProvider(config) { provider ->
            val scope = storageContentScope(config)
            val state = keys.status(scope)
            StorageContentStatus(scope, state.first, state.second, keys.backupVerified(scope),
                hasKey = keys.hasKey(scope), metadataProtectionAvailable = provider.contentManifestConditionalWrites)
        }
    }

    override suspend fun create(config: NetworkConnectionConfig): Result<Unit> = storageResult {
        check(config.extras.contentEncryption == null) { "Use a content password to initialize this location" }
        val scope = storageContentScope(config)
        creationLock(scope).withLock {
            withProvider(config) { provider ->
                check(provider.contentManifestConditionalWrites) { "This backend cannot safely classify encrypted content" }
                if (!keys.hasKey(scope)) {
                    check(provider.readContentManifest(Uuid.random().toString()).getOrThrow() == null) {
                        "This directory already has authenticated content classification; import its recovery backup"
                    }
                    keys.create(scope)
                }
                withManifest(config, provider) { access ->
                    if (keys.manifestInitialized(scope)) access.read() else access.initialize()
                }
                keys.markManifestInitialized(scope)
            }
        }
    }

    override suspend fun exportRecovery(config: NetworkConnectionConfig, password: CharArray): Result<ByteArray> = storageResult {
        withProvider(config) { provider ->
            withManifest(config, provider) { it.read() }
            keys.exportRecovery(storageContentScope(config), password)
        }
    }

    override suspend fun verifyBackupAndEnable(config: NetworkConnectionConfig, backup: ByteArray, password: CharArray): Result<Unit> = storageResult {
        withProvider(config) { provider ->
            check(provider.contentManifestConditionalWrites) { "This backend cannot safely classify encrypted content" }
            withManifest(config, provider) { it.read() }
            keys.verifyBackupAndEnable(storageContentScope(config), backup, password)
        }
    }

    override suspend fun importRecovery(config: NetworkConnectionConfig, backup: ByteArray, password: CharArray): Result<Unit> = storageResult {
        withProvider(config) { provider ->
            val scope = storageContentScope(config)
            val recovered = StorageContentRecoveryV2.importRecovery(backup, scope, password)
            try {
                // A restored anchor is evidence of an existing catalog, never permission to create an empty one.
                StorageContentManifestAccess(provider, scope, recovered.anchor, recovered.key).read()
                keys.importRecovery(scope, backup, password)
            } finally { recovered.key.fill(0) }
        }
    }

    override suspend fun unlock(config: NetworkConnectionConfig): Result<Unit> = storageResult {
        check(config.extras.contentEncryption == null) { "Enter the content password to unlock this location" }
        withProvider(config) { provider ->
            withManifest(config, provider) { it.read() }
            keys.unlock(storageContentScope(config))
        }
    }
    override suspend fun lock(config: NetworkConnectionConfig): Result<Unit> = storageResult {
        val reference = config.extras.contentEncryption
        if (reference != null) keys.lockPasswordAuthorization(storageContentScope(config),
            storageContentPasswordOwner(config, reference.root))
        else keys.lock(storageContentScope(config))
    }
    override suspend fun setEnabled(config: NetworkConnectionConfig, enabled: Boolean): Result<Unit> = storageResult {
        val reference = config.extras.contentEncryption
        if (reference != null) {
            if (enabled) withProvider(config) { provider ->
                if (!provider.effectiveCapabilities().getOrThrow().write || config.extras.cloud.readOnly) {
                    throw StorageContentException(StorageContentErrorCode.ReadOnly, "This location is read-only")
                }
                val key = keys.keyForPasswordOperation(storageContentScope(config), passwordOwner(config, reference.root))
                try { StorageContentVaultAccess(provider, reference.root, settings, passwordOwner(config, reference.root))
                    .history(reference.vaultId, key) } finally { key.fill(0) }
            }
            return@storageResult
        }
        if (enabled) withProvider(config) { provider ->
            check(provider.contentManifestConditionalWrites) { "This backend cannot safely classify encrypted content" }
            withManifest(config, provider) { it.read() }
            keys.setEnabled(storageContentScope(config), true)
        } else keys.setEnabled(storageContentScope(config), false)
    }

    private suspend fun <T> withProvider(config: NetworkConnectionConfig, block: suspend (StorageProvider) -> T): T =
        withContext(Dispatchers.IO) {
            val provider = providerFactory(config)
            try { block(provider) } finally { provider.close() }
        }

    private suspend fun <T> withManifest(config: NetworkConnectionConfig, provider: StorageProvider,
        block: suspend (StorageContentManifestAccess) -> T): T {
        val scope = storageContentScope(config)
        val key = keys.keyForManifest(scope)
        return try { block(StorageContentManifestAccess(provider, scope, keys.manifestAnchor(scope), key)) }
        finally { key.fill(0) }
    }


    private fun passwordOwner(config: NetworkConnectionConfig, root: StoragePath): String =
        storageContentPasswordOwner(config, root) + "|" + StorageAccounts.authorizationGeneration(config)

    private fun inPasswordLocation(config: NetworkConnectionConfig, path: StoragePath): Boolean {
        val ref = config.extras.contentEncryption ?: return false
        storageContentScope(config)
        return path.reference?.content?.vaultId == ref.vaultId ||
            path.path == ref.root.path.trimEnd('/').ifEmpty { "/" } ||
            path.path.startsWith(ref.root.path.trimEnd('/') + "/")
    }

    private suspend fun <T> withPasswordLocation(config: NetworkConnectionConfig, write: Boolean = false,
        block: suspend (StorageContentLogicalAccess, StorageContentVaultAccess, ByteArray) -> T): T {
        withStageLock { check(!closed) { "Content context is closed" } }
        val ref = checkNotNull(config.extras.contentEncryption)
        val scope = storageContentScope(config)
        return withProvider(config) { provider ->
            if (write && (!provider.effectiveCapabilities().getOrThrow().write || config.extras.cloud.readOnly)) {
                throw StorageContentException(StorageContentErrorCode.ReadOnly, "This location is read-only")
            }
            val owner = passwordOwner(config, ref.root)
            val key = keys.keyForPasswordOperation(scope, owner)
            try {
                val access = StorageContentVaultAccess(provider, ref.root, settings, owner)
                block(StorageContentLogicalAccess(provider, ref, access, key), access, key)
            } finally { key.fill(0) }
        }
    }

    override suspend fun logicalList(config: NetworkConnectionConfig, directory: StoragePath): Result<List<NetworkFileEntry>?> =
        storageResult {
            if (!inPasswordLocation(config, directory)) null
            else withPasswordLocation(config) { logical, _, _ -> logical.list(directory) }
        }

    override suspend fun resolveDownload(config: NetworkConnectionConfig, file: FileSimpleInfo): Result<StorageContentDownload?> =
        storageResult {
            if (!inPasswordLocation(config, file.storagePath)) null
            else withPasswordLocation(config) { logical, _, _ -> logical.resolveDownload(file) }
        }

    override suspend fun renameLogical(config: NetworkConnectionConfig, source: StoragePath, target: StoragePath): Result<Boolean?> =
        storageResult {
            if (!inPasswordLocation(config, source) && !inPasswordLocation(config, target)) null
            else {
                check(inPasswordLocation(config, source) && inPasswordLocation(config, target)) {
                    "Moves across password location boundaries require a verified content copy"
                }
                withPasswordLocation(config, true) { logical, _, _ -> logical.rename(source, target) }
            }
        }

    override suspend fun deleteLogical(config: NetworkConnectionConfig, path: StoragePath, isDirectory: Boolean): Result<Boolean?> =
        storageResult {
            if (!inPasswordLocation(config, path)) null
            else withPasswordLocation(config, true) { logical, _, _ -> logical.delete(path, isDirectory) }
        }

    override suspend fun createLogicalDirectory(config: NetworkConnectionConfig, path: StoragePath): Result<Boolean?> =
        storageResult {
            if (!inPasswordLocation(config, path)) null
            else withPasswordLocation(config, true) { logical, _, _ ->
                logical.createDirectory(path, checkNotNull(config.extras.contentEncryption).enabled)
            }
        }

    override suspend fun resolveUploadTarget(config: NetworkConnectionConfig, target: StoragePath): Result<StoragePath> =
        storageResult {
            if (!inPasswordLocation(config, target)) target
            else withPasswordLocation(config, true) { logical, _, _ -> logical.resolveOrdinaryUpload(target) }
        }

    private fun passwordSpoolName(config: NetworkConnectionConfig, taskKey: Long, entryId: String): String {
        val ref = checkNotNull(config.extras.contentEncryption)
        // Authorization changes retire the unlock, while an explicit new unlock can still reconcile the original task.
        return PASSWORD_SPOOL_PREFIX + hash(storageContentPasswordOwner(config, ref.root) +
            json.encodeToString(listOf(taskKey.toString(), entryId, storageContentScope(config))))
    }

    private fun passwordSpool(config: NetworkConnectionConfig, taskKey: Long, entryId: String): PasswordSpool? =
        settings.getStringOrNull(passwordSpoolName(config, taskKey, entryId))?.let {
            try {
                json.decodeFromString<PasswordSpool>(it).also { value ->
                    check(value.version == 1 && value.taskKey == taskKey && value.entryId == entryId &&
                        value.vaultId == config.extras.contentEncryption?.vaultId)
                }
            } catch (_: Exception) { throw StorageContentException(StorageContentErrorCode.DamagedLocation,
                "Protected content upload recovery data is invalid") }
        }

    override suspend fun uploadEncryptionEnabled(config: NetworkConnectionConfig, taskKey: Long, entryId: String): Result<Boolean> =
        storageResult {
            if (config.extras.contentEncryption == null) status(config).getOrThrow().enabled
            else passwordSpool(config, taskKey, entryId) != null || checkNotNull(config.extras.contentEncryption).enabled
        }

    private suspend fun preparePasswordUpload(config: NetworkConnectionConfig, taskKey: Long, entryId: String,
        source: FileSimpleInfo, stagedPath: String): StoragePreparedContentUpload? = withContext(Dispatchers.IO) {
        val stage = checkedStage(taskKey, stagedPath)
        try {
            val previous = passwordSpool(config, taskKey, entryId)
            val ref = checkNotNull(config.extras.contentEncryption)
            if (!ref.enabled && previous == null) return@withContext null
            withPasswordLocation(config, true) { _, access, key ->
                val context = currentCoroutineContext()
                val sourceHash = digest(stage) { context.ensureActive() }
                val identity = json.encodeToString(TaskRuntimeEndpointRef(source.protocol, source.protocolId, source.path, source.storageReference))
                val name = passwordSpoolName(config, taskKey, entryId)
                previous?.let { saved ->
                    val history = access.metadataHistory(ref.vaultId, key)
                    val remote = history.records[saved.operationId]
                    if (remote != null) check(remote == saved.record) { "Content upload operation identity changed" }
                    if (saved.sourceIdentity != identity || saved.sourceDigest != sourceHash) {
                        if (saved.record != null || remote != null) throw StorageContentException(StorageContentErrorCode.SourceChanged,
                            "The source changed during an interrupted write; preserve it and verify the original operation")
                    } else {
                        val complete = remote != null
                        val uploaded = access.objectMatches(saved.objectId, saved.size, saved.cipherDigest)
                        if ((complete || saved.publicationStarted) && !uploaded) throw StorageContentException(StorageContentErrorCode.DamagedLocation,
                            "An object whose commit was published is missing or changed; preserve the original operation")
                        if (complete) settings.putString(name, json.encodeToString(saved.copy(completed = true)))
                        else if (!uploaded) {
                            val cipher = checkedCipherPath(taskKey, saved.path)
                            check(fileSystem.metadata(cipher).size == saved.size &&
                                digest(cipher) { context.ensureActive() } == saved.cipherDigest) { "Encrypted upload spool changed" }
                            rememberCipherSource(taskKey, cipher)
                        }
                        return@withPasswordLocation StoragePreparedContentUpload(saved.path, saved.size, sourceHash,
                            access.objectPath(saved.objectId), uploaded = uploaded, committed = complete)
                    }
                    fileSystem.delete(checkedCipherPath(taskKey, saved.path))
                }
                // Preparing only writes a private spool; beginUpload verifies fresh history before remote IO.
                access.authenticateLocation(ref.vaultId, key)
                val objectId = Uuid.random().toString()
                val cipher = StorageContentFileProtection.createPrivateTempFile(taskDirectory(taskKey), ".cipher-")
                var saved = false
                try {
                    fileSystem.source(stage).buffer().use { input ->
                        fileSystem.sink(cipher).buffer().use { output ->
                            StorageContentEncryptionV1.encrypt(input, output, key,
                                storageContentObjectScope(ref.vaultId, objectId)) { context.ensureActive() }
                        }
                    }
                    val value = PasswordSpool(taskKey = taskKey, entryId = entryId, vaultId = ref.vaultId,
                        sourceIdentity = identity, sourceDigest = sourceHash, path = cipher.toString(),
                        size = checkNotNull(fileSystem.metadata(cipher).size), sourceSize = checkNotNull(fileSystem.metadata(stage).size),
                        sourceUpdatedDate = maxOf(0, source.updatedDate), cipherDigest = digest(cipher) { context.ensureActive() },
                        objectId = objectId)
                    settings.putString(name, json.encodeToString(value))
                    saved = true
                    rememberCipherSource(taskKey, cipher)
                    StoragePreparedContentUpload(value.path, value.size, sourceHash, access.objectPath(objectId))
                } finally { if (!saved) fileSystem.delete(cipher) }
            }
        } finally { removeStage(stage) }
    }

    private suspend fun beginPasswordUpload(config: NetworkConnectionConfig, taskKey: Long, entryId: String,
        target: StoragePath) {
        if (!inPasswordLocation(config, target)) {
            check(passwordSpool(config, taskKey, entryId) == null) { "Prepared content cannot leave its password location" }
            return
        }
        withPasswordLocation(config, true) { logical, access, key ->
            val saved = passwordSpool(config, taskKey, entryId)
            if (saved == null) {
                check(!checkNotNull(config.extras.contentEncryption).enabled) { "Encrypted upload spool is missing" }
                logical.resolveOrdinaryUpload(target)
                return@withPasswordLocation
            }
            check(saved.target == null || saved.target == target) { "Encrypted upload destination changed" }
            saved.record?.let { record ->
                val current = access.metadataHistory(saved.vaultId, key)
                current.records[record.operationId]?.let {
                    check(it == record) { "Content upload operation identity changed" }
                    access.verifyObject(record)
                    settings.putString(passwordSpoolName(config, taskKey, entryId), json.encodeToString(saved.copy(completed = true)))
                }
                return@withPasswordLocation
            }
            val context = currentCoroutineContext()
            check(digest(checkedCipherPath(taskKey, saved.path)) { context.ensureActive() } == saved.cipherDigest) {
                "Encrypted upload spool changed"
            }
            val record = logical.prepareFile(target, saved.operationId, saved.objectId, saved.size,
                saved.cipherDigest, saved.sourceSize, saved.sourceUpdatedDate)
            settings.putString(passwordSpoolName(config, taskKey, entryId), json.encodeToString(saved.copy(target = target, record = record)))
        }
    }

    private suspend fun recordPasswordUpload(config: NetworkConnectionConfig, taskKey: Long, entryId: String,
        entry: NetworkFileEntry) {
        withPasswordLocation(config, true) { _, access, key ->
            val saved = checkNotNull(passwordSpool(config, taskKey, entryId)) { "Encrypted upload recovery data is missing" }
            val record = checkNotNull(saved.record) { "Encrypted upload has no registered logical destination" }
            val target = access.objectPath(saved.objectId)
            check(entry.path == target.path && entry.size == saved.size && !entry.isDirectory && !entry.isSymbolicLink) {
                "Encrypted upload result does not match its immutable object"
            }
            settings.putString(passwordSpoolName(config, taskKey, entryId), json.encodeToString(saved.copy(publicationStarted = true)))
            access.publish(record, key)
            settings.putString(passwordSpoolName(config, taskKey, entryId), json.encodeToString(saved.copy(completed = true, publicationStarted = true)))
        }
    }

    private suspend fun publishPasswordDownload(config: NetworkConnectionConfig, taskKey: Long, file: FileSimpleInfo,
        stagedPath: String, destinationPath: String): Boolean = withContext(Dispatchers.IO) {
        val stage = checkedStage(taskKey, stagedPath)
        try {
            if (!inPasswordLocation(config, file.storagePath)) {
                publishPlainStage(stage, StorageContentFileProtection.normalizedAbsolutePath(destinationPath))
                return@withContext true
            }
            withPasswordLocation(config) { logical, access, key ->
                val context = currentCoroutineContext()
                val identity = file.storageReference?.content
                val ref = checkNotNull(config.extras.contentEncryption)
                val managed = identity != null && identity.vaultId == ref.vaultId && identity.ordinaryPath.isEmpty()
                val destination = StorageContentFileProtection.normalizedAbsolutePath(destinationPath)
                if (managed) {
                    val history = access.metadataHistory(ref.vaultId, key)
                    val record = history.records[identity!!.revisionId]
                        ?: throw StorageContentException(StorageContentErrorCode.DamagedLocation, "The authenticated content revision is missing")
                    check(record.entryId == identity.entryId && record.kind == StorageContentCommitKind.File &&
                        record.size == file.size && record.ciphertextLength == fileSystem.metadata(stage).size &&
                        digest(stage) { context.ensureActive() } == record.ciphertextDigest) { "Managed content is damaged or changed" }
                    fileSystem.source(stage).buffer().use { input ->
                        StorageContentEncryptionV1.decryptAndPublish(input, destination, key,
                            storageContentObjectScope(ref.vaultId, record.objectId)) { context.ensureActive() }
                    }
                } else {
                    // Classification comes from authenticated metadata, never a guessed ciphertext magic prefix.
                    val classified = logical.resolveDownload(file)
                    check(classified.file.storageReference?.content?.ordinaryPath?.isNotEmpty() == true) {
                        "An ordinary file requires an authenticated directory classification"
                    }
                    publishPlainStage(stage, destination)
                }
                withStageLock {
                    stages[destination]?.let { stages[destination] = it.first to checkNotNull(StorageContentFileProtection.fileIdentity(destination)) }
                }
                true
            }
        } finally { removeStage(stage) }
    }

    @Serializable
    private data class PasswordSpool(
        val version: Int = 1, val taskKey: Long, val entryId: String, val vaultId: String,
        val sourceIdentity: String, val sourceDigest: String, val path: String, val size: Long,
        val sourceSize: Long, val sourceUpdatedDate: Long, val cipherDigest: String, val objectId: String,
        val operationId: String = Uuid.random().toString(), val target: StoragePath? = null,
        val record: StorageContentCommit? = null, val completed: Boolean = false, val publicationStarted: Boolean = false,
    )


    private suspend fun publishPlainStage(stage: Path, destination: Path) {
        val temporary = StorageContentFileProtection.createPrivateTempFile(checkNotNull(destination.parent), ".folderspan-plain-")
        try {
            fileSystem.source(stage).buffer().use { input ->
                fileSystem.sink(temporary).buffer().use { output ->
                    val bytes = ByteArray(1024 * 1024)
                    try {
                        while (true) {
                            currentCoroutineContext().ensureActive()
                            val count = input.read(bytes)
                            if (count < 0) break
                            output.write(bytes, 0, count)
                        }
                    } finally { bytes.fill(0) }
                }
            }
            currentCoroutineContext().ensureActive()
            StorageContentFileProtection.atomicMove(temporary, destination)
            withStageLock {
                stages[destination]?.let { stages[destination] = it.first to checkNotNull(StorageContentFileProtection.fileIdentity(destination)) }
            }
        } finally { fileSystem.delete(temporary) }
    }

    override suspend fun createStage(taskKey: Long, entryId: String): Result<String> = storageResult {
        withContext(Dispatchers.IO) {
            val stage = StorageContentFileProtection.createPrivateTempFile(taskDirectory(taskKey), ".input-")
            try {
                withStageLock {
                    check(!closed) { "Content context is closed" }
                    stages[stage] = taskKey to checkNotNull(StorageContentFileProtection.fileIdentity(stage))
                }
                stage.toString()
            } catch (error: Throwable) {
                fileSystem.delete(stage)
                throw error
            }
        }
    }

    override suspend fun stageIdentity(taskKey: Long, path: String): Result<String> = storageResult {
        withContext(Dispatchers.IO) {
            val value = StorageContentFileProtection.normalizedAbsolutePath(path)
            val cipher = withStageLock { check(!closed) { "Content context is closed" }; cipherSources[value] }
            if (cipher == null) checkNotNull(StorageContentFileProtection.fileIdentity(checkedStage(taskKey, path)))
            else {
                check(cipher.first == taskKey && value.parent == taskDirectory(taskKey) &&
                    StorageContentFileProtection.fileIdentity(value) == cipher.second) { "Encrypted staging ownership changed" }
                cipher.second
            }
        }
    }

    override suspend fun completeStageWrite(taskKey: Long, path: String, expectedIdentity: String): Result<Unit> = storageResult {
        withContext(Dispatchers.IO) {
            withStageLock { check(!closed) { "Content context is closed" } }
            val value = StorageContentFileProtection.normalizedAbsolutePath(path)
            check(value.parent == taskDirectory(taskKey)) { "Content stage is outside this task" }
            withStageLock {
                check(!closed && stages[value] == (taskKey to expectedIdentity)) { "Content stage write ownership changed" }
                val current = checkNotNull(StorageContentFileProtection.fileIdentity(value)) {
                    "Content stage is no longer a regular private file"
                }
                stages[value] = taskKey to current
            }
        }
    }

    private fun rememberCipherSource(taskKey: Long, path: Path) {
        val identity = checkNotNull(StorageContentFileProtection.fileIdentity(path)) { "Encrypted spool is unavailable" }
        check(path.parent == taskDirectory(taskKey) && path.name.startsWith(".cipher-")) { "Invalid encrypted staging path" }
        withStageLock {
            check(!closed) { "Content context is closed" }
            cipherSources[path] = taskKey to identity
        }
    }

    override suspend fun prepareUpload(
        config: NetworkConnectionConfig, taskKey: Long, entryId: String,
        source: FileSimpleInfo, stagedPath: String,
    ): Result<StoragePreparedContentUpload?> = storageResult {
        if (config.extras.contentEncryption != null) return@storageResult preparePasswordUpload(config, taskKey, entryId, source, stagedPath)
        withContext(Dispatchers.IO) {
            val stage = checkedStage(taskKey, stagedPath)
            try {
                val state = status(config).getOrThrow()
                if (!state.enabled) return@withContext null
                check(state.unlocked) { "Content encryption is locked; unlock or recover it" }
                val scope = state.scope
                val context = currentCoroutineContext()
                val sourceDigest = digest(stage) { context.ensureActive() }
                val identity = json.encodeToString(TaskRuntimeEndpointRef(
                    source.protocol, source.protocolId, source.path, source.storageReference))
                val name = SPOOL_PREFIX + hash("$taskKey|$entryId|$scope")
                val previous = settings.getStringOrNull(name)?.let { json.decodeFromString<Spool>(it) }
                previous?.let { record ->
                    check(record.version == 1 && record.taskKey == taskKey && record.entryId == entryId && record.scope == scope) {
                        "Encrypted spool identity is invalid"
                    }
                    if (record.sourceIdentity == identity && record.sourceDigest == sourceDigest) {
                        val ciphertext = checkedCipherPath(taskKey, record.path)
                        check(fileSystem.metadata(ciphertext).size == record.size &&
                            digest(ciphertext) { context.ensureActive() } == record.cipherDigest) { "Encrypted spool changed" }
                        rememberCipherSource(taskKey, ciphertext)
                        return@withContext StoragePreparedContentUpload(record.path, record.size, sourceDigest)
                    }
                }
                check(previous == null || !previous.intentRecorded || settings.getBoolean("auth.storage.content-completed." + previous.operationId, false)) {
                    "Source changed during an interrupted encrypted write; recover that write before replacing its spool"
                }
                val ciphertext = StorageContentFileProtection.createPrivateTempFile(taskDirectory(taskKey), ".cipher-")
                StorageContentFileProtection.restrictOwnerOnly(ciphertext, directory = false)
                var saved = false
                val key = keys.keyForOperation(scope)
                try {
                    fileSystem.source(stage).buffer().use { input ->
                        fileSystem.sink(ciphertext).buffer().use { output ->
                            StorageContentEncryptionV1.encrypt(input, output, key, scope) { context.ensureActive() }
                        }
                    }
                    val record = Spool(
                        taskKey = taskKey, entryId = entryId, scope = scope, sourceIdentity = identity,
                        sourceDigest = sourceDigest, path = ciphertext.toString(), size = checkNotNull(fileSystem.metadata(ciphertext).size),
                        sourceSize = checkNotNull(fileSystem.metadata(stage).size), sourceUpdatedDate = source.updatedDate,
                        cipherDigest = digest(ciphertext) { context.ensureActive() },
                    )
                    settings.putString(name, json.encodeToString(record))
                    saved = true
                    rememberCipherSource(taskKey, ciphertext)
                    StoragePreparedContentUpload(record.path, record.size, sourceDigest)
                } finally {
                    key.fill(0)
                    if (!saved) fileSystem.delete(ciphertext)
                }
            } finally { removeStage(stage) }
        }
    }

    override suspend fun beforeMutation(config: NetworkConnectionConfig, path: StoragePath): Result<Unit> = storageResult {
        if (config.extras.contentEncryption != null) {
            if (inPasswordLocation(config, path)) withPasswordLocation(config, true) { _, access, key ->
                access.history(checkNotNull(config.extras.contentEncryption).vaultId, key)
            }
            return@storageResult
        }
        if (config.extras.cloud.connectionId.isBlank()) return@storageResult
        val scope = storageContentScope(config)
        if (!keys.hasKey(scope)) {
            withProvider(config) { requireNoExistingStorageContentManifest(it) }
            return@storageResult
        }
        check(path.path != "/" && path.path.substringAfterLast('/') != contentManifestFileName(keys.manifestAnchor(scope))) {
            "Authenticated content classification cannot be renamed or deleted as an ordinary object"
        }
        withProvider(config) { provider ->
            withManifest(config, provider) { access ->
                val catalog = access.read()
                val id = provider.contentObjectIdentity(path).getOrThrow()
                check(catalog.records.none { !it.committed && (it.objectId.isBlank() || it.objectId == id ||
                    it.path == path.path || it.path.startsWith(path.path.trimEnd('/') + "/")) }) {
                    "Retry or verify an interrupted encrypted write before renaming, deleting, or restoring its objects"
                }
                // Committed identities survive rename/delete and native restores; keep their authenticated history.
            }
        }
    }

    override suspend fun beginUpload(config: NetworkConnectionConfig, taskKey: Long, entryId: String,
        target: StoragePath): Result<Unit> = storageResult {
        if (config.extras.contentEncryption != null) {
            beginPasswordUpload(config, taskKey, entryId, target)
            return@storageResult
        }
        if (config.extras.cloud.connectionId.isBlank()) return@storageResult
        withProvider(config) { provider ->
            val scope = storageContentScope(config)
            if (!keys.hasKey(scope)) {
                requireNoExistingStorageContentManifest(provider)
                return@withProvider
            }
            check(target.path.substringAfterLast('/') != contentManifestFileName(keys.manifestAnchor(scope))) {
                "Authenticated content metadata cannot be overwritten as an ordinary file"
            }
            val name = SPOOL_PREFIX + hash("$taskKey|$entryId|$scope")
            if (!keys.status(scope).first) {
                check(!settings.hasKey(name)) {
                    "Content encryption was disabled after preparing this upload; no ciphertext was sent"
                }
                withManifest(config, provider) { access ->
                    val catalog = access.read()
                    val identity = provider.contentObjectIdentity(target).getOrThrow()
                    check(catalog.records.none { it.objectId.isNotEmpty() && it.objectId == identity || !it.committed && it.path == target.path }) {
                        "Enable content encryption before overwriting managed content"
                    }
                    check(catalog.records.none { !it.committed && it.objectId.isBlank() }) {
                        "Retry or verify the interrupted encrypted write before writing unknown ordinary objects"
                    }
                }
                return@withProvider
            }
            val spool = loadSpool(scope, taskKey, entryId)
            val ciphertext = checkedCipherPath(taskKey, spool.path)
            val context = currentCoroutineContext()
            check(digest(ciphertext) { context.ensureActive() } == spool.cipherDigest) { "Encrypted upload spool changed" }
            val identity = provider.contentObjectIdentity(target).getOrThrow().orEmpty()
            check(spool.targetPath.isEmpty() || spool.targetPath == target.path) { "Encrypted upload destination changed" }
            // Persist recovery ownership before the remote CAS; a crash cannot leave an unowned intent.
            settings.putString(name, json.encodeToString(spool.copy(targetPath = target.path, intentRecorded = true)))
            withManifest(config, provider) { access ->
                val catalog = access.read()
                val old = catalog.records.singleOrNull { it.operationId == spool.operationId }
                if (old != null) {
                    check(old.path == target.path && old.ciphertextDigest == spool.cipherDigest &&
                        (old.objectId.isBlank() || old.objectId == identity)) { "Interrupted encrypted upload target changed; verify it before retrying" }
                } else {
                    val managed = identity.isNotEmpty() && catalog.records.any { it.objectId == identity }
                    val priorPlaintext = if (identity.isNotEmpty() && !managed) remoteDigest(provider, target, taskKey) else null
                    val intent = StorageContentManifestRecord(spool.operationId, target.path, identity,
                        spool.cipherDigest, previousPlaintextDigest = priorPlaintext)
                    access.update { current ->
                        check(current.records.none { it.operationId == intent.operationId }) { "Content operation identity already exists" }
                        current.copy(records = current.records + intent)
                    }
                }
            }
            settings.putString(name, json.encodeToString(spool.copy(targetPath = target.path, intentRecorded = true)))
        }
    }

    private fun loadSpool(scope: String, taskKey: Long, entryId: String): Spool = json.decodeFromString<Spool>(
        checkNotNull(settings.getStringOrNull(SPOOL_PREFIX + hash("$taskKey|$entryId|$scope"))) {
            "Encrypted upload spool is unavailable"
        }).also { check(it.version == 1 && it.scope == scope && it.taskKey == taskKey && it.entryId == entryId) {
            "Encrypted spool identity is invalid"
        } }

    private suspend fun remoteDigest(provider: StorageProvider, target: StoragePath, taskKey: Long): String {
        val entry = provider.stat(target).getOrThrow()
        check(!entry.isDirectory && !entry.isSymbolicLink && entry.size >= 0) { "Content target is not an ordinary file" }
        val temporary = StorageContentFileProtection.createPrivateTempFile(taskDirectory(taskKey), ".verify-")
        StorageContentFileProtection.restrictOwnerOnly(temporary, directory = false)
        return try {
            check(provider.download(target, temporary.toString(), entry.size).getOrThrow()) { "Could not verify current remote content" }
            val context = currentCoroutineContext()
            digest(temporary) { context.ensureActive() }
        } finally { fileSystem.delete(temporary) }
    }

    override suspend fun publishDownload(
        config: NetworkConnectionConfig, taskKey: Long, file: FileSimpleInfo,
        stagedPath: String, destinationPath: String,
    ): Result<Boolean> = storageResult {
        if (config.extras.contentEncryption != null) return@storageResult publishPasswordDownload(config, taskKey, file, stagedPath, destinationPath)
        withContext(Dispatchers.IO) {
            val stage = checkedStage(taskKey, stagedPath)
            val context = currentCoroutineContext()
            try {
                val prefix = fileSystem.source(stage).buffer().use { input ->
                    input.readByteArray(minOf(8L, fileSystem.metadata(stage).size ?: 0L))
                }
                val encrypted = prefix.contentEquals(MAGIC)
                val scope = if (config.extras.cloud.connectionId.isBlank()) null else storageContentScope(config)
                var pendingRecovery: StorageContentManifestRecord? = null
                var pendingObjectId: String? = null
                if (scope != null) withProvider(config) { provider ->
                    if (!keys.hasKey(scope)) {
                        requireNoExistingStorageContentManifest(provider)
                        return@withProvider
                    }
                    withManifest(config, provider) { access ->
                        val manifest = access.read()
                        val id = provider.contentObjectIdentity(StoragePath(file.path, file.storageReference)).getOrThrow()
                        val records = manifest.records.filter { it.objectId.isNotEmpty() && it.objectId == id ||
                            !it.committed && it.path == file.path }
                        val actualDigest = digest(stage) { context.ensureActive() }
                        val recoverable = manifest.records.filter { !it.committed && it.objectId.isBlank() && it.ciphertextDigest == actualDigest }
                        check(recoverable.size <= 1) { "Interrupted encrypted output matches multiple intents; verify its original target" }
                        if (recoverable.isNotEmpty()) {
                            check(encrypted && !id.isNullOrBlank()) { "Interrupted encrypted output has no verifiable stable identity" }
                            val intent = recoverable.single()
                            val originalId = provider.contentObjectIdentity(StoragePath(intent.path)).getOrThrow()
                            check(originalId == null || originalId == id) { "Interrupted output has another object at its original target; verify that object first" }
                            pendingRecovery = intent
                            pendingObjectId = id
                        }
                        if (records.isNotEmpty()) {
                            check(records.any { it.ciphertextDigest == actualDigest || it.previousPlaintextDigest == actualDigest }) {
                                "Managed content is damaged or replaced; recover an authenticated native version"
                            }
                        } else if (!encrypted) {
                            check(manifest.records.none { !it.committed && it.objectId.isBlank() }) {
                                "An interrupted encrypted write has no stable identity; retry or verify that write before reading unknown ordinary files"
                            }
                        }
                    }
                }
                val destination = StorageContentFileProtection.normalizedAbsolutePath(destinationPath)
                if (encrypted) {
                    val scope = storageContentScope(config)
                    var key = byteArrayOf()
                    val pending = pendingRecovery
                    val verified = if (pending == null) destination else StorageContentFileProtection.createPrivateTempFile(checkNotNull(destination.parent), ".folderspan-verified-")
                    try {
                        if (pending != null) StorageContentFileProtection.restrictOwnerOnly(verified, directory = false)
                        key = keys.keyForReadOperation(scope)
                        fileSystem.source(stage).buffer().use { input ->
                            StorageContentEncryptionV1.decryptAndPublish(input, verified, key, scope) { context.ensureActive() }
                        }
                        if (pending != null) {
                            // Full frame/footer/EOF authentication succeeded in a private file; no user target changed yet.
                            withProvider(config) { provider ->
                                val id = provider.contentObjectIdentity(StoragePath(file.path, file.storageReference)).getOrThrow()
                                check(id != null && id == pendingObjectId) { "Interrupted content identity changed during verification" }
                                val originalId = provider.contentObjectIdentity(StoragePath(pending.path)).getOrThrow()
                                check(originalId == null || originalId == id) { "Interrupted output target became ambiguous during verification" }
                                withManifest(config, provider) { access ->
                                    access.update { current ->
                                        val matching = current.records.filter { it.operationId == pending.operationId }
                                        val selected = matching.singleOrNull() ?: error("Interrupted content intent disappeared")
                                        check(selected.ciphertextDigest == pending.ciphertextDigest &&
                                            (selected.objectId.isBlank() || selected.objectId == id) &&
                                            current.records.count { !it.committed && it.objectId.isBlank() && it.ciphertextDigest == pending.ciphertextDigest } <= 1) {
                                            "Interrupted content identity became ambiguous"
                                        }
                                        current.copy(records = current.records.map { if (it.operationId == pending.operationId)
                                            it.copy(path = file.path, objectId = id, committed = true) else it })
                                    }
                                }
                            }
                            context.ensureActive()
                            StorageContentFileProtection.atomicMove(verified, destination)
                            if (settings.keys.filter { it.startsWith(SPOOL_PREFIX) }.any { name ->
                                settings.getStringOrNull(name)?.let { json.decodeFromString<Spool>(it).operationId == pending.operationId } == true
                            }) settings.putBoolean("auth.storage.content-completed." + pending.operationId, true)
                        }
                    } finally {
                        key.fill(0)
                        if (pending != null) fileSystem.delete(verified)
                    }
                } else {
                    val temporary = StorageContentFileProtection.createPrivateTempFile(checkNotNull(destination.parent), ".folderspan-plain-")
                    StorageContentFileProtection.restrictOwnerOnly(temporary, directory = false)
                    try {
                        fileSystem.source(stage).buffer().use { input ->
                            fileSystem.sink(temporary).buffer().use { output ->
                                val buffer = ByteArray(1024 * 1024)
                                try {
                                    while (true) {
                                        context.ensureActive()
                                        val count = input.read(buffer)
                                        if (count < 0) break
                                        output.write(buffer, 0, count)
                                    }
                                } finally { buffer.fill(0) }
                            }
                        }
                        context.ensureActive()
                        StorageContentFileProtection.atomicMove(temporary, destination)
                    } finally { fileSystem.delete(temporary) }
                }
                withStageLock {
                    stages[destination]?.let { owned ->
                        stages[destination] = owned.first to checkNotNull(StorageContentFileProtection.fileIdentity(destination))
                    }
                }
                true
            } finally { removeStage(stage) }
        }
    }

    override suspend fun recordUploaded(
        config: NetworkConnectionConfig, taskKey: Long, entryId: String, entry: NetworkFileEntry,
    ): Result<Unit> = storageResult {
        if (config.extras.contentEncryption != null) {
            recordPasswordUpload(config, taskKey, entryId, entry)
            return@storageResult
        }
        withProvider(config) { provider ->
            val scope = storageContentScope(config)
            val spool = loadSpool(scope, taskKey, entryId)
            check(spool.intentRecorded && spool.size == entry.size && spool.targetPath == entry.path) {
                "Encrypted upload result does not match its registered intent"
            }
            val target = StoragePath(entry.path, entry.reference)
            val id = checkNotNull(provider.contentObjectIdentity(target).getOrThrow()) { "Uploaded content has no stable identity" }
            check(remoteDigest(provider, target, taskKey) == spool.cipherDigest) {
                "Uploaded ciphertext changed before confirmation; preserve its intent and recover this write"
            }
            withManifest(config, provider) { access ->
                access.update { current ->
                    val intent = current.records.singleOrNull { it.operationId == spool.operationId }
                        ?: error("Encrypted upload classification intent is missing")
                    check(intent.path == entry.path && intent.ciphertextDigest == spool.cipherDigest &&
                        (intent.objectId.isBlank() || intent.objectId == id)) { "Uploaded content identity changed" }
                    current.copy(records = current.records.map { if (it.operationId == intent.operationId)
                        it.copy(objectId = id, committed = true) else it })
                }
            }
            settings.putBoolean("auth.storage.content-completed." + spool.operationId, true)
            val reference = entry.reference?.takeIf { it.id.isNotBlank() } ?: StorageReference(id)
            val metadata = LogicalMetadata(
                scope = scope, reference = reference, remoteSize = entry.size, remoteUpdatedDate = entry.updatedDate,
                size = spool.sourceSize, updatedDate = spool.sourceUpdatedDate, sourceDigest = spool.sourceDigest,
            )
            settings.putString(META_PREFIX + hash(scope + json.encodeToString(reference)), json.encodeToString(metadata))
        }
    }

    override suspend fun logicalFile(
        config: NetworkConnectionConfig, file: FileSimpleInfo,
    ): Result<FileSimpleInfo?> = storageResult {
        if (config.extras.contentEncryption != null) {
            if (!inPasswordLocation(config, file.storagePath)) return@storageResult null
            return@storageResult withPasswordLocation(config) { logical, _, _ ->
                val entry = logical.stat(file.storagePath) ?: throw StorageEntryNotFoundException()
                file.copy(name = entry.name, path = entry.path, isDirectory = entry.isDirectory,
                    isHidden = entry.isHidden, size = entry.size, createdDate = entry.createdDate,
                    updatedDate = entry.updatedDate, isSymbolicLink = entry.isSymbolicLink,
                    isSymbolicLinkKnown = entry.isSymbolicLinkKnown, storageReference = entry.reference)
            }
        }
        if (config.extras.cloud.connectionId.isBlank() || file.updatedDate <= 0 || file.storageReference?.id.isNullOrBlank()) null
        else {
            val scope = storageContentScope(config)
            settings.getStringOrNull(META_PREFIX + hash(scope + json.encodeToString(file.storageReference)))?.let { value ->
                val record = json.decodeFromString<LogicalMetadata>(value)
                check(record.version == 1) { "Unsupported encrypted logical metadata" }
                if (record.updatedDate > 0 && record.remoteUpdatedDate > 0 &&
                    record.scope == scope && record.reference == file.storageReference && record.remoteSize == file.size &&
                    record.remoteUpdatedDate == file.updatedDate) file.copy(size = record.size, updatedDate = record.updatedDate)
                else null
            }
        }
    }

    override suspend fun cleanup(taskKey: Long): Result<Unit> = storageResult {
        withContext(Dispatchers.IO) {
            val passwordSpools = settings.keys.filter { it.startsWith(PASSWORD_SPOOL_PREFIX) }.mapNotNull { name ->
                settings.getStringOrNull(name)?.let { name to json.decodeFromString<PasswordSpool>(it) }
            }.filter { it.second.taskKey == taskKey }
            check(passwordSpools.none { it.second.record != null && !it.second.completed }) {
                "Verify the original content operation before cleaning its recovery data"
            }
            check(!StorageUploadSessionStore(settings).hasUncertainTask(taskKey)) {
                "Verify uncertain upload completion before cleaning encrypted recovery data"
            }
            val taskSpools = settings.keys.filter { it.startsWith(SPOOL_PREFIX) }.mapNotNull { name ->
                settings.getStringOrNull(name)?.let { json.decodeFromString<Spool>(it) }?.takeIf { it.taskKey == taskKey }
            }
            check(taskSpools.none { it.intentRecorded && !settings.getBoolean("auth.storage.content-completed." + it.operationId, false) }) {
                "An encrypted write is still pending; retry or verify it before cleaning its recovery spool"
            }
            withStageLock { stages.filterValues { it.first == taskKey }.keys.toList() }.forEach(::removeStage)
            val directory = StorageContentFileProtection.normalizedAbsolutePath(cacheRoot.toString()).resolve(taskKey.toString())
            if (fileSystem.metadataOrNull(directory) != null) {
                check(isRealDirectory(directory)) { "Content cache ownership changed" }
                fileSystem.deleteRecursively(directory)
            }
            settings.keys.filter { it.startsWith(SPOOL_PREFIX) }.forEach { name ->
                val record = settings.getStringOrNull(name)?.let { json.decodeFromString<Spool>(it) }
                if (record?.taskKey == taskKey) {
                    check(!record.intentRecorded || settings.getBoolean("auth.storage.content-completed." + record.operationId, false)) {
                        "An encrypted write is still pending; retry or verify it before cleaning its recovery spool"
                    }
                    settings.remove(name)
                    settings.remove("auth.storage.content-completed." + record.operationId)
                }
            }
            passwordSpools.forEach { settings.remove(it.first) }
            withStageLock { cipherSources.entries.removeAll { it.value.first == taskKey } }
        }
    }

    override fun close() {
        val owned = withStageLock {
            closed = true
            cipherSources.clear()
            stages.toMap().also { stages.clear() }
        }
        keys.close()
        owned.forEach { (path, registration) ->
            if (StorageContentFileProtection.fileIdentity(path) == registration.second) fileSystem.delete(path)
        }
    }

    private fun taskDirectory(taskKey: Long): Path {
        val root = StorageContentFileProtection.normalizedAbsolutePath(cacheRoot.toString())
        StorageContentFileProtection.createPrivateDirectory(root)
        val directory = root.resolve(taskKey.toString())
        StorageContentFileProtection.createPrivateDirectory(directory)
        return directory
    }

    private fun isRealDirectory(path: Path): Boolean =
        fileSystem.metadataOrNull(path)?.let { it.isDirectory && it.symlinkTarget == null } == true

    private fun checkedStage(taskKey: Long, value: String): Path {
        val path = StorageContentFileProtection.normalizedAbsolutePath(value)
        val registration = withStageLock { stages[path] }
        check(registration != null && registration.first == taskKey && path.parent == taskDirectory(taskKey) &&
            StorageContentFileProtection.fileIdentity(path) == registration.second) {
            "Content stage does not belong to this task and execution session"
        }
        return path
    }

    private fun checkedCipherPath(taskKey: Long, value: String): Path {
        val path = StorageContentFileProtection.normalizedAbsolutePath(value)
        check(path.parent == taskDirectory(taskKey) && path.name.startsWith(".cipher-") &&
            StorageContentFileProtection.fileIdentity(path) != null) { "Encrypted spool is unavailable" }
        return path
    }

    private fun removeStage(path: Path) {
        val registered = withStageLock { stages.remove(path) } ?: return
        if (StorageContentFileProtection.fileIdentity(path) == registered.second) fileSystem.delete(path)
    }

    private inline fun <T> withStageLock(block: () -> T): T {
        stageLock.lock()
        return try { block() } finally { stageLock.unlock() }
    }

    private fun digest(path: Path, checkCancelled: () -> Unit): String {
        val hash = HashingSource.sha256(fileSystem.source(path))
        hash.buffer().use { input ->
            val buffer = ByteArray(1024 * 1024)
            try {
                while (true) {
                    checkCancelled()
                    if (input.read(buffer) < 0) break
                }
            } finally { buffer.fill(0) }
        }
        return hash.hash.hex()
    }

    private fun hash(value: String) = StorageContentCrypto.sha256(value.encodeToByteArray()).toByteString().hex()

    @Serializable
    private data class Spool(
        val version: Int = 1, val taskKey: Long, val entryId: String, val scope: String,
        val sourceIdentity: String, val sourceDigest: String, val path: String, val size: Long,
        val sourceSize: Long, val sourceUpdatedDate: Long, val cipherDigest: String,
        val operationId: String = Uuid.random().toString(), val targetPath: String = "", val intentRecorded: Boolean = false,
    )

    @Serializable
    private data class LogicalMetadata(
        val version: Int = 1, val scope: String, val reference: StorageReference,
        val remoteSize: Long, val remoteUpdatedDate: Long, val size: Long, val updatedDate: Long,
        val sourceDigest: String,
    )

    private companion object {
        private val creationLocks = mutableMapOf<String, Mutex>()
        private val creationMapLock = TaskRuntimeStoreLock()
        fun creationLock(scope: String): Mutex {
            creationMapLock.lock()
            return try { creationLocks.getOrPut(scope) { Mutex() } } finally { creationMapLock.unlock() }
        }
        const val META_PREFIX = "auth.storage.content-metadata."
        const val SPOOL_PREFIX = "auth.storage.content-spool."
        const val PASSWORD_SPOOL_PREFIX = "auth.storage.password-location.spool."
        val MAGIC = "FSPANENC".encodeToByteArray()
    }
}
