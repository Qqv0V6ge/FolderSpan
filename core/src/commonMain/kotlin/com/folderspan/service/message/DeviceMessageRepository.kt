package com.folderspan.service.message

import com.folderspan.db.DeviceConversation
import com.folderspan.db.DeviceMessage
import com.folderspan.db.FolderSpanDatabase
import com.folderspan.utils.DatabaseReady
import com.folderspan.utils.executeAsListAwait
import com.folderspan.utils.executeAsOneAwait
import com.folderspan.utils.executeAsOneOrNullAwait

data class IncomingDeviceMessagePersistence(
    val message: DeviceStoredMessage,
    val duplicate: Boolean,
)

class DeviceMessagePersistenceException(message: String) : IllegalStateException(message)

interface DeviceMessageStore {
    suspend fun insertOutgoing(
        peerDeviceId: String,
        message: ValidatedDeviceMessage,
        createdAtMillis: Long,
    ): DeviceStoredMessage

    suspend fun updateOutgoingStatus(
        peerDeviceId: String,
        messageId: String,
        status: DeviceMessageStatus,
        receivedAtMillis: Long? = null,
    ): DeviceStoredMessage

    suspend fun prepareExplicitRetry(peerDeviceId: String, messageId: String): DeviceStoredMessage

    suspend fun persistIncoming(
        peerDeviceId: String,
        message: ValidatedDeviceMessage,
        receivedAtMillis: Long,
        isRead: Boolean,
    ): IncomingDeviceMessagePersistence

    suspend fun recoverInterruptedSends(): Long

    suspend fun page(peerDeviceId: String, limit: Long, offset: Long = 0L): List<DeviceStoredMessage>

    suspend fun pageBefore(
        peerDeviceId: String,
        beforeSentAtMillis: Long,
        beforeLocalId: Long,
        limit: Long,
    ): List<DeviceStoredMessage>

    suspend fun conversations(): List<DeviceConversationSummary>

    suspend fun conversation(peerDeviceId: String): DeviceConversationSummary?

    suspend fun count(peerDeviceId: String): Long

    suspend fun find(
        peerDeviceId: String,
        messageId: String,
        direction: DeviceMessageDirection,
    ): DeviceStoredMessage?

    suspend fun markConversationRead(peerDeviceId: String)

    suspend fun deleteConversation(peerDeviceId: String)
}

