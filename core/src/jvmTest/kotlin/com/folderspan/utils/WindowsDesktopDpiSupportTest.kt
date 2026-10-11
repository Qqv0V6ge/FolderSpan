package com.folderspan.utils

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class WindowsDesktopDpiSupportTest {
    @Test
    fun parseRegistryDpiScaleSupportsHexDword() {
        val output = """
            HKEY_CURRENT_USER\Control Panel\Desktop\WindowMetrics
                AppliedDPI    REG_DWORD    0x00000090
        """.trimIndent()

        assertEquals(1.5f, WindowsDesktopDpiSupport.parseRegistryDpiScale(output))
    }

    @Test
    fun parseRegistryDpiScaleSupportsDecimalDword() {
        val output = """
            HKEY_CURRENT_USER\Control Panel\Desktop
                LogPixels    REG_DWORD    120
        """.trimIndent()

        assertEquals(1.25f, WindowsDesktopDpiSupport.parseRegistryDpiScale(output))
    }

    @Test
    fun parsePlainDpiScaleSupportsPowerShellOutput() {
        assertEquals(1.5f, WindowsDesktopDpiSupport.parsePlainDpiScale("144"))
    }

    @Test
    fun detectSystemScaleSkipsNonWindows() {
        assertNull(WindowsDesktopDpiSupport.detectSystemScale(osName = "Linux"))
    }
}
