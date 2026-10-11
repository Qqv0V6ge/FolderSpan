package com.folderspan

import kotlin.test.Test
import kotlin.test.assertEquals

class PlatformDeviceNameJvmTest {
    @Test
    fun deviceNameIsDerivedFromCurrentSystemProperties() {
        val expected = "${System.getProperty("user.name")}-${System.getProperty("os.name")}"

        assertEquals(expected, currentDeviceName())
    }
}
