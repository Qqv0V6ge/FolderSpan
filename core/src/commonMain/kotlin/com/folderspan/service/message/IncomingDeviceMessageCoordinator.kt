package com.folderspan.service.message

import kotlin.time.Clock
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class IncomingDeviceMessageCoordinator(
    private val store: DeviceMessageStore,
    private val endpointRegistry: DeviceMessageLiveEndpointRegistry,
    private val isConversationOpen: (String) -> Boolean = { false },
    private val onIncomingPersisted: (String) -> Unit = {},
    private val nowMillis: () -> Long = { Clock.System.now().toEpochMilliseconds() },
    private val transferTimeoutMillis: Long = DEVICE_MESSAGE_RECEIVE_TIMEOUT_MILLIS,
    private val maxConcurrentBodiesPerPeer: Int = DEVICE_MESSAGE_MAX_CONCURRENT_BODIES_PER_PEER,
) {
    private val mutex = Mutex()
    private val accumulators = mutableMapOf<IncomingTransferKey, IncomingAccumulator>()
    private val rateLimits = mutableMapOf<String, TokenBucket>()

    suspend fun begin(
        endpointIdentity: DeviceMessageEndpointIdentity,
        metadata: DeviceMessageMetadata,
    ): DeviceMessageBeginResult = endpointRegistry.withLiveEndpoint(endpointIdentity) {
        val now = nowMillis()
        val validatedMetadata = validateDeviceMessageMetadata(metadata, now)
        mutex.withLock {
            cleanupExpiredLocked(now)
            val existing = store.find(
                peerDeviceId = endpointIdentity.peerDeviceId,
                messageId = validatedMetadata.messageId,
                direction = DeviceMessageDirection.Incoming,
            )
            if (existing != null) {
                verifyPersistentDuplicate(existing, validatedMetadata)
                return@withLock DeviceMessageBeginResult.AlreadyPersisted(
                    DeviceMessageReceipt(
                        messageId = existing.messageId,
                        receivedAtEpochMillis = existing.receivedAtMillis ?: existing.createdAtMillis,
                    ),
                )
            }
            val key = IncomingTransferKey(endpointIdentity, validatedMetadata.messageId)
            if (accumulators.containsKey(key)) {
                throw DeviceMessageTransferException(DeviceMessageTransferError.TransferAlreadyActive)
            }
            val activeForPeer = accumulators.keys.count { item ->
                item.endpointIdentity.peerDeviceId == endpointIdentity.peerDeviceId
            }
            if (activeForPeer >= maxConcurrentBodiesPerPeer) {
                throw DeviceMessageTransferException(DeviceMessageTransferError.ConcurrentLimit)
            }
            val bucket = rateLimits.getOrPut(endpointIdentity.peerDeviceId) {
                TokenBucket(nowEpochMillis = now)
            }
            if (!bucket.tryConsume(now)) {
                throw DeviceMessageTransferException(DeviceMessageTransferError.RateLimited)
            }
            accumulators[key] = IncomingAccumulator(
                metadata = validatedMetadata,
                startedAtMillis = now,
                updatedAtMillis = now,
            )
            DeviceMessageBeginResult.Ready
        }
    }

    suspend fun appendChunk(
        endpointIdentity: DeviceMessageEndpointIdentity,
        messageId: String,
        chunkIndex: Int,
        chunk: ByteArray,
    ) {
        endpointRegistry.withLiveEndpoint(endpointIdentity) {
            val now = nowMillis()
            mutex.withLock {
                cleanupExpiredLocked(now)
                val key = IncomingTransferKey(endpointIdentity, messageId)
                val accumulator = accumulators[key]
                    ?: throw DeviceMessageTransferException(DeviceMessageTransferError.TransferNotActive)
                if (chunk.isEmpty() || chunk.size > DEVICE_MESSAGE_MAX_CHUNK_BYTES) {
                    accumulators.remove(key)
                    throw DeviceMessageTransferException(DeviceMessageTransferError.ChunkTooLarge)
                }
                if (chunkIndex != accumulator.nextChunkIndex) {
                    accumulators.remove(key)
                    throw DeviceMessageTransferException(DeviceMessageTransferError.ChunkOutOfOrder)
                }
                if (accumulator.receivedBytes + chunk.size > accumulator.metadata.utf8Length ||
                    accumulator.receivedBytes + chunk.size > DEVICE_MESSAGE_MAX_BODY_BYTES
                ) {
                    accumulators.remove(key)
                    throw DeviceMessageTransferException(DeviceMessageTransferError.BodyOverflow)
                }
                accumulator.chunks += chunk.copyOf()
                accumulator.receivedBytes += chunk.size
                accumulator.nextChunkIndex += 1
                accumulator.updatedAtMillis = now
            }
        }
    }

    suspend fun commit(
        endpointIdentity: DeviceMessageEndpointIdentity,
        messageId: String,
    ): DeviceMessageReceipt = endpointRegistry.withLiveEndpoint(endpointIdentity) {
        val now = nowMillis()
        val accumulator = mutex.withLock {
            cleanupExpiredLocked(now)
            accumulators.remove(IncomingTransferKey(endpointIdentity, messageId))
                ?: throw DeviceMessageTransferException(DeviceMessageTransferError.TransferNotActive)
        }
        val bodyBytes = accumulator.combine()
        val validated = validateDeviceMessageBody(accumulator.metadata, bodyBytes, now)
        val persistence = store.persistIncoming(
            peerDeviceId = endpointIdentity.peerDeviceId,
            message = validated,
            receivedAtMillis = now,
            isRead = isConversationOpen(endpointIdentity.peerDeviceId),
        )
        runCatching { onIncomingPersisted(endpointIdentity.peerDeviceId) }
        DeviceMessageReceipt(
            messageId = persistence.message.messageId,
            receivedAtEpochMillis = persistence.message.receivedAtMillis ?: now,
        )
    }

    suspend fun discardEndpoint(endpointIdentity: DeviceMessageEndpointIdentity): Int = mutex.withLock {
        val keys = accumulators.keys.filter { it.endpointIdentity == endpointIdentity }
        keys.forEach(accumulators::remove)
        keys.size
    }

    suspend fun discardTransfer(
        endpointIdentity: DeviceMessageEndpointIdentity,
        messageId: String,
    ): Boolean = mutex.withLock {
        accumulators.remove(IncomingTransferKey(endpointIdentity, messageId)) != null
    }

    suspend fun hasActiveTransfer(
        endpointIdentity: DeviceMessageEndpointIdentity,
        messageId: String,
    ): Boolean = mutex.withLock {
        cleanupExpiredLocked(nowMillis())
        accumulators.containsKey(IncomingTransferKey(endpointIdentity, messageId))
    }

    suspend fun cleanupExpired(): Int = mutex.withLock {
        cleanupExpiredLocked(nowMillis())
    }

    suspend fun activeTransferCount(peerDeviceId: String? = null): Int = mutex.withLock {
        if (peerDeviceId == null) {
            accumulators.size
        } else {
            accumulators.keys.count { it.endpointIdentity.peerDeviceId == peerDeviceId }
        }
    }

    private fun cleanupExpiredLocked(now: Long): Int {
        val expired = accumulators.filterValues { accumulator ->
            now - accumulator.updatedAtMillis >= transferTimeoutMillis
        }.keys
        expired.forEach(accumulators::remove)
        return expired.size
    }
}

