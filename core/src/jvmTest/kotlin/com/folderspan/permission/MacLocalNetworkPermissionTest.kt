package com.folderspan.permission

import java.net.Inet6Address
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class MacLocalNetworkPermissionTest {
    @Test
    fun macOsWithLocalNetworkPrivacyListsThePermission() {
        val permission = desktopPlatformPermissions("Mac OS X", "15.1")
            .single { it.id == PermissionIds.LocalNetwork }

        assertEquals(PermissionAction.Request, permission.action)
        assertTrue(permission.description.isNotBlank())
    }

    @Test
    fun onlySupportedMacOsVersionsUseTheNativePrivacyRequest() {
        assertFalse(desktopPlatformPermissions("Mac OS X", "14.7").any { it.id == PermissionIds.LocalNetwork })
        listOf("Windows 11" to "10.0", "Linux" to "6.12").forEach { (os, version) ->
            assertFalse(MacLocalNetworkPermission.isSupported(os, version))
            assertEquals(
                PermissionAction.OpenSettings,
                desktopPlatformPermissions(os, version).single { it.id == PermissionIds.LocalNetwork }.action,
            )
        }
        assertTrue(MacLocalNetworkPermission.isSupported("Mac OS X", "26.0.1"))
    }

    @Test
    fun statusDoesNotPretendThatAnUnqueryablePermissionWasGrantedOrDenied() {
        assertEquals(PermissionStatus.Unknown, MacLocalNetworkPermission.status("Mac OS X", "15.1"))
        assertEquals(PermissionStatus.Unsupported, MacLocalNetworkPermission.status("Linux", "6.12"))
    }

    @Test
    fun probeKeepsTheLinkLocalPrefixAndInterfaceScope() {
        val bytes = ByteArray(16).also {
            it[0] = 0xfe.toByte()
            it[1] = 0x80.toByte()
            it[15] = 1
        }
        val source = Inet6Address.getByAddress(null, bytes, 7)
        val host = ByteArray(8) { (it + 2).toByte() }

        val target = localNetworkPermissionProbeAddress(source, host)

        assertTrue(target.isLinkLocalAddress)
        assertEquals(7, target.scopeId)
        assertContentEquals(bytes.copyOfRange(0, 8), target.address.copyOfRange(0, 8))
        assertContentEquals(host, target.address.copyOfRange(8, 16))
    }
}
