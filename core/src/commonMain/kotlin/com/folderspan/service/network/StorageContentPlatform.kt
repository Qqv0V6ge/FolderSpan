package com.folderspan.service.network

import com.folderspan.data.file.FileSimpleInfo
import com.folderspan.data.main.network.StorageContentLocationReference
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import strings.AppStrings

@Serializable
data class StorageContentStatus(
    val scope: String,
    val enabled: Boolean = false,
    val unlocked: Boolean = false,
    val backupVerified: Boolean = false,
    val available: Boolean = true,
    val hasKey: Boolean = false,
    val metadataProtectionAvailable: Boolean = false,
)

@Serializable
data class StorageContentLocationStatus(
    val available: Boolean = false,
    val writable: Boolean = false,
    val root: StoragePath = StoragePath("/"),
    val reference: StorageContentLocationReference? = null,
)

@Serializable
data class StorageContentDownload(val file: FileSimpleInfo, val target: StoragePath, val size: Long)

@Serializable
data class StoragePreparedContentUpload(val path: String, val size: Long, val sourceVersion: String,
    val target: StoragePath? = null, val uploaded: Boolean = false, val committed: Boolean = false)

/** Stable connection and configured directory/library identity; never OAuth secrets or display names. */
@OptIn(ExperimentalUuidApi::class)
internal fun storageContentScope(config: NetworkConnectionConfig): String {
    config.extras.contentEncryption?.let {
        require(it.root.path.startsWith('/') && it.root.path.none(Char::isISOControl)) { "Invalid password location root" }
        requireSafeNetworkWritePath(it.root.path)
        if (it.vaultId.isEmpty()) throw StorageContentException(StorageContentErrorCode.Locked,
            AppStrings.storage_content_pending_verification)
        require(it.formatVersion == 1 && Uuid.parse(it.vaultId).toString() == it.vaultId) {
            "Unsupported password location reference"
        }
        return Json.encodeToString(listOf("password-location-v1", it.vaultId))
    }
    val cloud = config.extras.cloud
    require(cloud.connectionId.isNotBlank()) { "Content encryption requires a stable connection identity" }
    return Json.encodeToString(listOf(config.protocol, cloud.accountId, cloud.connectionId, cloud.rootId, cloud.libraryId))
}

/** Authorization ownership is separate from the portable ciphertext/key scope. */
internal fun storageContentPasswordOwner(config: NetworkConnectionConfig, root: StoragePath): String {
    val cloud = config.extras.cloud
    return Json.encodeToString(listOf(config.sessionOwner, config.protocol, config.host, config.username,
        cloud.accountId, cloud.connectionId, cloud.rootId, cloud.libraryId,
        Json.encodeToString(StoragePath.serializer(), root)))
}

