package com.folderspan.ui.state.device

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class DevicePermissionPathTest {
    @Test
    fun windowsVirtualRootResolvesToDriveRoot() {
        assertEquals("c:\\", permissionBoundaryRoot("c:\\program files (x86)", "\\"))
        assertNull(permissionBoundaryRoot("relative\\path", "\\"))
        assertEquals("/", permissionBoundaryRoot("/usr", "/"))
    }
}
