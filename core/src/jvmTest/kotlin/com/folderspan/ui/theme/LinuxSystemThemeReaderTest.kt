package com.folderspan.ui.theme

import java.nio.file.Path
import java.nio.file.Paths
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LinuxSystemThemeReaderTest {
    @Test
    fun portalParserHandlesBothCommandFormatsWithoutTreatingNoPreferenceAsLight() {
        assertEquals(1L, LinuxThemeParser.portalColorScheme("(<<uint32 1>>,)"))
        assertEquals(2L, LinuxThemeParser.portalColorScheme("variant variant uint32 2"))
        assertEquals(0L, LinuxThemeParser.portalColorScheme("(<uint32 0>,)"))
        assertEquals(4_294_967_295L, LinuxThemeParser.portalColorScheme("(uint32 4294967295,)"))
        assertNull(LinuxThemeParser.portalColorScheme("Error: no such interface"))
        assertNull(LinuxThemeParser.portalColorScheme("(<int32 1>,)"))
    }

    @Test
    fun explicitPortalPreferenceWinsOverDesktopAndToolkitSettings() {
        val fixture = Fixture("GNOME")
        fixture.portal = "(<<uint32 2>>,)"
        fixture.settings["org.gnome.desktop.interface:color-scheme"] = "'prefer-dark'"
        fixture.files["/home/test/.config/gtk-3.0/settings.ini"] = "[Settings]\ngtk-theme-name=Adwaita-dark"

        assertFalse(fixture.reader.read()!!)
        assertEquals(listOf("gdbus"), fixture.commands.map { it.first() })
    }

    @Test
    fun noPortalPreferenceFallsBackToDesktopWithoutCallingSecondPortalClient() {
        val fixture = Fixture("ubuntu:GNOME")
        fixture.portal = "(<<uint32 0>>,)"
        fixture.settings["org.gnome.desktop.interface:color-scheme"] = "'prefer-dark'"

        assertTrue(fixture.reader.read()!!)
        assertFalse(fixture.commands.any { it.first() == "dbus-send" })
    }

    @Test
    fun dbusSendReadsPortalWhenGdbusIsUnavailable() {
        val fixture = Fixture("KDE")
        fixture.dbusPortal = "method return\n variant variant uint32 1"

        assertTrue(fixture.reader.read()!!)
        assertEquals(listOf("gdbus", "dbus-send"), fixture.commands.map { it.first() })
    }

    @Test
    fun futurePortalValuesFallBackToCurrentDesktop() {
        val fixture = Fixture("GNOME")
        fixture.portal = "(<<uint32 15>>,)"
        fixture.settings["org.gnome.desktop.interface:color-scheme"] = "'prefer-light'"

        assertFalse(fixture.reader.read()!!)
    }

    @Test
    fun cinnamonUsesItsOwnSettingsEvenWithGnomeInTheSessionName() {
        val fixture = Fixture("X-Cinnamon:GNOME")
        fixture.settings["org.cinnamon.desktop.interface:color-scheme"] = "'default'"
        fixture.settings["org.cinnamon.desktop.interface:gtk-theme"] = "'Mint-Y-Dark-Aqua'"
        fixture.settings["org.gnome.desktop.interface:color-scheme"] = "'prefer-light'"

        assertTrue(fixture.reader.read()!!)
        fixture.settings["org.cinnamon.desktop.interface:gtk-theme"] = "'Mint-Y-Aqua'"
        assertFalse(fixture.reader.read()!!)
        assertFalse(fixture.commands.any { "org.gnome.desktop.interface" in it })
    }

    @Test
    fun kdeUsesTheWindowColorAndIgnoresStaleGnomeSettings() {
        val fixture = Fixture("KDE")
        fixture.settings["org.gnome.desktop.interface:color-scheme"] = "'prefer-dark'"
        fixture.files["/home/test/.config/kdeglobals"] = """
            [General]
            ColorScheme=CustomNight
            [Colors:Window]
            BackgroundNormal=239,240,241
        """.trimIndent()

        assertFalse(fixture.reader.read()!!)
        assertFalse(fixture.commands.any { it.first() == "gsettings" })
    }

    @Test
    fun kdeFindsCustomNamedPaletteInXdgDataDirectories() {
        val fixture = Fixture("plasma")
        fixture.environment["XDG_CONFIG_HOME"] = fixture.path("/custom/config")
        fixture.environment["XDG_DATA_DIRS"] = fixture.paths("/custom/data", "/usr/share")
        fixture.files["/custom/config/kdeglobals"] = "[General]\nColorScheme=Ocean"
        fixture.files["/custom/data/color-schemes/Ocean.colors"] =
            "[Colors:Window]\nBackgroundNormal=30,35,40"

        assertTrue(fixture.reader.read()!!)
    }

    @Test
    fun lxqtReadsPaletteAndRefreshesItOnTheNextRead() {
        val fixture = Fixture("LXQt")
        fixture.files["/home/test/.config/lxqt/lxqt.conf"] = "[Palette]\nwindow_color=#242424"

        assertTrue(fixture.reader.read()!!)
        fixture.files["/home/test/.config/lxqt/lxqt.conf"] = "[Palette]\nwindow_color=#f0f0f0"
        assertFalse(fixture.reader.read()!!)
    }

    @Test
    fun xfceUsesActiveThemeAndReadsItAgainAfterChanges() {
        val fixture = Fixture("XFCE")
        fixture.xfceTheme = "Greybird-dark"

        assertTrue(fixture.reader.read()!!)
        fixture.xfceTheme = "Greybird"
        assertFalse(fixture.reader.read()!!)
    }

    @Test
    fun missingUserKeysFallBackToXdgSystemConfig() {
        val fixture = Fixture("LXQt")
        fixture.environment["XDG_CONFIG_DIRS"] = fixture.paths("/etc/custom", "/etc/xdg")
        fixture.files["/home/test/.config/lxqt/lxqt.conf"] = "[Qt]\nstyle=Fusion"
        fixture.files["/etc/custom/lxqt/lxqt.conf"] = "[Palette]\nwindow_color=#212121"

        assertTrue(fixture.reader.read()!!)
    }

    @Test
    fun gtkFalsePreferDarkFlagDoesNotOverrideAnExplicitDarkTheme() {
        val fixture = Fixture("unknown")
        fixture.files["/home/test/.config/gtk-3.0/settings.ini"] = """
            [Settings]
            gtk-application-prefer-dark-theme=false
            gtk-theme-name=Adwaita-dark
        """.trimIndent()

        assertTrue(fixture.reader.read()!!)
        assertFalse(fixture.commands.any { it.first() == "gsettings" })
    }

    @Test
    fun unknownThemesAndMissingSettingsRemainUnknown() {
        val fixture = Fixture("GNOME")
        fixture.settings["org.gnome.desktop.interface:gtk-theme"] = "'My Custom Theme'"

        assertNull(fixture.reader.read())
        assertNull(LinuxThemeParser.themeName("NotDark"))
        assertNull(LinuxThemeParser.colorSchemePreference("'default'"))
        assertEquals(false, LinuxThemeParser.themeName("Arc-Darker"))
    }

    @Test
    fun iniParserRespectsSectionsCommentsAndQuotedValues() {
        val config = """
            # window_color=#ffffff
            [Qt]
            window_color=#ffffff
            [Palette]
            ; window_color=#ffffff
            window_color = "#232323"
        """.trimIndent()

        assertEquals("#232323", LinuxThemeParser.iniValue(config, "Palette", "window_color"))
        assertNull(LinuxThemeParser.iniValue(config, "Missing", "window_color"))
    }

    @Test
    fun backgroundParserAcceptsRgbAndQColorFormatsAndRejectsInvalidColors() {
        assertEquals(true, LinuxThemeParser.backgroundColor("35,38,41"))
        assertEquals(false, LinuxThemeParser.backgroundColor("#eff0f1"))
        assertEquals(true, LinuxThemeParser.backgroundColor("#ff232629"))
        assertNull(LinuxThemeParser.backgroundColor("-1,0,0"))
        assertNull(LinuxThemeParser.backgroundColor("256,0,0"))
        assertNull(LinuxThemeParser.backgroundColor("#zz1122"))
        assertNull(LinuxThemeParser.backgroundColor("35,38"))
    }

    private class Fixture(desktop: String) {
        // Purely virtual paths: an absolute UNC-compatible root also works on Windows,
        // without drive-letter colons conflicting with Linux XDG directory separators.
        // All file reads are injected below; this fixture never accesses the host or network.
        private val pathRoot = Paths.get("//folderspan-theme-fixture/root")

        fun path(value: String): String = pathRoot.resolve(value.trimStart('/')).toString()
        fun paths(vararg values: String): String = values.joinToString(":") { path(it) }

        val environment = mutableMapOf("XDG_CURRENT_DESKTOP" to desktop)
        val settings = mutableMapOf<String, String>()
        val files = mutableMapOf<String, String>()
        val commands = mutableListOf<List<String>>()
        var portal: String? = null
        var dbusPortal: String? = null
        var xfceTheme: String? = null

        val reader = LinuxSystemThemeReader(
            environment = environment::get,
            command = { command ->
                commands += command
                when (command.first()) {
                    "gdbus" -> portal
                    "dbus-send" -> dbusPortal
                    "gsettings" -> settings["${command[2]}:${command[3]}"]
                    "xfconf-query" -> xfceTheme
                    else -> null
                }
            },
            readFile = { path: Path ->
                if (path.startsWith(pathRoot)) {
                    files["/" + pathRoot.relativize(path).toString().replace('\\', '/')]
                } else null
            },
            userHome = Paths.get(path("/home/test"))
        )
    }
}
