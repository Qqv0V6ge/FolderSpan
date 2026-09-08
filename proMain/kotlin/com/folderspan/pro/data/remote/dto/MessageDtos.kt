package com.folderspan.pro.data.remote.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class MessageEnvelopeDto<T>(
    val code: Int = 0,
    val data: T? = null,
    val msg: String? = null,
)

@Serializable
data class MessageListDataDto(
    val list: List<MessageItemDto> = emptyList(),
    val total: Int = 0,
    val page: Int = 1,
    @SerialName("pageSize") val pageSize: Int = 20,
)

@Serializable
data class MessageItemDto(
    val id: Long = 0L,
    val type: String = "announcement",
    val title: String = "",
    val content: String = "",
    val version: String = "",
    val platform: String = "",
    val link: String = "",
    @SerialName("published_at") val publishedAt: Long = 0L,
    val locale: String? = null,
    val channel: String? = null,
)
