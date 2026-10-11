package com.folderspan.service.message

import kotlin.time.Clock

class DeviceMessageService(
    private val store: DeviceMessageStore,
    private val endpointRegistry: DeviceMessageLiveEndpointRegistry,
    private val onOutgoingChanged: (String) -> Unit = {},
    private val nowMillis: () -> Long = { Clock.System.now().toEpochMilliseconds() },
) {
    suspend fun send(
        endpointIdentity: DeviceMessageEndpointIdentity,
        body: String,
        messageId: String = newDeviceMessageId(),
    ): Result<DeviceStoredMessage> = runCatching {
        val now = nowMillis()
        val validated = prepareDeviceMessage(body, messageId, now, now)
        val (stored, client) = endpointRegistry.withLiveEndpoint(endpointIdentity) { endpoint ->
            store.insertOutgoing(endpointIdentity.peerDeviceId, validated, createdAtMillis = now) to endpoint.client
        }
        notifyOutgoingChanged(endpointIdentity.peerDeviceId)
        sendPersisted(stored, validated, client)
    }

    suspend fun retry(
        endpointIdentity: DeviceMessageEndpointIdentity,
        messageId: String,
    ): Result<DeviceStoredMessage> = runCatching {
        val now = nowMillis()
        val (stored, client) = endpointRegistry.withLiveEndpoint(endpointIdentity) { endpoint ->
            store.prepareExplicitRetry(endpointIdentity.peerDeviceId, messageId) to endpoint.client
        }
        notifyOutgoingChanged(endpointIdentity.peerDeviceId)
        val metadata = DeviceMessageMetadata(
            messageId = stored.messageId,
            sentAtEpochMillis = stored.sentAtMillis,
            utf8Length = stored.bodyUtf8Length,
            sha256 = stored.bodySha256,
        )
        val validated = validateDeviceMessageBody(metadata, stored.body.encodeToByteArray(), now)
        sendPersisted(stored, validated, client)
    }

    private suspend fun sendPersisted(
        stored: DeviceStoredMessage,
        message: ValidatedDeviceMessage,
        client: DeviceMessageClient,
    ): DeviceStoredMessage {
        val transferResult = runCatching {
            client.send(
                metadata = message.metadata,
                bodyChunks = chunkDeviceMessageBody(message.bodyBytes, client.maxChunkBytes),
            ).getOrThrow()
        }
        val receipt = transferResult.getOrElse { failure ->
            val status = if ((failure as? DeviceMessageTransferException)?.deliveryUncertain == true) {
                DeviceMessageStatus.Unconfirmed
            } else {
                DeviceMessageStatus.Failed
            }
            runCatching {
                store.updateOutgoingStatus(stored.peerDeviceId, stored.messageId, status)
            }
            notifyOutgoingChanged(stored.peerDeviceId)
            throw failure
        }
        if (receipt.messageId != stored.messageId || receipt.receivedAtEpochMillis <= 0L) {
            runCatching {
                store.updateOutgoingStatus(
                    stored.peerDeviceId,
                    stored.messageId,
                    DeviceMessageStatus.Unconfirmed,
                )
            }
            notifyOutgoingChanged(stored.peerDeviceId)
            throw DeviceMessageTransferException(
                DeviceMessageTransferError.ReceiptMismatch,
                deliveryUncertain = true,
            )
        }
        return store.updateOutgoingStatus(
            peerDeviceId = stored.peerDeviceId,
            messageId = stored.messageId,
            status = DeviceMessageStatus.Delivered,
            receivedAtMillis = receipt.receivedAtEpochMillis,
        ).also { notifyOutgoingChanged(stored.peerDeviceId) }
    }

    private fun notifyOutgoingChanged(peerDeviceId: String) {
        runCatching { onOutgoingChanged(peerDeviceId) }
    }
}
