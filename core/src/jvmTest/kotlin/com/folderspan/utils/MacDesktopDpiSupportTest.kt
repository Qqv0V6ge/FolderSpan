package com.folderspan.utils

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class MacDesktopDpiSupportTest {
    @Test
    fun parseSystemProfilerScaleUsesPrimaryDisplayUiLooksLike() {
        val output = """
            Graphics/Displays:

                Apple M3:

                  Chipset Model: Apple M3
                  Type: GPU

                    Built-in Retina Display:

                      Display Type: Built-in Retina LCD
                      Resolution: 3024 x 1964 Retina
                      UI Looks like: 1512 x 982
                      Main Display: Yes
                      Online: Yes

                    Dell U2720Q:

                      Resolution: 3840 x 2160
                      UI Looks like: 3008 x 1692
                      Main Display: No
                      Online: Yes
        """.trimIndent()

        assertEquals(2f, MacDesktopDpiSupport.parseSystemProfilerScale(output))
    }

    @Test
    fun parseSystemProfilerScaleFallsBackToDefaultRetinaScale() {
        val output = """
            Graphics/Displays:

                Apple M3:

                    Built-in Retina Display:

                      Resolution: 3456 x 2234 Retina
                      Main Display: Yes
                      Online: Yes
        """.trimIndent()

        assertEquals(2f, MacDesktopDpiSupport.parseSystemProfilerScale(output))
    }

    @Test
    fun parseAppleDisplayScaleFactorSupportsNumericOutput() {
        assertEquals(1.5f, MacDesktopDpiSupport.parseAppleDisplayScaleFactor("1.5"))
    }

    @Test
    fun detectSystemScaleSkipsNonMac() {
        assertNull(MacDesktopDpiSupport.detectSystemScale(osName = "Linux"))
    }
}
