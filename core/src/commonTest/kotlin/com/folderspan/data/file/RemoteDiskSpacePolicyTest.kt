package com.folderspan.data.file

import com.folderspan.data.main.device.DeviceType
import kotlin.test.Test
import kotlin.test.assertEquals

class RemoteDiskSpacePolicyTest {
    @Test
    fun iosRemoteRootsKeepPathsWithoutExposingDeviceCapacity() {
        val local = PathInfo("/Documents", 512_000L, 123_000L)

        val remote = local.forRemoteAccess(DeviceType.IOS)

        assertEquals(PathInfo("/Documents", 0L, 0L), remote)
        assertEquals(512_000L, local.totalSpace)
        assertEquals(123_000L, local.freeSpace)
    }

    @Test
    fun otherPlatformsKeepTheirExistingRemoteCapacityInformation() {
        val local = PathInfo("/storage", 512_000L, 123_000L)

        listOf(DeviceType.Android, DeviceType.JVM, DeviceType.JS).forEach { platform ->
            assertEquals(local, local.forRemoteAccess(platform, "Linux"))
        }
    }

    @Test
    fun macRemoteRootsAlsoHideDeviceCapacity() {
        val local = PathInfo("/Users/shared", 512_000L, 123_000L)
        assertEquals(PathInfo(local.path, 0L, 0L), local.forRemoteAccess(DeviceType.JVM, "Mac OS X"))
    }
}