class SqlDelightDeviceMessageStore(
    private val database: FolderSpanDatabase,
) : DeviceMessageStore {
    private val queries
        get() = database.deviceMessageQueries

    override suspend fun insertOutgoing(
        peerDeviceId: String,
        message: ValidatedDeviceMessage,
        createdAtMillis: Long,
    ): DeviceStoredMessage {
        requirePeerDeviceId(peerDeviceId)
        DatabaseReady.await()
        return database.transactionWithResult {
            val inserted = queries.insertOutgoing(
                messageId = message.metadata.messageId,
                peerDeviceId = peerDeviceId,
                body = message.body,
                bodyUtf8Length = message.metadata.utf8Length.toLong(),
                bodySha256 = message.metadata.sha256,
                sentAtMillis = message.metadata.sentAtEpochMillis,
                createdAtMillis = createdAtMillis,
            ).value
            val stored = queries.selectByIdentity(
                peerDeviceId = peerDeviceId,
                messageId = message.metadata.messageId,
                direction = DeviceMessageDirection.Outgoing.name,
            ).executeAsOne()
            verifyStoredPayload(stored, message)
            if (inserted > 0L) {
                queries.upsertConversationOutgoing(
                    peerDeviceId = peerDeviceId,
                    messageAtMillis = message.metadata.sentAtEpochMillis,
                ).value
            }
            stored.toDomain()
        }
    }

    override suspend fun updateOutgoingStatus(
        peerDeviceId: String,
        messageId: String,
        status: DeviceMessageStatus,
        receivedAtMillis: Long?,
    ): DeviceStoredMessage {
        require(status != DeviceMessageStatus.Received && status != DeviceMessageStatus.Sending) {
            "Outgoing completion status must be Delivered, Failed, or Unconfirmed"
        }
        DatabaseReady.await()
        queries.updateOutgoingStatus(
            status = status.name,
            receivedAtMillis = receivedAtMillis,
            peerDeviceId = peerDeviceId,
            messageId = messageId,
        ).await()
        return find(peerDeviceId, messageId, DeviceMessageDirection.Outgoing)
            ?: throw DeviceMessagePersistenceException("Outgoing message does not exist")
    }

    override suspend fun prepareExplicitRetry(peerDeviceId: String, messageId: String): DeviceStoredMessage {
        DatabaseReady.await()
        return database.transactionWithResult {
            val existing = queries.selectByIdentity(
                peerDeviceId = peerDeviceId,
                messageId = messageId,
                direction = DeviceMessageDirection.Outgoing.name,
            ).executeAsOneOrNull() ?: throw DeviceMessagePersistenceException("Outgoing message does not exist")
            val status = existing.status.toDeviceMessageStatus()
            if (status != DeviceMessageStatus.Failed && status != DeviceMessageStatus.Unconfirmed) {
                throw DeviceMessagePersistenceException("Only failed or unconfirmed messages may be retried")
            }
            queries.updateOutgoingForExplicitRetry(peerDeviceId, messageId).value
            existing.copy(status = DeviceMessageStatus.Sending.name, receivedAtMillis = null).toDomain()
        }
    }

    override suspend fun persistIncoming(
        peerDeviceId: String,
        message: ValidatedDeviceMessage,
        receivedAtMillis: Long,
        isRead: Boolean,
    ): IncomingDeviceMessagePersistence {
        requirePeerDeviceId(peerDeviceId)
        DatabaseReady.await()
        return database.transactionWithResult {
            val inserted = queries.insertIncoming(
                messageId = message.metadata.messageId,
                peerDeviceId = peerDeviceId,
                body = message.body,
                bodyUtf8Length = message.metadata.utf8Length.toLong(),
                bodySha256 = message.metadata.sha256,
                sentAtMillis = message.metadata.sentAtEpochMillis,
                receivedAtMillis = receivedAtMillis,
                isRead = isRead,
                createdAtMillis = receivedAtMillis,
            ).value
            val stored = queries.selectByIdentity(
                peerDeviceId = peerDeviceId,
                messageId = message.metadata.messageId,
                direction = DeviceMessageDirection.Incoming.name,
            ).executeAsOne()
            verifyStoredPayload(stored, message)
            if (inserted > 0L) {
                if (isRead) {
                    queries.upsertConversationIncomingRead(
                        peerDeviceId = peerDeviceId,
                        messageAtMillis = message.metadata.sentAtEpochMillis,
                    ).value
                } else {
                    queries.upsertConversationIncomingUnread(
                        peerDeviceId = peerDeviceId,
                        messageAtMillis = message.metadata.sentAtEpochMillis,
                    ).value
                }
            }
            IncomingDeviceMessagePersistence(stored.toDomain(), duplicate = inserted == 0L)
        }
    }

    override suspend fun recoverInterruptedSends(): Long {
        DatabaseReady.await()
        return queries.recoverSendingAsUnconfirmed().await()
    }

    override suspend fun page(peerDeviceId: String, limit: Long, offset: Long): List<DeviceStoredMessage> {
        requirePage(limit, offset)
        DatabaseReady.await()
        return queries.selectPage(peerDeviceId, limit, offset)
            .executeAsListAwait()
            .map(DeviceMessage::toDomain)
    }

    override suspend fun pageBefore(
        peerDeviceId: String,
        beforeSentAtMillis: Long,
        beforeLocalId: Long,
        limit: Long,
    ): List<DeviceStoredMessage> {
        requirePage(limit, 0L)
        DatabaseReady.await()
        return queries.selectPageBefore(
            peerDeviceId = peerDeviceId,
            sentAtMillis = beforeSentAtMillis,
            sentAtMillis_ = beforeSentAtMillis,
            localId = beforeLocalId,
            value_ = limit,
        ).executeAsListAwait().map(DeviceMessage::toDomain)
    }

    override suspend fun conversations(): List<DeviceConversationSummary> {
        DatabaseReady.await()
        return queries.selectConversations().executeAsListAwait().map(DeviceConversation::toDomain)
    }

    override suspend fun conversation(peerDeviceId: String): DeviceConversationSummary? {
        DatabaseReady.await()
        return queries.selectConversationByPeer(peerDeviceId).executeAsOneOrNullAwait()?.toDomain()
    }

    override suspend fun count(peerDeviceId: String): Long {
        DatabaseReady.await()
        return queries.countByPeer(peerDeviceId).executeAsOneAwait()
    }

    override suspend fun find(
        peerDeviceId: String,
        messageId: String,
        direction: DeviceMessageDirection,
    ): DeviceStoredMessage? {
        DatabaseReady.await()
        return queries.selectByIdentity(peerDeviceId, messageId, direction.name)
            .executeAsOneOrNullAwait()
            ?.toDomain()
    }

    override suspend fun markConversationRead(peerDeviceId: String) {
        DatabaseReady.await()
        database.transaction {
            queries.markPeerRead(peerDeviceId).value
            queries.clearConversationUnread(peerDeviceId).value
        }
    }

    override suspend fun deleteConversation(peerDeviceId: String) {
        DatabaseReady.await()
        database.transaction {
            queries.deleteByPeer(peerDeviceId).value
            queries.deleteConversationByPeer(peerDeviceId).value
        }
    }
}

