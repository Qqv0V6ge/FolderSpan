package com.folderspan.service.network

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
enum class StorageFeatureAvailability { AVAILABLE, PERMISSION_DENIED, AUTHORIZATION_REQUIRED, UNSUPPORTED }

@Serializable
enum class StorageAdvancedFeature {
    RESUMABLE_UPLOAD, LIST_VERSIONS, READ_VERSION, RESTORE_VERSION,
    LIST_SHARES, CREATE_LINK, INVITE_USER, REVOKE_SHARE,
}

class StorageFeatureException(val availability: StorageFeatureAvailability) : Exception(
    when (availability) {
        StorageFeatureAvailability.AVAILABLE -> "Storage operation is unavailable"
        StorageFeatureAvailability.PERMISSION_DENIED -> "Storage account has insufficient permission"
        StorageFeatureAvailability.AUTHORIZATION_REQUIRED -> "Storage account requires authorization"
        StorageFeatureAvailability.UNSUPPORTED -> "Storage backend does not support this operation"
    }
)

@Serializable
data class StorageUploadConstraints(
    val alignment: Int = 1,
    val maxChunkSize: Int = 4 * 1024 * 1024,
    val maxFileSize: Long = Long.MAX_VALUE,
) {
    init {
        require(alignment > 0 && maxChunkSize in alignment..16 * 1024 * 1024 && maxFileSize >= 0)
    }

    fun validate(size: Long, offset: Long, chunkSize: Int) {
        require(size in 0..maxFileSize && offset in 0..size) { "Invalid storage upload length or offset" }
        require(chunkSize in 0..maxChunkSize && chunkSize.toLong() <= size - offset) { "Invalid storage upload chunk" }
        require(chunkSize > 0 || size == 0L) { "Storage upload chunk is empty" }
        require(offset % alignment == 0L && (offset + chunkSize == size || chunkSize % alignment == 0)) {
            "Storage upload chunk is not aligned"
        }
    }
}

@Serializable
data class StorageAdvancedCapabilities(
    val features: Map<StorageAdvancedFeature, StorageFeatureAvailability> = emptyMap(),
    val upload: StorageUploadConstraints? = null,
    val shareRoles: Set<String> = emptySet(),
    val shareExpiry: Boolean = false,
) {
    fun availability(feature: StorageAdvancedFeature) = features[feature] ?: StorageFeatureAvailability.UNSUPPORTED

    fun require(feature: StorageAdvancedFeature) {
        val status = availability(feature)
        if (status != StorageFeatureAvailability.AVAILABLE) throw StorageFeatureException(status)
    }

    fun validateShare(request: StorageShareRequest) {
        require(if (request.kind == StorageShareKind.LINK) StorageAdvancedFeature.CREATE_LINK else StorageAdvancedFeature.INVITE_USER)
        kotlin.require(request.role in shareRoles) { "Storage sharing role is unsupported" }
        kotlin.require(request.expiresAt == null || shareExpiry) { "Storage sharing expiry is unsupported" }
    }
}

@Serializable
enum class StorageUploadStatus { UPLOADING, COMPLETED, EXPIRED, UNCERTAIN, CANCELLED }

// Session secrets belong to protected execution-host storage, never ordinary task serialization.
@ConsistentCopyVisibility
data class StorageUploadSession internal constructor(
    val backend: String,
    val target: StoragePath,
    val size: Long,
    val confirmedOffset: Long,
    val status: StorageUploadStatus = StorageUploadStatus.UPLOADING,
    val entry: NetworkFileEntry? = null,
    internal val secret: String,
    internal val binding: String,
) {
    init { require(size >= 0 && confirmedOffset in 0..size) }
    override fun toString() = "StorageUploadSession(backend=$backend, confirmedOffset=$confirmedOffset, status=$status)"
}

class StorageUploadUncertainException(val session: StorageUploadSession) : Exception("Storage upload completion is uncertain")

@Serializable
data class StorageVersion(
    val file: StoragePath,
    val id: String,
    val size: Long? = null,
    val updatedDate: Long? = null,
    val connectionBinding: String = "",
)

@Serializable
data class StoragePage<T>(val entries: List<T>, val nextCursor: String? = null)

@Serializable
enum class StorageShareKind { LINK, INVITATION }

@Serializable
data class StorageShareRequest(
    val kind: StorageShareKind,
    val role: String,
    val recipient: String? = null,
    val expiresAt: Long? = null,
) {
    init {
        require(role.isNotBlank() && role.length <= 128 && role.none(Char::isISOControl))
        require((kind == StorageShareKind.INVITATION) == !recipient.isNullOrBlank()) { "Invalid storage sharing recipient" }
        require(recipient == null || (recipient.length <= 1024 && recipient.none(Char::isISOControl)))
        require(expiresAt == null || expiresAt > 0)
    }

    override fun toString() = "StorageShareRequest(kind=$kind, role=$role)"
}

@Serializable
data class StorageShare(
    val file: StoragePath,
    val id: String,
    val kind: StorageShareKind,
    val role: String,
    val recipient: String? = null,
    val expiresAt: Long? = null,
    val url: String? = null,
    val canRevoke: Boolean = true,
    val connectionBinding: String = "",
) {
    override fun toString() = "StorageShare(kind=$kind, role=$role)"
}

internal suspend fun <T> StorageProvider.unavailableAdvancedOperation(path: StoragePath, feature: StorageAdvancedFeature): Result<T> =
    storageResult {
        advancedCapabilities(path).getOrThrow().require(feature)
        throw StorageFeatureException(StorageFeatureAvailability.UNSUPPORTED)
    }

/** Account and configured namespace identity only; never authorization or runtime session secrets. */
internal fun storageConnectionBinding(config: NetworkConnectionConfig, rootId: String = config.extras.cloud.rootId): String =
    Json.encodeToString(listOf(config.protocol, config.extras.cloud.accountId,
        config.extras.cloud.connectionId, rootId, config.extras.cloud.libraryId))

/** Only call after the provider has identified a newly created grant. */
internal suspend fun compensateStorageShare(failure: Throwable, revoke: suspend () -> Unit): Nothing {
    val cleanupFailure = try {
        withContext(NonCancellable) { withTimeout(5_000) { revoke() } }
        null
    } catch (_: Throwable) {
        IllegalStateException("Storage share revocation could not be confirmed; refresh the sharing state")
    }
    if (cleanupFailure != null) {
        if (failure is CancellationException) failure.addSuppressed(cleanupFailure)
        else {
            val uncertain = IllegalStateException(
                "Storage sharing validation failed and revocation could not be confirmed; refresh the sharing state", failure)
            uncertain.addSuppressed(cleanupFailure)
            throw uncertain
        }
    }
    throw failure
}
