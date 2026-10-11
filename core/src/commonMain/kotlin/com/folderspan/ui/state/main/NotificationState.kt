package com.folderspan.ui.state.main

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Campaign
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Warning
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.ui.graphics.vector.ImageVector
import com.folderspan.notification.RequestNotificationFactory
import com.folderspan.localization.LocalizedMessage
import com.folderspan.localization.renderOrLegacy
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.protobuf.ProtoNumber
import kotlin.time.Clock

@OptIn(ExperimentalSerializationApi::class)
@Serializable
data class NotificationDisplayOptions(
    @ProtoNumber(1) val showInBell: Boolean = true,
    @ProtoNumber(2) val showInBanner: Boolean = false,
    @ProtoNumber(3) val sendSystemNotification: Boolean = true
)

@Serializable
enum class NotificationType {
    Info,
    Warning,
    Error,
    Success,
    Promotion
}

@OptIn(ExperimentalSerializationApi::class)
@Serializable
data class Notification(
    @ProtoNumber(1) val id: Long,
    @ProtoNumber(2) val title: String,
    @ProtoNumber(3) val message: String,
    @ProtoNumber(4) val isRead: Boolean = false,
    @ProtoNumber(5) val timestamp: Long = Clock.System.now().toEpochMilliseconds(),
    @ProtoNumber(6) val type: NotificationType = NotificationType.Info,
    @ProtoNumber(7) val category: String = "",
    @ProtoNumber(8) val metadata: Map<String, String> = emptyMap(),
    @ProtoNumber(9) val displayOptions: NotificationDisplayOptions = NotificationDisplayOptions(),
    @ProtoNumber(10) val localizedTitle: LocalizedMessage? = null,
    @ProtoNumber(11) val localizedMessage: LocalizedMessage? = null,
    @ProtoNumber(12) val localizedCategory: LocalizedMessage? = null,
) {
    fun getIcon(): ImageVector = when (type) {
        NotificationType.Info -> Icons.Default.Info
        NotificationType.Warning -> Icons.Default.Warning
        NotificationType.Error -> Icons.Default.Error
        NotificationType.Success -> Icons.Default.CheckCircle
        NotificationType.Promotion -> Icons.Default.Campaign
    }

    fun shouldShowInBell(): Boolean = displayOptions.showInBell

    fun shouldShowInBanner(): Boolean = displayOptions.showInBanner

    fun displayTitle(): String = localizedTitle.renderOrLegacy(title)

    fun displayMessage(): String = localizedMessage.renderOrLegacy(message)

    fun displayCategory(): String = localizedCategory.renderOrLegacy(category)
}

class NotificationState {
    private val notificationList = mutableStateListOf<Notification>()
//        .apply {
//        addAll(initialNotifications())
//    }

    var ignoredBannerIds by mutableStateOf(setOf<Long>())
        private set

    val notifications: SnapshotStateList<Notification>
        get() = notificationList

    var revision by mutableStateOf(0L)
        private set

    var pendingOpenRequestId by mutableStateOf<String?>(null)
        private set

    fun requestOpenRequestId(requestId: String) {
        pendingOpenRequestId = requestId
    }

    fun consumePendingOpenRequestId(requestId: String) {
        if (pendingOpenRequestId == requestId) {
            pendingOpenRequestId = null
        }
    }

    fun markAllAsRead() {
        var changed = false
        for (index in notificationList.indices) {
            val item = notificationList[index]
            if (!item.isRead) {
                notificationList[index] = item.copy(isRead = true)
                changed = true
            }
        }
        if (changed) incrementRevision()
    }

    fun markMultipleAsRead(ids: Set<Long>) {
        updateReadState(ids, true)
    }

    fun markMultipleAsUnread(ids: Set<Long>) {
        updateReadState(ids, false)
    }

    fun deleteMultiple(ids: Set<Long>) {
        if (ids.isEmpty()) return
        if (notificationList.removeAll { item ->  item.id in ids }) {
            incrementRevision()
        }
    }

    fun markAsRead(id: Long) {
        updateSingle(id) { current ->
            if (current.isRead) current else current.copy(isRead = true)
        }
    }

    fun markAsUnread(id: Long) {
        updateSingle(id) { current ->
            if (!current.isRead) current else current.copy(isRead = false)
        }
    }

    fun delete(id: Long) {
        if (notificationList.removeAll { item ->  item.id == id }) {
            incrementRevision()
        }
    }

    private fun updateReadState(ids: Set<Long>, isRead: Boolean) {
        if (ids.isEmpty()) return
        var changed = false
        for (index in notificationList.indices) {
            val item = notificationList[index]
            if (item.id in ids && item.isRead != isRead) {
                notificationList[index] = item.copy(isRead = isRead)
                changed = true
            }
        }
        if (changed) incrementRevision()
    }

    private inline fun updateSingle(id: Long, transform: (Notification) -> Notification) {
        val index = notificationList.indexOfFirst { item ->  item.id == id }
        if (index < 0) return
        val current = notificationList[index]
        val updated = transform(current)
        if (updated != current) {
            notificationList[index] = updated
            incrementRevision()
        }
    }

    private fun incrementRevision() {
        revision += 1
    }

    fun upsert(notification: Notification) {
        val index = notificationList.indexOfFirst { item ->  item.id == notification.id }
        if (index >= 0) {
            if (notificationList[index] != notification) {
                notificationList[index] = notification
                incrementRevision()
            }
            return
        }
        notificationList.add(0, notification)
        incrementRevision()
    }

    fun removeByRequestId(requestId: String) {
        val targetId = RequestNotificationFactory.notificationIdFor(requestId)
        delete(targetId)
    }

    fun findByRequestId(requestId: String): Notification? {
        val targetId = RequestNotificationFactory.notificationIdFor(requestId)
        return notificationList.firstOrNull { item ->  item.id == targetId }
    }

    fun ignoreBanner(id: Long) {
        ignoredBannerIds = ignoredBannerIds + id
    }

    fun pruneIgnoredBanners(validIds: Set<Long>) {
        ignoredBannerIds = ignoredBannerIds.intersect(validIds)
    }
}
