package com.folderspan.ui.screen.device

import com.folderspan.utils.WindowSizeClass
import kotlin.test.Test
import kotlin.test.assertEquals

class DevicePermissionsScreenTest {
    @Test
    fun permissionColumnsIncreaseFromExpandedWindows() {
        assertEquals(1, permissionColumnCount(WindowSizeClass.Compact))
        assertEquals(1, permissionColumnCount(WindowSizeClass.Medium))
        assertEquals(2, permissionColumnCount(WindowSizeClass.Expanded))
        assertEquals(3, permissionColumnCount(WindowSizeClass.Large))
        assertEquals(4, permissionColumnCount(WindowSizeClass.ExtraLarge))
    }
}
