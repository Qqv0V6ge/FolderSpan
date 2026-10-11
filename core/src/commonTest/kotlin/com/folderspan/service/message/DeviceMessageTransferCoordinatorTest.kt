package com.folderspan.service.message

import strings.AppStrings

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DeviceMessageTransferCoordinatorTest {
    @Test
    fun loopbackDeliversSmallAndOneMiBMessagesInBoundedChunks() = runTest {
        val fixture = createLoopbackFixture(chunkBytes = 16 * 1024)
        val largeBody = "a".repeat(DEVICE_MESSAGE_MAX_BODY_BYTES)

        val small = fixture.service.send(ENDPOINT, "hello", "small-message-000001").getOrThrow()
        val large = fixture.service.send(ENDPOINT, largeBody, "large-message-000001").getOrThrow()

        assertEquals(DeviceMessageStatus.Delivered, small.status)
        assertEquals(DeviceMessageStatus.Delivered, large.status)
        assertEquals(listOf(largeBody, "hello"), fixture.receiverStore.page(PEER, 10).map(DeviceStoredMessage::body))
        assertTrue(fixture.client.chunkCount > 1)
        assertTrue(fixture.client.largestChunkBytes <= 16 * 1024)
    }

    @Test
    fun multibyteCharactersMayCrossChunksAndTextRemainsLiteral() = runTest {
        val fixture = createLoopbackFixture(chunkBytes = 2)
        val body = AppStrings.ui_test_device_message_transfer_coordinator_literal

        fixture.service.send(ENDPOINT, body, "multibyte-message-01").getOrThrow()

        assertEquals(body, fixture.receiverStore.page(PEER, 10).single().body)
    }

    @Test
    fun offlineSendCreatesNoOutgoingRecord() = runTest {
        val store = FakeDeviceMessageStore()
        val service = DeviceMessageService(store, DeviceMessageLiveEndpointRegistry()) { NOW }

        val failure = service.send(ENDPOINT, "offline", "offline-message-0001").exceptionOrNull()

        assertEquals(DeviceMessageTransferError.Offline, assertIs<DeviceMessageTransferException>(failure).error)
        assertEquals(0, store.outgoingInsertCount)
        assertEquals(0L, store.count(PEER))
    }

    @Test
    fun uncertainReceiptRequiresExplicitRetryAndReceiverDeduplicates() = runTest {
        val fixture = createLoopbackFixture(chunkBytes = 4)
        fixture.client.failOnceAfterCommit = true
        val messageId = "explicit-retry-message-01"

        val first = fixture.service.send(ENDPOINT, "retry me", messageId)

        assertTrue(first.isFailure)
        assertEquals(
            DeviceMessageStatus.Unconfirmed,
            fixture.senderStore.find(PEER, messageId, DeviceMessageDirection.Outgoing)?.status,
        )
        assertEquals(1L, fixture.receiverStore.count(PEER))

        val retried = fixture.service.retry(ENDPOINT, messageId).getOrThrow()

        assertEquals(DeviceMessageStatus.Delivered, retried.status)
        assertEquals(1L, fixture.receiverStore.count(PEER))
        assertEquals(2, fixture.client.sendCount)
    }

    @Test
    fun outOfOrderTruncatedChecksumAndOversizedChunksNeverEnterHistory() = runTest {
        val fixture = createIncomingFixture()
        val valid = prepareDeviceMessage("hello", "ordered-message-00001", NOW, NOW)

        fixture.coordinator.begin(ENDPOINT, valid.metadata)
        assertTransferError(DeviceMessageTransferError.ChunkOutOfOrder) {
            fixture.coordinator.appendChunk(ENDPOINT, valid.metadata.messageId, 1, valid.bodyBytes)
        }

        val truncated = prepareDeviceMessage("world", "truncated-message-001", NOW, NOW)
        fixture.coordinator.begin(ENDPOINT, truncated.metadata)
        fixture.coordinator.appendChunk(ENDPOINT, truncated.metadata.messageId, 0, byteArrayOf('w'.code.toByte()))
        assertFailsWith<DeviceMessageValidationException> {
            fixture.coordinator.commit(ENDPOINT, truncated.metadata.messageId)
        }

        val checksum = prepareDeviceMessage("valid", "checksum-message-00001", NOW, NOW)
        fixture.coordinator.begin(ENDPOINT, checksum.metadata.copy(sha256 = "0".repeat(64)))
        fixture.coordinator.appendChunk(ENDPOINT, checksum.metadata.messageId, 0, checksum.bodyBytes)
        assertFailsWith<DeviceMessageValidationException> {
            fixture.coordinator.commit(ENDPOINT, checksum.metadata.messageId)
        }

        val oversized = prepareDeviceMessage("body", "chunk-limit-message-01", NOW, NOW)
        fixture.coordinator.begin(ENDPOINT, oversized.metadata)
        assertTransferError(DeviceMessageTransferError.ChunkTooLarge) {
            fixture.coordinator.appendChunk(
                ENDPOINT,
                oversized.metadata.messageId,
                0,
                ByteArray(DEVICE_MESSAGE_MAX_CHUNK_BYTES + 1),
            )
        }

        assertEquals(0L, fixture.store.count(PEER))
        assertEquals(0, fixture.coordinator.activeTransferCount())
    }

    @Test
    fun timeoutDisconnectAndRevocationDiscardPartialBodies() = runTest {
        var now = NOW
        var authorized = true
        val fixture = createIncomingFixture(nowMillis = { now }, authorization = { authorized })
        val timedOut = prepareDeviceMessage("timeout", "timeout-message-000001", NOW, NOW)
        fixture.coordinator.begin(ENDPOINT, timedOut.metadata)
        fixture.coordinator.appendChunk(ENDPOINT, timedOut.metadata.messageId, 0, byteArrayOf('t'.code.toByte()))

        now += DEVICE_MESSAGE_RECEIVE_TIMEOUT_MILLIS
        assertEquals(1, fixture.coordinator.cleanupExpired())
        assertTransferError(DeviceMessageTransferError.TransferNotActive) {
            fixture.coordinator.commit(ENDPOINT, timedOut.metadata.messageId)
        }

        val disconnected = prepareDeviceMessage("disconnect", "disconnect-message-01", NOW, NOW)
        fixture.coordinator.begin(ENDPOINT, disconnected.metadata)
        assertEquals(1, fixture.coordinator.discardEndpoint(ENDPOINT))
        fixture.registry.unregister(ENDPOINT)

        authorized = false
        assertTransferError(DeviceMessageTransferError.Offline) {
            fixture.coordinator.appendChunk(ENDPOINT, disconnected.metadata.messageId, 0, disconnected.bodyBytes)
        }
        assertEquals(0L, fixture.store.count(PEER))
    }

    @Test
    fun receiveRateLimiterAllowsBurstTenThenRefillsAtThirtyPerMinute() = runTest {
        var now = NOW
        val fixture = createIncomingFixture(nowMillis = { now })
        repeat(DEVICE_MESSAGE_RATE_LIMIT_BURST) { index ->
            val message = prepareDeviceMessage("$index", rateMessageId(index), NOW, NOW)
            fixture.coordinator.begin(ENDPOINT, message.metadata)
            fixture.coordinator.appendChunk(ENDPOINT, message.metadata.messageId, 0, message.bodyBytes)
            fixture.coordinator.commit(ENDPOINT, message.metadata.messageId)
        }

        val rejected = prepareDeviceMessage("rejected", rateMessageId(99), NOW, NOW)
        assertTransferError(DeviceMessageTransferError.RateLimited) {
            fixture.coordinator.begin(ENDPOINT, rejected.metadata)
        }

        now += 2_000L
        val refilled = prepareDeviceMessage("refilled", rateMessageId(100), NOW, NOW)
        assertEquals(DeviceMessageBeginResult.Ready, fixture.coordinator.begin(ENDPOINT, refilled.metadata))
    }

    @Test
    fun concurrentBodyLimitIsPerPeer() = runTest {
        val fixture = createIncomingFixture()
        repeat(DEVICE_MESSAGE_MAX_CONCURRENT_BODIES_PER_PEER) { index ->
            val message = prepareDeviceMessage("$index", concurrentMessageId(index), NOW, NOW)
            fixture.coordinator.begin(ENDPOINT, message.metadata)
        }

        val excess = prepareDeviceMessage("excess", concurrentMessageId(99), NOW, NOW)
        assertTransferError(DeviceMessageTransferError.ConcurrentLimit) {
            fixture.coordinator.begin(ENDPOINT, excess.metadata)
        }
        assertEquals(
            DEVICE_MESSAGE_MAX_CONCURRENT_BODIES_PER_PEER,
            fixture.coordinator.activeTransferCount(PEER),
        )
    }

    @Test
    fun storageFailureReturnsNoReceiptAndLeavesNoHistory() = runTest {
        val store = FakeDeviceMessageStore().apply { failIncomingPersistence = true }
        val fixture = createIncomingFixture(store = store)
        val message = prepareDeviceMessage("disk full", "storage-message-00001", NOW, NOW)
        fixture.coordinator.begin(ENDPOINT, message.metadata)
        fixture.coordinator.appendChunk(ENDPOINT, message.metadata.messageId, 0, message.bodyBytes)

        assertFailsWith<DeviceMessagePersistenceException> {
            fixture.coordinator.commit(ENDPOINT, message.metadata.messageId)
        }

        assertEquals(0L, store.count(PEER))
        assertEquals(0, fixture.coordinator.activeTransferCount())
    }

    @Test
    fun observerFailuresDoNotSuppressPersistedReceiptOrDeliveryStatus() = runTest {
        val receiverStore = FakeDeviceMessageStore()
        val receiverRegistry = DeviceMessageLiveEndpointRegistry()
        val coordinator = IncomingDeviceMessageCoordinator(
            store = receiverStore,
            endpointRegistry = receiverRegistry,
            onIncomingPersisted = { error("observer failed") },
            nowMillis = { NOW + 1 },
        )
        val client = LoopbackDeviceMessageClient(ENDPOINT, 4, coordinator)
        receiverRegistry.register(DeviceMessageLiveEndpoint(ENDPOINT, client) { true })
        val senderRegistry = DeviceMessageLiveEndpointRegistry().also { registry ->
            registry.register(DeviceMessageLiveEndpoint(ENDPOINT, client) { true })
        }
        val service = DeviceMessageService(
            store = FakeDeviceMessageStore(),
            endpointRegistry = senderRegistry,
            onOutgoingChanged = { error("observer failed") },
            nowMillis = { NOW },
        )

        val delivered = service.send(ENDPOINT, "persist despite observer", "observer-message-00001").getOrThrow()

        assertEquals(DeviceMessageStatus.Delivered, delivered.status)
        assertEquals(1L, receiverStore.count(PEER))
    }

    @Test
    fun persistedDuplicateReturnsReceiptWithoutAllocatingOrCountingAgainstRateLimit() = runTest {
        val fixture = createIncomingFixture()
        val message = prepareDeviceMessage("once", "duplicate-message-0001", NOW, NOW)
        fixture.coordinator.begin(ENDPOINT, message.metadata)
        fixture.coordinator.appendChunk(ENDPOINT, message.metadata.messageId, 0, message.bodyBytes)
        val firstReceipt = fixture.coordinator.commit(ENDPOINT, message.metadata.messageId)

        repeat(20) {
            val result = fixture.coordinator.begin(ENDPOINT, message.metadata)
            assertEquals(firstReceipt, assertIs<DeviceMessageBeginResult.AlreadyPersisted>(result).receipt)
        }

        assertEquals(1L, fixture.store.count(PEER))
        assertEquals(0, fixture.coordinator.activeTransferCount())
    }

    private suspend fun createLoopbackFixture(chunkBytes: Int): LoopbackFixture {
        val senderStore = FakeDeviceMessageStore()
        val receiverStore = FakeDeviceMessageStore()
        val receiverRegistry = DeviceMessageLiveEndpointRegistry()
        val coordinator = IncomingDeviceMessageCoordinator(
            store = receiverStore,
            endpointRegistry = receiverRegistry,
            nowMillis = { NOW + 1 },
        )
        val client = LoopbackDeviceMessageClient(ENDPOINT, chunkBytes, coordinator)
        receiverRegistry.register(DeviceMessageLiveEndpoint(ENDPOINT, client) { true })
        val senderRegistry = DeviceMessageLiveEndpointRegistry()
        senderRegistry.register(DeviceMessageLiveEndpoint(ENDPOINT, client) { true })
        return LoopbackFixture(
            senderStore = senderStore,
            receiverStore = receiverStore,
            client = client,
            service = DeviceMessageService(senderStore, senderRegistry) { NOW },
        )
    }

    private suspend fun createIncomingFixture(
        store: FakeDeviceMessageStore = FakeDeviceMessageStore(),
        nowMillis: () -> Long = { NOW },
        authorization: () -> Boolean = { true },
    ): IncomingFixture {
        val registry = DeviceMessageLiveEndpointRegistry()
        val client = NoOpDeviceMessageClient(ENDPOINT)
        registry.register(DeviceMessageLiveEndpoint(ENDPOINT, client, authorization))
        return IncomingFixture(
            store = store,
            registry = registry,
            coordinator = IncomingDeviceMessageCoordinator(
                store = store,
                endpointRegistry = registry,
                nowMillis = nowMillis,
            ),
        )
    }

    private suspend fun assertTransferError(
        expected: DeviceMessageTransferError,
        block: suspend () -> Unit,
    ) {
        val failure = assertFailsWith<DeviceMessageTransferException> { block() }
        assertEquals(expected, failure.error)
    }

    private fun rateMessageId(index: Int): String = "rate-message-${index.toString().padStart(8, '0')}"

    private fun concurrentMessageId(index: Int): String = "concurrent-${index.toString().padStart(12, '0')}"

    private companion object {
        const val NOW = 1_700_000_000_000L
        const val PEER = "peer-device-1"
        val ENDPOINT = DeviceMessageEndpointIdentity(
            peerDeviceId = PEER,
            transport = DeviceMessageTransport.Session,
            connectionId = "connection-1",
        )
    }
}

