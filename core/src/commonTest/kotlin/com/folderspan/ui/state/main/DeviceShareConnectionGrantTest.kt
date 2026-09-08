package com.folderspan.ui.state.main

import com.folderspan.data.main.device.DeviceType
import com.folderspan.data.file.FileSimpleInfo
import com.folderspan.service.data.SocketDevice
import com.folderspan.ui.state.device.DeviceSharePathGrant
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.test.assertEquals

class DeviceShareConnectionGrantTest {
    @Test
    fun matchesDeviceFingerprintAndNonceBeforeExpiry() {
        val device = testDevice()
        val grant = DeviceShareConnectionGrant(
            deviceId = device.id,
            tlsFingerprintSha256 = device.tlsFingerprintSha256,
            nonce = "nonce-1",
            expiresAtMillis = 2_000L,
        )

        assertTrue(grant.matches(device, "nonce-1", nowMillis = 1_000L))
    }

    @Test
    fun rejectsWrongNonceFingerprintDeviceOrExpiredGrant() {
        val device = testDevice()
        val grant = DeviceShareConnectionGrant(
            deviceId = device.id,
            tlsFingerprintSha256 = device.tlsFingerprintSha256,
            nonce = "nonce-1",
            expiresAtMillis = 2_000L,
        )

        assertFalse(grant.matches(device, "nonce-2", nowMillis = 1_000L))
        assertFalse(grant.matches(device.withCopy(tlsFingerprintSha256 = "different"), "nonce-1", nowMillis = 1_000L))
        assertFalse(grant.matches(device.withCopy(id = "other-device"), "nonce-1", nowMillis = 1_000L))
        assertFalse(grant.matches(device, "nonce-1", nowMillis = 2_001L))
    }

    @Test
    fun pathScopeAllowsSharedFileAndDirectoryTreeOnly() {
        val device = testDevice()
        val grant = DeviceShareConnectionGrant(
            deviceId = device.id,
            tlsFingerprintSha256 = device.tlsFingerprintSha256,
            nonce = "nonce-1",
            expiresAtMillis = 2_000L,
            allowedPaths = listOf(
                DeviceSharePathGrant("/shared/file.txt", isDirectory = false),
                DeviceSharePathGrant("/shared/photos", isDirectory = true),
            ),
        )

        assertTrue(grant.pathScope.allowsContentPath("/shared/file.txt"))
        assertFalse(grant.pathScope.allowsContentPath("/shared/file.txt/child"))
        assertTrue(grant.pathScope.allowsContentPath("/shared/photos/2026/photo.jpg"))
        assertFalse(grant.pathScope.allowsContentPath("/shared/private.txt"))
        assertFalse(grant.pathScope.allowsContentPath("/shared/photos/../private.txt"))
    }

    @Test
    fun parentListingIsAllowedButFiltersUnsharedChildren() {
        val scope = DeviceShareConnectionGrant(
            deviceId = "device-1",
            tlsFingerprintSha256 = "AA:BB:CC",
            nonce = "nonce-1",
            expiresAtMillis = 2_000L,
            allowedPaths = listOf(DeviceSharePathGrant("/shared/photos", isDirectory = true)),
        ).pathScope

        assertTrue(scope.allowsListingPath("/"))
        assertTrue(scope.allowsListingPath("/shared"))
        assertFalse(scope.allowsListingPath("/private"))
        val visible = scope.filterListing(
            listOf(
                file("/shared/photos", isDirectory = true),
                file("/shared/private", isDirectory = true),
            )
        )
        assertEquals(listOf("/shared/photos"), visible.map { item -> item.path })
    }

    @Test
    fun sharePathsUseSingleVirtualRootAndRejectPhysicalOrTraversalPaths() {
        val scope = DeviceShareConnectionGrant(
            deviceId = "device-1",
            tlsFingerprintSha256 = "AA:BB:CC",
            nonce = "nonce-1",
            expiresAtMillis = 2_000L,
            allowedPaths = listOf(
                DeviceSharePathGrant("/private/report.txt", isDirectory = false),
                DeviceSharePathGrant("/private/photos", isDirectory = true),
            ),
        ).pathScope

        assertEquals(listOf("/report.txt", "/photos"), scope.virtualRoots.map { root -> root.path })
        assertEquals("/private/report.txt", scope.resolveVirtualContentPath("/report.txt"))
        assertEquals("/private/photos/2026/photo.jpg", scope.resolveVirtualContentPath("/photos/2026/photo.jpg"))
        assertEquals(null, scope.resolveVirtualContentPath("/"))
        assertEquals(null, scope.resolveVirtualContentPath("/private/photos"))
        assertEquals(null, scope.resolveVirtualContentPath("/photos/../report.txt"))
    }

    private fun testDevice(): SocketDevice {
        return SocketDevice(
            id = "device-1",
            name = "Device",
            pathSeparator = "/",
            host = "10.0.0.2",
            port = 12040,
            type = DeviceType.JVM,
            httpsPort = 12040,
            tlsFingerprintSha256 = "AA:BB:CC",
        )
    }

    private fun file(path: String, isDirectory: Boolean): FileSimpleInfo {
        return FileSimpleInfo(
            name = path.substringAfterLast('/'),
            isDirectory = isDirectory,
            isHidden = false,
            path = path,
            mineType = "",
            size = 0L,
            createdDate = 0L,
            updatedDate = 0L,
        )
    }
}
