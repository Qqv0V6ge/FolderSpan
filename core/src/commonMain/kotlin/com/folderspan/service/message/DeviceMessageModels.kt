package com.folderspan.service.message

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.protobuf.ProtoNumber

const val DEVICE_MESSAGE_MAX_BODY_BYTES = 1_048_576
const val DEVICE_MESSAGE_ID_MIN_LENGTH = 16
const val DEVICE_MESSAGE_ID_MAX_LENGTH = 128
const val DEVICE_MESSAGE_SHA256_HEX_LENGTH = 64
const val DEVICE_MESSAGE_TIMESTAMP_FUTURE_SKEW_MILLIS = 5L * 60L * 1_000L

@OptIn(ExperimentalSerializationApi::class)
@Serializable
data class DeviceMessageMetadata(
    @ProtoNumber(1) val messageId: String,
    @ProtoNumber(2) val sentAtEpochMillis: Long,
    @ProtoNumber(3) val utf8Length: Int,
    @ProtoNumber(4) val sha256: String,
)

@OptIn(ExperimentalSerializationApi::class)
@Serializable
data class DeviceMessageReceipt(
    @ProtoNumber(1) val messageId: String,
    @ProtoNumber(2) val receivedAtEpochMillis: Long,
)

enum class DeviceMessageDirection {
    Incoming,
    Outgoing,
}

enum class DeviceMessageStatus {
    Sending,
    Delivered,
    Failed,
    Unconfirmed,
    Received,
}

data class DeviceStoredMessage(
    val localId: Long,
    val messageId: String,
    val peerDeviceId: String,
    val direction: DeviceMessageDirection,
    val body: String,
    val bodyUtf8Length: Int,
    val bodySha256: String,
    val sentAtMillis: Long,
    val receivedAtMillis: Long?,
    val status: DeviceMessageStatus,
    val isRead: Boolean,
    val createdAtMillis: Long,
)

data class DeviceConversationSummary(
    val peerDeviceId: String,
    val lastMessageAtMillis: Long,
    val unreadCount: Long,
)

data class ValidatedDeviceMessage(
    val metadata: DeviceMessageMetadata,
    val bodyBytes: ByteArray,
    val body: String,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is ValidatedDeviceMessage) return false
        return metadata == other.metadata && bodyBytes.contentEquals(other.bodyBytes) && body == other.body
    }

    override fun hashCode(): Int {
        var result = metadata.hashCode()
        result = 31 * result + bodyBytes.contentHashCode()
        result = 31 * result + body.hashCode()
        return result
    }
}
