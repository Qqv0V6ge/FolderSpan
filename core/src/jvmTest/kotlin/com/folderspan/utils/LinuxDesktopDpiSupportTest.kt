package com.folderspan.utils

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LinuxDesktopDpiSupportTest {
    @Test
    fun parseGnomeDisplayConfigScaleExtractsPrimaryScale() {
        val output = """
            (uint32 11, [], [(0, 0, 1.5, uint32 0, true, [('eDP-1', 'BOE', '0x0c11', '0x00000000')], @a{sv} {}), (1920, 0, 1, uint32 0, false, [('DP-3', 'HWV', 'ZQE-CBA', '0xc080f622')], @a{sv} {})], {'layout-mode': <uint32 1>})
        """.trimIndent()

        assertEquals(1.5f, LinuxDesktopDpiSupport.parseGnomeDisplayConfigScale(output))
    }

    @Test
    fun parseGnomeMonitorsXmlScaleUsesPrimaryScale() {
        val xml = """
            <monitors version="2">
              <configuration>
                <logicalmonitor>
                  <x>0</x>
                  <y>0</y>
                  <scale>1.5</scale>
                  <primary>yes</primary>
                </logicalmonitor>
              </configuration>
              <configuration>
                <logicalmonitor>
                  <x>0</x>
                  <y>0</y>
                  <scale>1</scale>
                  <primary>yes</primary>
                </logicalmonitor>
              </configuration>
              <configuration>
                <logicalmonitor>
                  <x>0</x>
                  <y>0</y>
                  <scale>1.5</scale>
                  <primary>yes</primary>
                </logicalmonitor>
              </configuration>
            </monitors>
        """.trimIndent()

        assertEquals(1.5f, LinuxDesktopDpiSupport.parseGnomeMonitorsXmlScale(xml))
    }

    @Test
    fun parseGnomeSettingsScaleHandlesDisabledValue() {
        assertNull(LinuxDesktopDpiSupport.parseGnomeSettingsScale("uint32 0"))
        assertEquals(2f, LinuxDesktopDpiSupport.parseGnomeSettingsScale("uint32 2"))
    }

    @Test
    fun parseKdeKscreenDoctorScaleUsesPrimaryOutput() {
        val output = """
            Output: 1 eDP-1 enabled connected priority 2 Panel Modes: 2880x1800@90*! Geometry: 0,0 1920x1200 Scale: 1.25 Rotation: 1 Overscan: 0 Vrr: incapable RgbRange: unknown
            Output: 2 HDMI-A-1 enabled connected primary priority 1 Samsung Electric Company 27” Modes: 3840x2160@60*! Geometry: 1920,0 2560x1440 Scale: 1.5 Rotation: 1 Overscan: 0 Vrr: incapable RgbRange: unknown
        """.trimIndent()

        assertEquals(1.5f, LinuxDesktopDpiSupport.parseKdeKscreenDoctorScale(output))
    }

    @Test
    fun parseHyprctlMonitorsScaleUsesFocusedMonitor() {
        val output = """
            [{"id":0,"name":"eDP-1","description":"Built-in Display","make":"BOE","model":"0x0c11","serial":"","width":1920,"height":1200,"refreshRate":60.0,"x":0,"y":0,"activeWorkspace":{"id":1,"name":"1"},"specialWorkspace":{"id":0,"name":"special"},"reserved":[0,0,0,0],"scale":1.5,"transform":0,"focused":true,"dpmsStatus":true,"vrr":false,"solitary":"0","activelyTearing":false,"directScanoutTo":false,"disabled":false,"currentFormat":"XRGB8888","mirrorOf":"none","availableModes":["2880x1800@60.00"]}]
        """.trimIndent()

        assertEquals(1.5f, LinuxDesktopDpiSupport.parseHyprctlMonitorsScale(output))
    }

    @Test
    fun parseHyprlandConfigScaleUsesMonitorScaleField() {
        val config = """
            monitor = eDP-1, preferred, auto, 1.5
            monitor = HDMI-A-1, 2560x1440@144, 1920x0, 1
        """.trimIndent()

        assertEquals(1.5f, LinuxDesktopDpiSupport.parseHyprlandConfigScale(config))
    }

    @Test
    fun parseScaleOutputSupportsDesktopCommandFormats() {
        assertEquals(1.5f, LinuxDesktopDpiSupport.parseScaleOutput("(1.5,)"))
        assertEquals(2f, LinuxDesktopDpiSupport.parseScaleOutput("uint32 2"))
    }

    @Test
    fun parseDpiScaleOutputSupportsXfceAndDeepinFormats() {
        assertEquals(1.5f, LinuxDesktopDpiSupport.parseDpiScaleOutput("144"))
        assertEquals(1.5f, LinuxDesktopDpiSupport.parseDpiScaleOutput("147456", divisor = 1024f))
    }

    @Test
    fun resolveHyprlandConfigPathReturnsNullWhenConfigDoesNotExist() {
        val path = LinuxDesktopDpiSupport.resolveHyprlandConfigPath(
            configPath = "/tmp/not-found.conf",
            homePath = "/tmp"
        )

        assertNull(path)
    }

    @Test
    fun desktopSessionDetectorsRecognizeNewDesktopFamilies() {
        assertTrue(LinuxDesktopDpiSupport.isXfceSession(currentDesktop = "XFCE"))
        assertTrue(LinuxDesktopDpiSupport.isCinnamonSession(currentDesktop = "X-Cinnamon"))
        assertTrue(LinuxDesktopDpiSupport.isMateSession(currentDesktop = "MATE"))
        assertTrue(LinuxDesktopDpiSupport.isDeepinSession(currentDesktop = "DDE"))
        assertTrue(LinuxDesktopDpiSupport.isDeepinSession(desktopSession = "deepin"))
        assertTrue(LinuxDesktopDpiSupport.isDeepinSession(gdmSession = "uos"))
        assertFalse(LinuxDesktopDpiSupport.isXfceSession(currentDesktop = "GNOME"))
    }

    @Test
    fun detectSystemScaleSkipsNonLinux() {
        assertNull(LinuxDesktopDpiSupport.detectSystemScale(osName = "Windows 11"))
    }
}