interface StorageContentOperations {
    suspend fun inspectPasswordLocation(config: NetworkConnectionConfig): Result<StorageContentLocationStatus> =
        Result.success(StorageContentLocationStatus())
    suspend fun uploadEncryptionEnabled(config: NetworkConnectionConfig, taskKey: Long, entryId: String): Result<Boolean> =
        status(config).map { it.enabled }
    suspend fun resolveUploadTarget(config: NetworkConnectionConfig, target: StoragePath): Result<StoragePath> =
        if (config.extras.contentEncryption == null) Result.success(target)
        else Result.failure(StorageContentException(StorageContentErrorCode.Unavailable,
            "Password location upload routing is unavailable on this execution host"))
    suspend fun logicalList(config: NetworkConnectionConfig, directory: StoragePath): Result<List<NetworkFileEntry>?> =
        unavailablePasswordOperation(config)
    suspend fun resolveDownload(config: NetworkConnectionConfig, file: FileSimpleInfo): Result<StorageContentDownload?> =
        unavailablePasswordOperation(config)
    suspend fun renameLogical(config: NetworkConnectionConfig, source: StoragePath, target: StoragePath): Result<Boolean?> =
        unavailablePasswordOperation(config)
    suspend fun deleteLogical(config: NetworkConnectionConfig, path: StoragePath, isDirectory: Boolean): Result<Boolean?> =
        unavailablePasswordOperation(config)
    suspend fun createLogicalDirectory(config: NetworkConnectionConfig, path: StoragePath): Result<Boolean?> =
        unavailablePasswordOperation(config)
    suspend fun setupPassword(config: NetworkConnectionConfig, password: CharArray): Result<StorageContentLocationReference> =
        Result.failure(UnsupportedOperationException("Password locations are unavailable on this execution host"))
    suspend fun unlockPassword(config: NetworkConnectionConfig, password: CharArray): Result<Unit> =
        Result.failure(UnsupportedOperationException("Password locations are unavailable on this execution host"))
    suspend fun status(config: NetworkConnectionConfig): Result<StorageContentStatus>
    suspend fun create(config: NetworkConnectionConfig): Result<Unit>
    suspend fun exportRecovery(config: NetworkConnectionConfig, password: CharArray): Result<ByteArray>
    suspend fun verifyBackupAndEnable(config: NetworkConnectionConfig, backup: ByteArray, password: CharArray): Result<Unit>
    suspend fun importRecovery(config: NetworkConnectionConfig, backup: ByteArray, password: CharArray): Result<Unit>
    suspend fun unlock(config: NetworkConnectionConfig): Result<Unit>
    suspend fun lock(config: NetworkConnectionConfig): Result<Unit>
    suspend fun setEnabled(config: NetworkConnectionConfig, enabled: Boolean): Result<Unit>
    suspend fun createStage(taskKey: Long, entryId: String): Result<String>
    /** Returns an identity only for a registered staging file owned by this task and execution session. */
    suspend fun stageIdentity(taskKey: Long, path: String): Result<String> =
        Result.failure(UnsupportedOperationException("Staging identity is unavailable on this execution host"))
    /** Finish an admitted provider write that may have replaced its registered private file. */
    suspend fun completeStageWrite(taskKey: Long, path: String, expectedIdentity: String): Result<Unit> =
        Result.failure(UnsupportedOperationException("Staging writes are unavailable on this execution host"))
    suspend fun prepareUpload(config: NetworkConnectionConfig, taskKey: Long, entryId: String,
        source: FileSimpleInfo, stagedPath: String): Result<StoragePreparedContentUpload?>
    suspend fun beforeMutation(config: NetworkConnectionConfig, path: StoragePath): Result<Unit> = Result.success(Unit)
    suspend fun beginUpload(config: NetworkConnectionConfig, taskKey: Long, entryId: String, target: StoragePath): Result<Unit> =
        Result.failure(UnsupportedOperationException("Content classification is unavailable on this execution host"))
    suspend fun publishDownload(config: NetworkConnectionConfig, taskKey: Long, file: FileSimpleInfo,
        stagedPath: String, destinationPath: String): Result<Boolean>
    suspend fun recordUploaded(config: NetworkConnectionConfig, taskKey: Long, entryId: String, entry: NetworkFileEntry): Result<Unit>
    suspend fun logicalFile(config: NetworkConnectionConfig, file: FileSimpleInfo): Result<FileSimpleInfo?>
    suspend fun cleanup(taskKey: Long): Result<Unit>
}

expect object StorageContentPlatform : StorageContentOperations

internal object UnsupportedStorageContentOperations : StorageContentOperations {
    private fun <T> unavailable(): Result<T> = Result.failure(UnsupportedOperationException(
        "Content encryption is unavailable on this execution host"))
    override suspend fun status(config: NetworkConnectionConfig) = Result.success(
        StorageContentStatus(scope = "", available = false))
    override suspend fun create(config: NetworkConnectionConfig): Result<Unit> = unavailable()
    override suspend fun exportRecovery(config: NetworkConnectionConfig, password: CharArray): Result<ByteArray> = unavailable()
    override suspend fun verifyBackupAndEnable(config: NetworkConnectionConfig, backup: ByteArray, password: CharArray): Result<Unit> = unavailable()
    override suspend fun importRecovery(config: NetworkConnectionConfig, backup: ByteArray, password: CharArray): Result<Unit> = unavailable()
    override suspend fun unlock(config: NetworkConnectionConfig): Result<Unit> = unavailable()
    override suspend fun lock(config: NetworkConnectionConfig): Result<Unit> = unavailable()
    override suspend fun setEnabled(config: NetworkConnectionConfig, enabled: Boolean): Result<Unit> = unavailable()
    override suspend fun createStage(taskKey: Long, entryId: String): Result<String> = unavailable()
    override suspend fun prepareUpload(config: NetworkConnectionConfig, taskKey: Long, entryId: String,
        source: FileSimpleInfo, stagedPath: String): Result<StoragePreparedContentUpload?> = Result.success(null)
    override suspend fun beginUpload(config: NetworkConnectionConfig, taskKey: Long, entryId: String, target: StoragePath) = Result.success(Unit)
    override suspend fun publishDownload(config: NetworkConnectionConfig, taskKey: Long, file: FileSimpleInfo,
        stagedPath: String, destinationPath: String): Result<Boolean> = unavailable()
    override suspend fun recordUploaded(config: NetworkConnectionConfig, taskKey: Long, entryId: String, entry: NetworkFileEntry) = Result.success(Unit)
    override suspend fun logicalFile(config: NetworkConnectionConfig, file: FileSimpleInfo): Result<FileSimpleInfo?> = Result.success(null)
    override suspend fun cleanup(taskKey: Long) = Result.success(Unit)
}

private fun <T> unavailablePasswordOperation(config: NetworkConnectionConfig): Result<T?> =
    if (config.extras.contentEncryption == null) Result.success(null)
    else Result.failure(StorageContentException(StorageContentErrorCode.Unavailable,
        "Password location operations are unavailable on this execution host"))
