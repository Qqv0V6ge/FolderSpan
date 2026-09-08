package com.folderspan.pro.domain.model

import com.folderspan.PlatformType
import com.folderspan.currentOsName
import com.folderspan.data.main.device.DeviceType

data class Announcement(
    val id: Long,
    val type: String,
    val title: String,
    val content: String,
    val version: String,
    val platform: String,
    val link: String,
    val locale: String?,
    val publishedAtEpochMillis: Long,
    val actions: List<NotificationAction>,
)

data class AnnouncementQuery(
    val type: String = ANNOUNCEMENT_LIST_TYPE,
    val platform: String,
    val page: Int = 1,
    val pageSize: Int = 20,
)

data class AnnouncementPage(
    val items: List<Announcement>,
    val total: Int,
    val page: Int,
    val pageSize: Int,
)

data class AnnouncementSnapshot(
    val items: List<Announcement> = emptyList(),
    val cutoffEpochMillis: Long = 0L,
    val isRefreshing: Boolean = false,
    val isLoadingMore: Boolean = false,
    val hasMore: Boolean = false,
    val errorMessage: String? = null,
)

const val ANNOUNCEMENT_LIST_TYPE = "announcement"

fun Announcement.isRead(cutoffEpochMillis: Long): Boolean =
    publishedAtEpochMillis <= cutoffEpochMillis

fun announcementPlatformToken(
    platformType: DeviceType = PlatformType,
    osName: String = currentOsName(),
): String = when (platformType) {
    DeviceType.Android -> "android"
    DeviceType.IOS -> "ios"
    DeviceType.JS -> "js"
    DeviceType.JVM -> desktopAnnouncementPlatform(osName)
}

internal fun desktopAnnouncementPlatform(osName: String): String {
    val normalized = osName.lowercase()
    return when {
        normalized.contains("mac") || normalized.contains("darwin") -> "macos"
        normalized.contains("win") -> "windows"
        else -> "linux"
    }
}