private data class LoopbackFixture(
    val senderStore: FakeDeviceMessageStore,
    val receiverStore: FakeDeviceMessageStore,
    val client: LoopbackDeviceMessageClient,
    val service: DeviceMessageService,
)

private data class IncomingFixture(
    val store: FakeDeviceMessageStore,
    val registry: DeviceMessageLiveEndpointRegistry,
    val coordinator: IncomingDeviceMessageCoordinator,
)

private class LoopbackDeviceMessageClient(
    override val endpointIdentity: DeviceMessageEndpointIdentity,
    override val maxChunkBytes: Int,
    private val coordinator: IncomingDeviceMessageCoordinator,
) : DeviceMessageClient {
    var failOnceAfterCommit = false
    var sendCount = 0
        private set
    var chunkCount = 0
        private set
    var largestChunkBytes = 0
        private set

    override suspend fun send(
        metadata: DeviceMessageMetadata,
        bodyChunks: Flow<ByteArray>,
    ): Result<DeviceMessageReceipt> = runCatching {
        sendCount += 1
        when (val begin = coordinator.begin(endpointIdentity, metadata)) {
            is DeviceMessageBeginResult.AlreadyPersisted -> begin.receipt
            DeviceMessageBeginResult.Ready -> {
                var index = 0
                bodyChunks.collect { chunk ->
                    chunkCount += 1
                    largestChunkBytes = maxOf(largestChunkBytes, chunk.size)
                    coordinator.appendChunk(endpointIdentity, metadata.messageId, index, chunk)
                    index += 1
                }
                val receipt = coordinator.commit(endpointIdentity, metadata.messageId)
                if (failOnceAfterCommit) {
                    failOnceAfterCommit = false
                    throw DeviceMessageTransferException(
                        DeviceMessageTransferError.TransportFailure,
                        deliveryUncertain = true,
                    )
                }
                receipt
            }
        }
    }
}