private fun DeviceMessage.toDomain(): DeviceStoredMessage = DeviceStoredMessage(
    localId = localId,
    messageId = messageId,
    peerDeviceId = peerDeviceId,
    direction = direction.toDeviceMessageDirection(),
    body = body,
    bodyUtf8Length = bodyUtf8Length.toInt(),
    bodySha256 = bodySha256,
    sentAtMillis = sentAtMillis,
    receivedAtMillis = receivedAtMillis,
    status = status.toDeviceMessageStatus(),
    isRead = isRead,
    createdAtMillis = createdAtMillis,
)

private fun DeviceConversation.toDomain(): DeviceConversationSummary = DeviceConversationSummary(
    peerDeviceId = peerDeviceId,
    lastMessageAtMillis = lastMessageAtMillis,
    unreadCount = unreadCount,
)

private fun verifyStoredPayload(stored: DeviceMessage, message: ValidatedDeviceMessage) {
    if (stored.body != message.body ||
        stored.bodyUtf8Length != message.metadata.utf8Length.toLong() ||
        stored.bodySha256 != message.metadata.sha256 ||
        stored.sentAtMillis != message.metadata.sentAtEpochMillis
    ) {
        throw DeviceMessagePersistenceException("Message ID already exists with different content")
    }
}

private fun String.toDeviceMessageDirection(): DeviceMessageDirection =
    runCatching { DeviceMessageDirection.valueOf(this) }
        .getOrElse { throw DeviceMessagePersistenceException("Unknown message direction") }

private fun String.toDeviceMessageStatus(): DeviceMessageStatus =
    runCatching { DeviceMessageStatus.valueOf(this) }
        .getOrElse { throw DeviceMessagePersistenceException("Unknown message status") }

private fun requirePeerDeviceId(peerDeviceId: String) {
    require(peerDeviceId.isNotBlank()) { "Peer device ID must not be blank" }
}

private fun requirePage(limit: Long, offset: Long) {
    require(limit in 1L..200L) { "Page size must be between 1 and 200" }
    require(offset >= 0L) { "Page offset must not be negative" }
}
