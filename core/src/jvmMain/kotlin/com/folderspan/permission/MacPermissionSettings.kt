package com.folderspan.permission

internal object MacPermissionSettings {
    fun supports(
        major: Int,
        minor: Int = 0,
        osName: String = System.getProperty("os.name").orEmpty(),
        osVersion: String = System.getProperty("os.version").orEmpty(),
    ): Boolean {
        if (!osName.contains("mac", ignoreCase = true) && !osName.equals("darwin", ignoreCase = true)) return false
        val parts = osVersion.split('.')
        val currentMajor = parts.getOrNull(0)?.toIntOrNull() ?: return false
        val currentMinor = parts.getOrNull(1)?.toIntOrNull() ?: 0
        return currentMajor > major || currentMajor == major && currentMinor >= minor
    }

    fun privacyStatus(
        permissionId: String,
        osName: String = System.getProperty("os.name").orEmpty(),
        osVersion: String = System.getProperty("os.version").orEmpty(),
    ): PermissionStatus {
        val supported = when (permissionId) {
            PermissionIds.FilesAndFolders -> supports(10, 15, osName, osVersion)
            PermissionIds.FullDiskAccess -> supports(10, 14, osName, osVersion)
            PermissionIds.AppManagement -> supports(13, osName = osName, osVersion = osVersion)
            else -> false
        }
        // Reading a protected folder or the TCC database is not a reliable
        // permission query. Keep the user's files untouched during status checks.
        return if (supported) PermissionStatus.Unknown else PermissionStatus.Unsupported
    }

    fun url(permissionId: String): String? = when (permissionId) {
        PermissionIds.Notifications -> "x-apple.systempreferences:com.apple.preference.notifications?id=com.folderspan"
        PermissionIds.LocalNetwork -> "x-apple.systempreferences:com.apple.preference.security?Privacy_LocalNetwork"
        PermissionIds.FilesAndFolders -> "x-apple.systempreferences:com.apple.preference.security?Privacy_FilesAndFolders"
        PermissionIds.FullDiskAccess -> "x-apple.systempreferences:com.apple.preference.security?Privacy_AllFiles"
        PermissionIds.AppManagement -> "x-apple.systempreferences:com.apple.preference.security?Privacy_AppBundles"
        else -> null
    }

    fun open(permissionId: String) {
        if (!supports(10)) return
        val target = url(permissionId) ?: return
        ProcessDesktopCommandRunner().run(listOf("/usr/bin/open", target))
    }
}
