package com.folderspan.pro.domain.model

import com.folderspan.data.file.FileProtocol
import com.folderspan.editor.EditorSearchHistoryEntry
import com.folderspan.ui.state.file.DrawerBookmarkType
import com.folderspan.utils.SettingsUtils
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

data class CloneSettingTargetCommand(
    val type: String,
    val sourceTargetId: String,
    val targetId: String,
    val name: String,
    val description: String? = null,
    val subType: String? = null,
)

data class DeviceSettingEntry(
    val key: String,
    val value: JsonElement,
    val updatedAt: Long? = null,
)

data class RemoteSettingSave(
    val type: String,
    val targetId: String,
    val key: String,
    val value: JsonElement,
)

data class RemoteSettingEntryKey(
    val type: String,
    val targetId: String,
    val key: String,
)

object ProConfigurationSnapshotKeys {
    const val DEVICES = "app.devices.snapshot"
    const val ROLES = "app.roles.snapshot"
    const val NETWORKS = "app.networks.snapshot"
    const val WEB_RTC_ROOMS = "app.webRtcRooms.snapshot"
    const val SYNC_TASKS = "app.syncTasks.snapshot"
    const val DATA_ENCRYPTION_KEY = SettingsUtils.KEY_DATA_ENCRYPTION_KEY_SYNC
}

@Serializable
data class DeviceConfigurationSnapshot(
    val version: Int = 1,
    val devices: List<DeviceSnapshotItem> = emptyList(),
    val connections: List<DeviceConnectionSnapshotItem> = emptyList(),
    val receiveShares: List<DeviceReceiveShareSnapshotItem> = emptyList(),
)

@Serializable
data class DeviceSnapshotItem(
    val id: String,
    val name: String,
    val host: String = "",
    val port: Long = 0,
    val type: com.folderspan.data.main.device.DeviceType,
    val hasRemarks: Boolean = false,
)

@Serializable
data class DeviceConnectionSnapshotItem(
    val id: String,
    val connectionType: com.folderspan.data.main.device.DeviceConnectType,
    val firstConnection: Long,
    val lastConnection: Long,
    val category: com.folderspan.data.main.device.DeviceCategory,
    val roleId: Long = -1L,
)

@Serializable
data class DeviceReceiveShareSnapshotItem(
    val id: String,
    val connectionType: com.folderspan.data.main.device.DeviceConnectType,
    val path: String,
)

@Serializable
data class RoleConfigurationSnapshot(
    val version: Int = 1,
    val roles: List<RoleSnapshotItem> = emptyList(),
    val permissions: List<DevicePermissionSnapshotItem> = emptyList(),
    val rolePermissions: List<RolePermissionLinkSnapshotItem> = emptyList(),
)

@Serializable
data class RoleSnapshotItem(
    val id: Long,
    val name: String,
    val comment: String? = null,
    val sortOrder: Long = 0L,
)

@Serializable
data class DevicePermissionSnapshotItem(
    val id: Long,
    val path: String,
    val useAll: Boolean = true,
    val read: Boolean = true,
    val write: Boolean = true,
    val remove: Boolean = true,
    val rename: Boolean = true,
    val sortOrder: Long = 0L,
    val comment: String? = null,
)

@Serializable
data class RolePermissionLinkSnapshotItem(
    val deviceRoleId: Long,
    val devicePermissionId: Long,
)

@Serializable
data class NetworkConfigurationSnapshot(
    val version: Int = 1,
    val networks: List<NetworkSnapshotItem> = emptyList(),
)

@Serializable
data class NetworkSnapshotItem(
    val name: String,
    val protocol: String,
    val host: String,
    val username: String = "",
    val encryptedPassword: String = "",
    val pathSeparator: String = "/",
    val pinned: Boolean = false,
    val encryptedExtras: String = "",
)

@Serializable
data class WebRtcRoomConfigurationSnapshot(
    val version: Int = 1,
    val rooms: List<WebRtcRoomSnapshotItem> = emptyList(),
)

@Serializable
data class WebRtcRoomSnapshotItem(
    val name: String,
    val wssUrl: String,
    val roomId: String,
    val stunUrl: String = "",
    val turnUrl: String = "",
    val turnUsername: String = "",
    val encryptedTurnPassword: String = "",
    val source: com.folderspan.data.main.webrtc.WebRtcRoomSource,
    val pinned: Boolean = false,
    val sortOrder: Long = 0L,
    val createdAt: Long = 0L,
    val updatedAt: Long = 0L,
)

@Serializable
data class SyncTasksConfigurationSnapshot(
    val version: Int = 1,
    val tasks: List<com.folderspan.ui.state.main.SyncTask> = emptyList(),
)

@Serializable
data class SyncBookmarkSnapshotItem(
    val name: String,
    val type: DrawerBookmarkType,
    val path: String,
    val icon: String? = null,
    val sort: Long = 0L,
)

@Serializable
data class SyncFavoriteSnapshotItem(
    val name: String,
    val isDirectory: Boolean,
    val isFixed: Boolean,
    val path: String,
    val mineType: String,
    val size: Long,
    val createdDate: Long,
    val updatedDate: Long,
    val protocol: FileProtocol,
    val protocolId: String? = null,
)

interface SyncSnapshots {
    suspend fun readBookmarks(): List<SyncBookmarkSnapshotItem>
    suspend fun replaceBookmarks(items: List<SyncBookmarkSnapshotItem>)
    suspend fun readFavorites(): List<SyncFavoriteSnapshotItem>
    suspend fun replaceFavorites(items: List<SyncFavoriteSnapshotItem>)
    suspend fun readEditorSearchHistory(): List<EditorSearchHistoryEntry> = emptyList()
    suspend fun replaceEditorSearchHistory(items: List<EditorSearchHistoryEntry>) = Unit
}
