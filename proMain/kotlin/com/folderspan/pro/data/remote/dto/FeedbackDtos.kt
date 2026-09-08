package com.folderspan.pro.data.remote.dto

import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.JsonEncoder
import kotlinx.serialization.json.contentOrNull

@Serializable
data class FeedbackSubmitRequest(
    val content: String,
    val appVersion: String,
    val platform: String,
    val category: String? = null,
    val contact: String? = null,
    val type: String? = null,
)

@Serializable
data class FeedbackUpdateRequest(
    val uuid: String,
    val content: String,
    val appVersion: String,
    val platform: String,
    val category: String? = null,
    val contact: String? = null,
    val type: String? = null,
)

@Serializable
data class FeedbackDeleteRequest(val uuids: List<String>)

@Serializable
data class FeedbackSupplementRequest(val content: String)

@Serializable
data class FeedbackWithdrawRequest(val reason: String? = null)

@Serializable
data class FeedbackEnvelopeDto<T>(
    val code: Int = 0,
    val data: T? = null,
    val msg: String? = null,
)

@Serializable
data class FeedbackCategoriesDataDto(val list: List<FeedbackCategoryDto> = emptyList())

@Serializable
data class FeedbackCategoryDto(
    val key: String = "",
    val label: String = "",
)

@Serializable
data class FeedbackListDataDto(
    val list: List<FeedbackSummaryDto> = emptyList(),
    val total: Int = 0,
)

@Serializable
data class FeedbackSummaryDto(
    val id: Long? = null,
    val uuid: String? = null,
    val type: String = "",
    val category: String = "",
    val content: String = "",
    val contact: String = "",
    val status: String = "",
    val statusNote: String = "",
    val appVersion: String = "",
    val platform: String = "",
    val createdAt: String = "",
    val updatedAt: String = "",
    val priority: String = "",
    @Serializable(with = FlexibleNullableStringSerializer::class)
    val dueAt: String? = null,
    val unreadCount: Int = 0,
    val version: Int = 0,
)

@Serializable
data class FeedbackCaseDataDto(
    val feedback: FeedbackSummaryDto? = null,
    val events: List<FeedbackEventDto> = emptyList(),
    val attachments: List<FeedbackAttachmentDto> = emptyList(),
    val canSupplement: Boolean = false,
    val canWithdraw: Boolean = false,
    val caseType: String = "feedback",
)

@Serializable
data class FeedbackEventDto(
    val uuid: String = "",
    val actorType: String = "",
    val actorName: String = "",
    val eventType: String = "",
    val fromStatus: String = "",
    val toStatus: String = "",
    val message: String = "",
    val createdAt: String = "",
)

@Serializable
data class FeedbackAttachmentDto(
    val uuid: String = "",
    val originalName: String = "",
    val contentType: String = "application/octet-stream",
    val size: Long = 0L,
    val createdAt: String = "",
)

@Serializable
data class FeedbackOperationDataDto(
    val success: Boolean = false,
    val status: String = "",
    val version: Int = 0,
)

object FlexibleNullableStringSerializer : KSerializer<String?> {
    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor("FlexibleNullableString", PrimitiveKind.STRING)

    override fun deserialize(decoder: Decoder): String? {
        val jsonDecoder = decoder as? JsonDecoder
            ?: return decoder.decodeString().trim().takeIf(String::isNotEmpty)
        val element = jsonDecoder.decodeJsonElement()
        if (element is JsonNull) return null
        return (element as? JsonPrimitive)
            ?.contentOrNull
            ?.trim()
            ?.takeIf(String::isNotEmpty)
    }

    override fun serialize(encoder: Encoder, value: String?) {
        val jsonEncoder = encoder as? JsonEncoder
        if (jsonEncoder != null) {
            jsonEncoder.encodeJsonElement(value?.let(::JsonPrimitive) ?: JsonNull)
        } else if (value != null) {
            encoder.encodeString(value)
        } else {
            encoder.encodeString("")
        }
    }
}
