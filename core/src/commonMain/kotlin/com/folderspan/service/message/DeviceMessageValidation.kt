package com.folderspan.service.message

import com.folderspan.utils.secureRandomBytes
import korlibs.crypto.sha256

enum class DeviceMessageValidationError {
    BlankBody,
    BodyTooLarge,
    InvalidMessageId,
    InvalidTimestamp,
    InvalidDeclaredLength,
    InvalidSha256,
    LengthMismatch,
    IntegrityMismatch,
    MalformedUtf8,
}

class DeviceMessageValidationException(
    val error: DeviceMessageValidationError,
) : IllegalArgumentException(error.name)

fun newDeviceMessageId(): String {
    val bytes = secureRandomBytes(16)
    val alphabet = "0123456789abcdef"
    return buildString(bytes.size * 2) {
        bytes.forEach { byte ->
            val value = byte.toInt() and 0xFF
            append(alphabet[value ushr 4])
            append(alphabet[value and 0x0F])
        }
    }
}

fun validateDeviceMessageId(messageId: String): String {
    val valid = messageId.length in DEVICE_MESSAGE_ID_MIN_LENGTH..DEVICE_MESSAGE_ID_MAX_LENGTH &&
        messageId.all { character ->
            character in 'a'..'z' ||
                character in 'A'..'Z' ||
                character in '0'..'9' ||
                character == '-' ||
                character == '_' ||
                character == '.'
        }
    if (!valid) throw DeviceMessageValidationException(DeviceMessageValidationError.InvalidMessageId)
    return messageId
}

fun prepareDeviceMessage(
    body: String,
    messageId: String,
    sentAtEpochMillis: Long,
    nowEpochMillis: Long = sentAtEpochMillis,
): ValidatedDeviceMessage {
    validateDeviceMessageId(messageId)
    validateDeviceMessageTimestamp(sentAtEpochMillis, nowEpochMillis)
    if (body.isBlank()) throw DeviceMessageValidationException(DeviceMessageValidationError.BlankBody)
    val bodyBytes = body.encodeToByteArray()
    if (bodyBytes.size > DEVICE_MESSAGE_MAX_BODY_BYTES) {
        throw DeviceMessageValidationException(DeviceMessageValidationError.BodyTooLarge)
    }
    val metadata = DeviceMessageMetadata(
        messageId = messageId,
        sentAtEpochMillis = sentAtEpochMillis,
        utf8Length = bodyBytes.size,
        sha256 = bodyBytes.deviceMessageSha256(),
    )
    return ValidatedDeviceMessage(metadata, bodyBytes, body)
}

fun validateDeviceMessageMetadata(
    metadata: DeviceMessageMetadata,
    nowEpochMillis: Long,
): DeviceMessageMetadata {
    validateDeviceMessageId(metadata.messageId)
    validateDeviceMessageTimestamp(metadata.sentAtEpochMillis, nowEpochMillis)
    if (metadata.utf8Length !in 1..DEVICE_MESSAGE_MAX_BODY_BYTES) {
        throw DeviceMessageValidationException(DeviceMessageValidationError.InvalidDeclaredLength)
    }
    val normalizedSha256 = metadata.sha256.lowercase()
    if (normalizedSha256.length != DEVICE_MESSAGE_SHA256_HEX_LENGTH ||
        normalizedSha256.any { it !in '0'..'9' && it !in 'a'..'f' }
    ) {
        throw DeviceMessageValidationException(DeviceMessageValidationError.InvalidSha256)
    }
    return metadata.copy(sha256 = normalizedSha256)
}

fun validateDeviceMessageBody(
    metadata: DeviceMessageMetadata,
    bodyBytes: ByteArray,
    nowEpochMillis: Long,
): ValidatedDeviceMessage {
    val validatedMetadata = validateDeviceMessageMetadata(metadata, nowEpochMillis)
    if (bodyBytes.size != validatedMetadata.utf8Length) {
        throw DeviceMessageValidationException(DeviceMessageValidationError.LengthMismatch)
    }
    if (bodyBytes.deviceMessageSha256() != validatedMetadata.sha256) {
        throw DeviceMessageValidationException(DeviceMessageValidationError.IntegrityMismatch)
    }
    val body = try {
        bodyBytes.decodeToString(throwOnInvalidSequence = true)
    } catch (_: CharacterCodingException) {
        throw DeviceMessageValidationException(DeviceMessageValidationError.MalformedUtf8)
    }
    if (body.isBlank()) throw DeviceMessageValidationException(DeviceMessageValidationError.BlankBody)
    return ValidatedDeviceMessage(validatedMetadata, bodyBytes.copyOf(), body)
}

fun ByteArray.deviceMessageSha256(): String = sha256().hexLower

private fun validateDeviceMessageTimestamp(sentAtEpochMillis: Long, nowEpochMillis: Long) {
    if (sentAtEpochMillis <= 0L || sentAtEpochMillis > nowEpochMillis + DEVICE_MESSAGE_TIMESTAMP_FUTURE_SKEW_MILLIS) {
        throw DeviceMessageValidationException(DeviceMessageValidationError.InvalidTimestamp)
    }
}