private class NoOpDeviceMessageClient(
    override val endpointIdentity: DeviceMessageEndpointIdentity,
) : DeviceMessageClient {
    override val maxChunkBytes: Int = DEVICE_MESSAGE_MAX_CHUNK_BYTES

    override suspend fun send(
        metadata: DeviceMessageMetadata,
        bodyChunks: Flow<ByteArray>,
    ): Result<DeviceMessageReceipt> = Result.failure(
        DeviceMessageTransferException(DeviceMessageTransferError.TransportFailure),
    )
}

internal class FakeDeviceMessageStore : DeviceMessageStore {
    private val messages = mutableListOf<DeviceStoredMessage>()
    private var nextLocalId = 1L
    var outgoingInsertCount = 0
        private set
    var failIncomingPersistence = false

    override suspend fun insertOutgoing(
        peerDeviceId: String,
        message: ValidatedDeviceMessage,
        createdAtMillis: Long,
    ): DeviceStoredMessage {
        outgoingInsertCount += 1
        val existing = find(peerDeviceId, message.metadata.messageId, DeviceMessageDirection.Outgoing)
        if (existing != null) return existing
        return DeviceStoredMessage(
            localId = nextLocalId++,
            messageId = message.metadata.messageId,
            peerDeviceId = peerDeviceId,
            direction = DeviceMessageDirection.Outgoing,
            body = message.body,
            bodyUtf8Length = message.metadata.utf8Length,
            bodySha256 = message.metadata.sha256,
            sentAtMillis = message.metadata.sentAtEpochMillis,
            receivedAtMillis = null,
            status = DeviceMessageStatus.Sending,
            isRead = true,
            createdAtMillis = createdAtMillis,
        ).also(messages::add)
    }

