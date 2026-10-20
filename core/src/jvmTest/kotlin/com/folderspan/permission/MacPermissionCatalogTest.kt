package com.folderspan.permission

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class MacPermissionCatalogTest {
    @Test
    fun macOsListsPermissionsUsedByFileManagementAndNotifications() {
        val permissions = desktopPlatformPermissions("Mac OS X", "26.0")

        assertEquals(
            setOf("notifications", "files_and_folders", "full_disk_access", "app_management", "local_network", "login_startup"),
            permissions.map { it.id }.toSet(),
        )
        assertEquals("RequestOnce", permissions.single { it.id == "notifications" }.action.name)
        assertEquals(PermissionAction.OpenSettings, permissions.single { it.id == "files_and_folders" }.action)
        assertEquals(PermissionAction.OpenSettings, permissions.single { it.id == "full_disk_access" }.action)
        assertTrue(permissions.all { it.title.isNotBlank() && it.description.isNotBlank() })
    }

    @Test
    fun macOsFourteenStillHasNotificationAndFilePermissions() {
        assertEquals(
            setOf("notifications", "files_and_folders", "full_disk_access", "app_management", "login_startup"),
            desktopPlatformPermissions("Mac OS X", "14.7").map { it.id }.toSet(),
        )
    }

    @Test
    fun otherDesktopPlatformsListTheirNotificationNetworkAndFileAccessEntries() {
        listOf("Windows 11" to "10.0", "Linux" to "6.12").forEach { (os, version) ->
            val permissions = desktopPlatformPermissions(os, version)
            assertEquals(
                setOf(PermissionIds.Notifications, PermissionIds.LocalNetwork, PermissionIds.FilesAndFolders, PermissionIds.LoginStartup),
                permissions.map { it.id }.toSet(),
            )
            assertEquals(PermissionAction.OpenSettings, permissions.single { it.id == PermissionIds.Notifications }.action)
            assertEquals(PermissionAction.OpenSettings, permissions.single { it.id == PermissionIds.LocalNetwork }.action)
        }
    }

    @Test
    fun filePermissionsNeedSystemConfirmationAndHaveSeparateSettingsDestinations() {
        listOf(PermissionIds.FilesAndFolders, PermissionIds.FullDiskAccess, PermissionIds.AppManagement).forEach { id ->
            assertEquals(PermissionStatus.Unknown, MacPermissionSettings.privacyStatus(id, "Mac OS X", "26.0"))
            assertEquals(PermissionStatus.Unsupported, MacPermissionSettings.privacyStatus(id, "Linux", "6.12"))
        }
        assertTrue(MacPermissionSettings.url(PermissionIds.FilesAndFolders)!!.endsWith("Privacy_FilesAndFolders"))
        assertTrue(MacPermissionSettings.url(PermissionIds.FullDiskAccess)!!.endsWith("Privacy_AllFiles"))
        assertTrue(MacPermissionSettings.url(PermissionIds.AppManagement)!!.endsWith("Privacy_AppBundles"))
    }

    @Test
    fun fileAndNotificationPermissionsRespectOlderMacOsVersions() {
        assertEquals(
            setOf(PermissionIds.Notifications, PermissionIds.FullDiskAccess, PermissionIds.LoginStartup),
            desktopPlatformPermissions("Mac OS X", "10.14").map { it.id }.toSet(),
        )
        assertEquals(listOf(PermissionIds.LoginStartup), desktopPlatformPermissions("Mac OS X", "10.13").map { it.id })
    }
}
