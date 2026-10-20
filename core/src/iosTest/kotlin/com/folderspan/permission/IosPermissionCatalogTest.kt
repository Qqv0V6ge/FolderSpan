package com.folderspan.permission

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull

class IosPermissionCatalogTest {
    @Test
    fun listsLocalNetworkPermissionOnSupportedIosVersions() {
        val permission = PlatformPermissionProvider.permissions()
            .find { it.id == PermissionIds.LocalNetwork }

        assertNotNull(permission)
        assertEquals(PermissionAction.Request, permission.action)
    }

    @Test
    fun notificationsUseSettingsAfterAuthorizationWasDenied() {
        val permission = PlatformPermissionProvider.permissions()
            .single { it.id == PermissionIds.Notifications }

        assertEquals(PermissionAction.RequestOnce, permission.action)
    }

    @Test
    fun localNetworkStatusDoesNotClaimToReadAnUnavailableSystemSwitch() = runTest {
        val permission = PlatformPermission(
            PermissionIds.LocalNetwork,
            PermissionAction.Request,
            title = "Local network",
            description = "Local network",
        )

        assertEquals(PermissionStatus.Unknown, PlatformPermissionProvider.status(permission))
    }

    @Test
    fun omitsLocalNetworkPermissionBeforeIosFourteen() {
        assertFalse(iosPlatformPermissions(13).any { it.id == PermissionIds.LocalNetwork })
        assertNotNull(iosPlatformPermissions(14).find { it.id == PermissionIds.LocalNetwork })
    }

    @Test
    fun fileAccessExplainsPerLocationAuthorizationInsteadOfRequestingGlobalAccess() {
        val permission = iosPlatformPermissions(14).single { it.id == PermissionIds.FilesAndFolders }

        assertEquals(PermissionAction.ShowInstructions, permission.action)
    }

    @Test
    fun fileAccessStatusCannotClaimAllSandboxAndSelectedLocationsAreAuthorized() = runTest {
        val permission = PlatformPermission(
            PermissionIds.FilesAndFolders,
            PermissionAction.ShowInstructions,
            title = "Files and folders",
            description = "Access to selected locations",
        )

        assertEquals(PermissionStatus.Unknown, PlatformPermissionProvider.status(permission))
    }

    @Test
    fun fileAccessDoesNotRequestOrOpenAPlaceholderFilePicker() {
        val permission = PlatformPermission(
            PermissionIds.FilesAndFolders,
            PermissionAction.ShowInstructions,
            title = "Files and folders",
            description = "Access to selected locations",
        )
        var result: PermissionStatus? = null

        PlatformPermissionProvider.request(permission) { result = it }

        assertEquals(PermissionStatus.Unknown, result)
    }
}