    override suspend fun updateOutgoingStatus(
        peerDeviceId: String,
        messageId: String,
        status: DeviceMessageStatus,
        receivedAtMillis: Long?,
    ): DeviceStoredMessage {
        val index = messages.indexOfFirst { item ->
            item.peerDeviceId == peerDeviceId &&
                item.messageId == messageId &&
                item.direction == DeviceMessageDirection.Outgoing
        }
        check(index >= 0)
        return messages[index].copy(status = status, receivedAtMillis = receivedAtMillis).also { messages[index] = it }
    }

    override suspend fun prepareExplicitRetry(peerDeviceId: String, messageId: String): DeviceStoredMessage {
        val existing = find(peerDeviceId, messageId, DeviceMessageDirection.Outgoing)
            ?: throw DeviceMessagePersistenceException("missing")
        if (existing.status != DeviceMessageStatus.Failed && existing.status != DeviceMessageStatus.Unconfirmed) {
            throw DeviceMessagePersistenceException("not retryable")
        }
        return updateOutgoingStatus(peerDeviceId, messageId, DeviceMessageStatus.Sending)
    }

    override suspend fun persistIncoming(
        peerDeviceId: String,
        message: ValidatedDeviceMessage,
        receivedAtMillis: Long,
        isRead: Boolean,
    ): IncomingDeviceMessagePersistence {
        if (failIncomingPersistence) throw DeviceMessagePersistenceException("storage failure")
        val existing = find(peerDeviceId, message.metadata.messageId, DeviceMessageDirection.Incoming)
        if (existing != null) return IncomingDeviceMessagePersistence(existing, duplicate = true)
        val stored = DeviceStoredMessage(
            localId = nextLocalId++,
            messageId = message.metadata.messageId,
            peerDeviceId = peerDeviceId,
            direction = DeviceMessageDirection.Incoming,
            body = message.body,
            bodyUtf8Length = message.metadata.utf8Length,
            bodySha256 = message.metadata.sha256,
            sentAtMillis = message.metadata.sentAtEpochMillis,
            receivedAtMillis = receivedAtMillis,
            status = DeviceMessageStatus.Received,
            isRead = isRead,
            createdAtMillis = receivedAtMillis,
        )
        messages += stored
        return IncomingDeviceMessagePersistence(stored, duplicate = false)
    }

