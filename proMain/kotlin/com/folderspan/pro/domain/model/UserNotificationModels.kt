package com.folderspan.pro.domain.model

data class AccountNotification(
    val id: String,
    val userUuid: String,
    val title: String,
    val content: String,
    val locale: String?,
    val type: String,
    val status: UserNotificationStatus,
    val actions: List<NotificationAction>,
    val readAtEpochMillis: Long?,
    val createdAtEpochMillis: Long,
)

data class NotificationAction(
    val label: String,
    val style: NotificationActionStyle,
    val kind: NotificationActionKind,
    val url: String? = null,
    val route: String? = null,
    val params: Map<String, String> = emptyMap(),
)

enum class NotificationActionStyle(val wireValue: String) {
    Primary("primary"),
    Secondary("secondary"),
}

enum class NotificationActionKind(val wireValue: String) {
    Url("url"),
    Route("route"),
}

enum class UserNotificationStatus(val wireValue: String) {
    Unread("unread"),
    Read("read"),
}

sealed interface UnifiedNotificationKey {
    data class Local(val id: Long) : UnifiedNotificationKey
    data class Account(val id: String) : UnifiedNotificationKey
    data class Announcement(val id: Long) : UnifiedNotificationKey
}

data class UserNotificationQuery(
    val status: UserNotificationStatus? = null,
    val page: Int = 1,
    val pageSize: Int = 30,
)

data class UserNotificationPage(
    val items: List<AccountNotification>,
    val total: Int,
    val page: Int,
    val pageSize: Int,
)

data class UserNotificationSnapshot(
    val items: List<AccountNotification> = emptyList(),
    val unreadTotal: Int = 0,
    val statusFilter: UserNotificationStatus? = null,
    val isRefreshing: Boolean = false,
    val isLoadingMore: Boolean = false,
    val hasMore: Boolean = false,
    val isStreaming: Boolean = false,
    val errorMessage: String? = null,
)

object AccountNotificationPayloadKeys {
    const val Source = "notification_source"
    const val SourceAccount = "account"
    const val Id = "notification_id"
    const val PrimaryActionKind = "notification_action_kind"
    const val PrimaryActionTarget = "notification_action_target"
    const val PrimaryActionParams = "notification_action_params"
}
