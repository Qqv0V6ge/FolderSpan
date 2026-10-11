package com.folderspan.service.message

import strings.AppStrings

import com.folderspan.test.ChineseLocalizationTest
import com.folderspan.utils.ProtoBufCodec
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class DeviceMessageValidationTest : ChineseLocalizationTest() {
    @Test
    fun preservesLiteralTextAndNewlines() {
        val text = "  <b>literal</b>\nsecond line  "
        val validated = prepareDeviceMessage(text, MESSAGE_ID, NOW, NOW)

        assertEquals(text, validated.body)
        assertContentEquals(text.encodeToByteArray(), validated.bodyBytes)
        assertEquals(text.encodeToByteArray().size, validated.metadata.utf8Length)
        assertEquals(validated, validateDeviceMessageBody(validated.metadata, validated.bodyBytes, NOW))
    }

    @Test
    fun acceptsExactlyOneMiB() {
        val text = "a".repeat(DEVICE_MESSAGE_MAX_BODY_BYTES)
        val validated = prepareDeviceMessage(text, MESSAGE_ID, NOW, NOW)

        assertEquals(DEVICE_MESSAGE_MAX_BODY_BYTES, validated.metadata.utf8Length)
    }

    @Test
    fun measuresMultibyteTextAsUtf8Bytes() {
        val validated = prepareDeviceMessage(AppStrings.ui_test_device_message_chinese_emoji, MESSAGE_ID, NOW, NOW)

        assertEquals(7, validated.metadata.utf8Length)
    }

    @Test
    fun rejectsBlankOversizedAndUnstableIds() {
        assertValidationError(DeviceMessageValidationError.BlankBody) {
            prepareDeviceMessage(" \n\t ", MESSAGE_ID, NOW, NOW)
        }
        assertValidationError(DeviceMessageValidationError.BodyTooLarge) {
            prepareDeviceMessage("a".repeat(DEVICE_MESSAGE_MAX_BODY_BYTES + 1), MESSAGE_ID, NOW, NOW)
        }
        assertValidationError(DeviceMessageValidationError.InvalidMessageId) {
            prepareDeviceMessage("hello", "short", NOW, NOW)
        }
    }

    @Test
    fun rejectsLengthChecksumAndMalformedUtf8() {
        val validated = prepareDeviceMessage("hello", MESSAGE_ID, NOW, NOW)
        assertValidationError(DeviceMessageValidationError.LengthMismatch) {
            validateDeviceMessageBody(validated.metadata.copy(utf8Length = 4), validated.bodyBytes, NOW)
        }
        assertValidationError(DeviceMessageValidationError.IntegrityMismatch) {
            validateDeviceMessageBody(validated.metadata, "jello".encodeToByteArray(), NOW)
        }
        val malformed = byteArrayOf(0xC3.toByte(), 0x28)
        val malformedMetadata = DeviceMessageMetadata(
            messageId = MESSAGE_ID,
            sentAtEpochMillis = NOW,
            utf8Length = malformed.size,
            sha256 = malformed.deviceMessageSha256(),
        )
        assertValidationError(DeviceMessageValidationError.MalformedUtf8) {
            validateDeviceMessageBody(malformedMetadata, malformed, NOW)
        }
    }

    @Test
    fun generatedIdsAreStableFormat() {
        val first = newDeviceMessageId()
        val second = newDeviceMessageId()

        assertEquals(32, first.length)
        assertTrue(first != second)
        assertEquals(first, validateDeviceMessageId(first))
    }

    @Test
    fun protocolMetadataAndReceiptRoundTrip() {
        val metadata = prepareDeviceMessage("hello", MESSAGE_ID, NOW, NOW).metadata
        val receipt = DeviceMessageReceipt(MESSAGE_ID, NOW + 1)

        assertEquals(metadata, ProtoBufCodec.decode<DeviceMessageMetadata>(ProtoBufCodec.encode(metadata)))
        assertEquals(receipt, ProtoBufCodec.decode<DeviceMessageReceipt>(ProtoBufCodec.encode(receipt)))
    }

    private fun assertValidationError(
        expected: DeviceMessageValidationError,
        block: () -> Unit,
    ) {
        val exception = assertFailsWith<DeviceMessageValidationException>(block = block)
        assertEquals(expected, exception.error)
    }

    private companion object {
        const val MESSAGE_ID = "0123456789abcdef0123456789abcdef"
        const val NOW = 1_700_000_000_000L
    }
}
