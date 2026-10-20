package com.folderspan.permission

enum class PermissionAction {
    Request,
    OpenSettings,
    None,
    RequestOnce,
    ShowInstructions,
}

enum class PermissionStatus {
    Granted,
    Denied,
    NotDetermined,
    // The permission state cannot currently be determined through supported APIs.
    Unknown,
    Unsupported
}

data class PlatformPermission(
    val id: String,
    val action: PermissionAction,
    val title: String,
    val description: String,
    val settingsInstructions: String? = null,
)

object PermissionIds {
    const val ReadExternalStorage = "read_external_storage"
    const val AllFilesAccess = "all_files_access"
    const val Notifications = "notifications"
    const val LocalNetwork = "local_network"
    const val FilesAndFolders = "files_and_folders"
    const val FullDiskAccess = "full_disk_access"
    const val AppManagement = "app_management"
    const val BatteryOptimization = "battery_optimization"
    const val Shizuku = "shizuku"
    const val Root = "root"
    const val LoginStartup = "login_startup"
}

expect object PlatformPermissionProvider {
    fun permissions(): List<PlatformPermission>
    suspend fun status(permission: PlatformPermission): PermissionStatus
    fun request(permission: PlatformPermission, onResult: (PermissionStatus) -> Unit)
    fun openSettings(permission: PlatformPermission)
}
