package com.folderspan.root

import com.folderspan.permission.PermissionStatus
import kotlin.test.Test
import kotlin.test.assertEquals

class RootPermissionStatusMapperTest {
    @Test
    fun mapsRootStatesToPlatformPermissionStatuses() {
        assertEquals(PermissionStatus.Unsupported, RootPermissionState.Unsupported.toPermissionStatus())
        assertEquals(PermissionStatus.NotDetermined, RootPermissionState.NotDetermined.toPermissionStatus())
        assertEquals(PermissionStatus.NotDetermined, RootPermissionState.Requesting.toPermissionStatus())
        assertEquals(PermissionStatus.Denied, RootPermissionState.Denied.toPermissionStatus())
        assertEquals(PermissionStatus.Denied, RootPermissionState.Failed(IllegalStateException("boom")).toPermissionStatus())
        assertEquals(PermissionStatus.Granted, RootPermissionState.Granted.toPermissionStatus())
    }
}
