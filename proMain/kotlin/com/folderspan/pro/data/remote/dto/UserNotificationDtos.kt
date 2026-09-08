package com.folderspan.pro.data.remote.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class UserNotificationEnvelopeDto<T>(
    val code: Int = 0,
    val data: T? = null,
    val msg: String? = null,
)

@Serializable
data class UserNotificationListDataDto(
    val list: List<UserNotificationDto> = emptyList(),
    val total: Int = 0,
    val page: Int = 1,
    @SerialName("pageSize") val pageSize: Int = 30,
)

@Serializable
data class UserNotificationDto(
    val id: String = "",
    @SerialName("user_uuid") val userUuid: String = "",
    val title: String = "",
    val content: String = "",
    val locale: String? = null,
    val type: String = "general",
    val status: String = "unread",
    val actions: List<UserNotificationActionDto> = emptyList(),
    @SerialName("read_at") val readAt: Long? = null,
    @SerialName("created_at") val createdAt: Long = 0L,
)

@Serializable
data class UserNotificationActionDto(
    val label: String = "",
    val style: String = "",
    val kind: String = "",
    val url: String? = null,
    val route: String? = null,
    val params: Map<String, String> = emptyMap(),
)

@Serializable
data class UserNotificationBatchReadRequest(val ids: List<String>)
