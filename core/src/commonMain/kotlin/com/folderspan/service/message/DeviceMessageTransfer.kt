package com.folderspan.service.message

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

const val DEVICE_MESSAGE_MAX_CHUNK_BYTES = 64 * 1024
const val DEVICE_MESSAGE_RECEIVE_TIMEOUT_MILLIS = 2L * 60L * 1_000L
const val DEVICE_MESSAGE_MAX_CONCURRENT_BODIES_PER_PEER = 4
const val DEVICE_MESSAGE_RATE_LIMIT_PER_MINUTE = 30
const val DEVICE_MESSAGE_RATE_LIMIT_BURST = 10

enum class DeviceMessageTransport {
    Session,
}

data class DeviceMessageEndpointIdentity(
    val peerDeviceId: String,
    val transport: DeviceMessageTransport,
    val connectionId: String,
)

interface DeviceMessageClient {
    val endpointIdentity: DeviceMessageEndpointIdentity

    val maxChunkBytes: Int

    suspend fun send(
        metadata: DeviceMessageMetadata,
        bodyChunks: Flow<ByteArray>,
    ): Result<DeviceMessageReceipt>
}

data class DeviceMessageLiveEndpoint(
    val identity: DeviceMessageEndpointIdentity,
    val client: DeviceMessageClient,
    val isAuthorized: () -> Boolean,
)

enum class DeviceMessageTransferError {
    Offline,
    Unauthorized,
    ReceiptMismatch,
    DuplicateConflict,
    RateLimited,
    ConcurrentLimit,
    TransferAlreadyActive,
    TransferNotActive,
    ChunkTooLarge,
    ChunkOutOfOrder,
    BodyOverflow,
    TransferTimedOut,
    TransportFailure,
}

class DeviceMessageTransferException(
    val error: DeviceMessageTransferError,
    val deliveryUncertain: Boolean = false,
    cause: Throwable? = null,
) : IllegalStateException(error.name, cause)

sealed interface DeviceMessageBeginResult {
    data object Ready : DeviceMessageBeginResult

    data class AlreadyPersisted(
        val receipt: DeviceMessageReceipt,
    ) : DeviceMessageBeginResult
}

fun chunkDeviceMessageBody(
    bodyBytes: ByteArray,
    requestedChunkBytes: Int,
): Flow<ByteArray> {
    require(requestedChunkBytes > 0) { "Chunk size must be positive" }
    val chunkBytes = requestedChunkBytes.coerceAtMost(DEVICE_MESSAGE_MAX_CHUNK_BYTES)
    return flow {
        var offset = 0
        while (offset < bodyBytes.size) {
            val end = (offset + chunkBytes).coerceAtMost(bodyBytes.size)
            emit(bodyBytes.copyOfRange(offset, end))
            offset = end
        }
    }
}
