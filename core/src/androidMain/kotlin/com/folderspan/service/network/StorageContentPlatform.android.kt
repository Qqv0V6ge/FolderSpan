package com.folderspan.service.network

import com.folderspan.androidContext
import com.folderspan.data.file.FileSimpleInfo
import com.folderspan.data.main.network.StorageContentLocationReference
import com.folderspan.security.TinkEncryption
import kotlinx.coroutines.CancellationException

actual object StorageContentPlatform : StorageContentOperations by AndroidKeystoreStorageContentOperations()

private const val CONTENT_KEYSTORE_UNAVAILABLE = "Android Keystore protection for content keys is unavailable; unlock the device or recover its protected settings"

/** Gate content keys without making the ordinary settings store depend on Keystore availability. */
internal class AndroidKeystoreStorageContentOperations(
    private val requireProtection: () -> Unit = { TinkEncryption.requireContentKeyProtection(androidContext()) },
    private val hasContentKey: (NetworkConnectionConfig) -> Boolean = { config ->
        config.extras.cloud.connectionId.isNotBlank() &&
            StorageContentKeyStore().use { it.hasKey(storageContentScope(config)) }
    },
    private val requireNoRemoteCatalog: suspend (NetworkConnectionConfig) -> Unit = { config ->
        val provider = NetworkClientFactory.create(config)
        try { requireNoExistingStorageContentManifest(provider) } finally { provider.close() }
    },
    private val operationsFactory: () -> StorageContentOperations = { StorageContentHostContext() },
) : StorageContentOperations {
    private val operations by lazy(operationsFactory)

    private fun protectionFailure(): Throwable? = try {
        requireProtection()
        null
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (error: Throwable) {
        IllegalStateException(CONTENT_KEYSTORE_UNAVAILABLE, error)
    }

    private suspend fun requireOrdinaryScope(config: NetworkConnectionConfig, failure: Throwable) {
        if (config.extras.contentEncryption != null) throw failure
        // Absence must be known. A damaged settings record cannot be treated as an empty key store.
        val existing = try { hasContentKey(config) }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Throwable) { throw failure }
        if (existing) throw failure
        // Second hosts may have no local key yet. The fixed remote catalog still blocks raw access.
        requireNoRemoteCatalog(config)
    }

    private suspend fun <T> checked(
        config: NetworkConnectionConfig? = null,
        allowOrdinary: Boolean = false,
        block: suspend (StorageContentOperations) -> Result<T>,
    ): Result<T> = storageResult {
        protectionFailure()?.let { failure ->
            if (!allowOrdinary || config == null) throw failure
            requireOrdinaryScope(config, failure)
        }
        block(operations).getOrThrow()
    }

    override suspend fun inspectPasswordLocation(config: NetworkConnectionConfig): Result<StorageContentLocationStatus> =
        checked { it.inspectPasswordLocation(config) }
    override suspend fun setupPassword(config: NetworkConnectionConfig, password: CharArray): Result<StorageContentLocationReference> =
        checked { it.setupPassword(config, password) }
    override suspend fun unlockPassword(config: NetworkConnectionConfig, password: CharArray): Result<Unit> =
        checked { it.unlockPassword(config, password) }
    override suspend fun uploadEncryptionEnabled(config: NetworkConnectionConfig, taskKey: Long, entryId: String): Result<Boolean> =
        checked(config, allowOrdinary = true) { it.uploadEncryptionEnabled(config, taskKey, entryId) }
    override suspend fun resolveUploadTarget(config: NetworkConnectionConfig, target: StoragePath): Result<StoragePath> =
        checked(config, allowOrdinary = true) { it.resolveUploadTarget(config, target) }
    override suspend fun logicalList(config: NetworkConnectionConfig, directory: StoragePath): Result<List<NetworkFileEntry>?> =
        checked(config, allowOrdinary = true) { it.logicalList(config, directory) }
    override suspend fun resolveDownload(config: NetworkConnectionConfig, file: FileSimpleInfo): Result<StorageContentDownload?> =
        checked(config, allowOrdinary = true) { it.resolveDownload(config, file) }
    override suspend fun renameLogical(config: NetworkConnectionConfig, source: StoragePath, target: StoragePath): Result<Boolean?> =
        checked(config, allowOrdinary = true) { it.renameLogical(config, source, target) }
    override suspend fun deleteLogical(config: NetworkConnectionConfig, path: StoragePath, isDirectory: Boolean): Result<Boolean?> =
        checked(config, allowOrdinary = true) { it.deleteLogical(config, path, isDirectory) }
    override suspend fun createLogicalDirectory(config: NetworkConnectionConfig, path: StoragePath): Result<Boolean?> =
        checked(config, allowOrdinary = true) { it.createLogicalDirectory(config, path) }

    override suspend fun status(config: NetworkConnectionConfig): Result<StorageContentStatus> = storageResult {
        val failure = protectionFailure()
        if (failure == null) operations.status(config).getOrThrow()
        else {
            requireOrdinaryScope(config, failure)
            StorageContentStatus(scope = "", available = false)
        }
    }

    override suspend fun create(config: NetworkConnectionConfig): Result<Unit> = checked { it.create(config) }
    override suspend fun exportRecovery(config: NetworkConnectionConfig, password: CharArray): Result<ByteArray> =
        checked { it.exportRecovery(config, password) }
    override suspend fun verifyBackupAndEnable(config: NetworkConnectionConfig, backup: ByteArray, password: CharArray): Result<Unit> =
        checked { it.verifyBackupAndEnable(config, backup, password) }
    override suspend fun importRecovery(config: NetworkConnectionConfig, backup: ByteArray, password: CharArray): Result<Unit> =
        checked { it.importRecovery(config, backup, password) }
    override suspend fun unlock(config: NetworkConnectionConfig): Result<Unit> = checked { it.unlock(config) }
    override suspend fun lock(config: NetworkConnectionConfig): Result<Unit> = storageResult { operations.lock(config).getOrThrow() }
    override suspend fun setEnabled(config: NetworkConnectionConfig, enabled: Boolean): Result<Unit> =
        checked { it.setEnabled(config, enabled) }
    override suspend fun createStage(taskKey: Long, entryId: String): Result<String> = checked { it.createStage(taskKey, entryId) }
    override suspend fun stageIdentity(taskKey: Long, path: String): Result<String> = checked { it.stageIdentity(taskKey, path) }
    override suspend fun completeStageWrite(taskKey: Long, path: String, expectedIdentity: String): Result<Unit> =
        checked { it.completeStageWrite(taskKey, path, expectedIdentity) }
    override suspend fun prepareUpload(config: NetworkConnectionConfig, taskKey: Long, entryId: String,
        source: FileSimpleInfo, stagedPath: String): Result<StoragePreparedContentUpload?> =
        checked(config, allowOrdinary = true) { it.prepareUpload(config, taskKey, entryId, source, stagedPath) }
    override suspend fun beforeMutation(config: NetworkConnectionConfig, path: StoragePath): Result<Unit> =
        checked(config, allowOrdinary = true) { it.beforeMutation(config, path) }
    override suspend fun beginUpload(config: NetworkConnectionConfig, taskKey: Long, entryId: String, target: StoragePath): Result<Unit> =
        checked(config, allowOrdinary = true) { it.beginUpload(config, taskKey, entryId, target) }
    override suspend fun publishDownload(config: NetworkConnectionConfig, taskKey: Long, file: FileSimpleInfo,
        stagedPath: String, destinationPath: String): Result<Boolean> =
        checked { it.publishDownload(config, taskKey, file, stagedPath, destinationPath) }
    override suspend fun recordUploaded(config: NetworkConnectionConfig, taskKey: Long, entryId: String, entry: NetworkFileEntry): Result<Unit> =
        checked { it.recordUploaded(config, taskKey, entryId, entry) }
    override suspend fun logicalFile(config: NetworkConnectionConfig, file: FileSimpleInfo): Result<FileSimpleInfo?> =
        checked(config, allowOrdinary = true) { it.logicalFile(config, file) }
    override suspend fun cleanup(taskKey: Long): Result<Unit> = storageResult { operations.cleanup(taskKey).getOrThrow() }
}