    override suspend fun recoverInterruptedSends(): Long {
        var changed = 0L
        messages.indices.forEach { index ->
            if (messages[index].status == DeviceMessageStatus.Sending) {
                messages[index] = messages[index].copy(status = DeviceMessageStatus.Unconfirmed)
                changed += 1
            }
        }
        return changed
    }

    override suspend fun page(peerDeviceId: String, limit: Long, offset: Long): List<DeviceStoredMessage> =
        messages.filter { it.peerDeviceId == peerDeviceId }
            .sortedWith(compareByDescending<DeviceStoredMessage> { it.sentAtMillis }.thenByDescending { it.localId })
            .drop(offset.toInt())
            .take(limit.toInt())

    override suspend fun pageBefore(
        peerDeviceId: String,
        beforeSentAtMillis: Long,
        beforeLocalId: Long,
        limit: Long,
    ): List<DeviceStoredMessage> = page(peerDeviceId, Long.MAX_VALUE.coerceAtMost(Int.MAX_VALUE.toLong())).filter {
        it.sentAtMillis < beforeSentAtMillis ||
            (it.sentAtMillis == beforeSentAtMillis && it.localId < beforeLocalId)
    }.take(limit.toInt())

    override suspend fun conversations(): List<DeviceConversationSummary> = messages
        .groupBy(DeviceStoredMessage::peerDeviceId)
        .map { (peerDeviceId, items) ->
            DeviceConversationSummary(
                peerDeviceId = peerDeviceId,
                lastMessageAtMillis = items.maxOf(DeviceStoredMessage::sentAtMillis),
                unreadCount = items.count { it.direction == DeviceMessageDirection.Incoming && !it.isRead }.toLong(),
            )
        }.sortedByDescending(DeviceConversationSummary::lastMessageAtMillis)

    override suspend fun conversation(peerDeviceId: String): DeviceConversationSummary? =
        conversations().firstOrNull { it.peerDeviceId == peerDeviceId }

    override suspend fun count(peerDeviceId: String): Long = messages.count { it.peerDeviceId == peerDeviceId }.toLong()

    override suspend fun find(
        peerDeviceId: String,
        messageId: String,
        direction: DeviceMessageDirection,
    ): DeviceStoredMessage? = messages.firstOrNull { item ->
        item.peerDeviceId == peerDeviceId && item.messageId == messageId && item.direction == direction
    }

    override suspend fun markConversationRead(peerDeviceId: String) {
        messages.indices.forEach { index ->
            if (messages[index].peerDeviceId == peerDeviceId && messages[index].direction == DeviceMessageDirection.Incoming) {
                messages[index] = messages[index].copy(isRead = true)
            }
        }
    }

    override suspend fun deleteConversation(peerDeviceId: String) {
        messages.removeAll { it.peerDeviceId == peerDeviceId }
    }
}