private data class IncomingTransferKey(
    val endpointIdentity: DeviceMessageEndpointIdentity,
    val messageId: String,
)

private data class IncomingAccumulator(
    val metadata: DeviceMessageMetadata,
    val startedAtMillis: Long,
    var updatedAtMillis: Long,
    val chunks: MutableList<ByteArray> = mutableListOf(),
    var receivedBytes: Int = 0,
    var nextChunkIndex: Int = 0,
) {
    fun combine(): ByteArray {
        val body = ByteArray(receivedBytes)
        var offset = 0
        chunks.forEach { chunk ->
            chunk.copyInto(body, destinationOffset = offset)
            offset += chunk.size
        }
        return body
    }
}

private class TokenBucket(
    nowEpochMillis: Long,
) {
    private var tokens = DEVICE_MESSAGE_RATE_LIMIT_BURST.toDouble()
    private var lastRefillAtMillis = nowEpochMillis

    fun tryConsume(nowEpochMillis: Long): Boolean {
        val elapsedMillis = (nowEpochMillis - lastRefillAtMillis).coerceAtLeast(0L)
        if (elapsedMillis > 0L) {
            val tokensPerMillis = DEVICE_MESSAGE_RATE_LIMIT_PER_MINUTE.toDouble() / 60_000.0
            tokens = (tokens + elapsedMillis * tokensPerMillis)
                .coerceAtMost(DEVICE_MESSAGE_RATE_LIMIT_BURST.toDouble())
            lastRefillAtMillis = nowEpochMillis
        }
        if (tokens < 1.0) return false
        tokens -= 1.0
        return true
    }
}

private fun verifyPersistentDuplicate(
    stored: DeviceStoredMessage,
    metadata: DeviceMessageMetadata,
) {
    if (stored.sentAtMillis != metadata.sentAtEpochMillis ||
        stored.bodyUtf8Length != metadata.utf8Length ||
        stored.bodySha256 != metadata.sha256
    ) {
        throw DeviceMessageTransferException(DeviceMessageTransferError.DuplicateConflict)
    }
}
