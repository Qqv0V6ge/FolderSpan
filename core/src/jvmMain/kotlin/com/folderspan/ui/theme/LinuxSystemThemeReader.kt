package com.folderspan.ui.theme

import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.util.concurrent.TimeUnit

internal class LinuxSystemThemeReader(
    private val environment: (String) -> String? = System::getenv,
    private val command: (List<String>) -> String? = ::runThemeCommand,
    private val readFile: (Path) -> String? = ::readThemeFile,
    private val userHome: Path = Paths.get(System.getProperty("user.home"))
) {
    fun read(): Boolean? {
        // Portal values 0 and unknown future values mean no preference, not light.
        when (readPortalColorScheme()) {
            1L -> return true
            2L -> return false
        }

        val desktop = listOf("XDG_CURRENT_DESKTOP", "DESKTOP_SESSION", "GDMSESSION")
            .mapNotNull(environment)
            .joinToString(":")
            .uppercase()
            .split(Regex("[^A-Z0-9]+"))
            .toSet()
        val desktopTheme = when {
            "CINNAMON" in desktop -> readGsettings("org.cinnamon.desktop.interface")
            "KDE" in desktop || "PLASMA" in desktop -> readKdeTheme()
            "XFCE" in desktop || "XFCE4" in desktop -> readXfceTheme()
            "LXQT" in desktop -> readLxqtTheme()
            "MATE" in desktop -> readGsettings("org.mate.interface")
            desktop.any { it in setOf("GNOME", "UNITY", "BUDGIE") } ->
                readGsettings("org.gnome.desktop.interface")
            else -> null
        }
        return desktopTheme
            ?: environment("GTK_THEME")?.let(LinuxThemeParser::themeName)
            ?: readGtkTheme()
    }

    private fun readPortalColorScheme(): Long? {
        val gdbus = command(
            listOf(
                "gdbus", "call", "--session", "--timeout", "1",
                "--dest", "org.freedesktop.portal.Desktop",
                "--object-path", "/org/freedesktop/portal/desktop",
                "--method", "org.freedesktop.portal.Settings.Read",
                "org.freedesktop.appearance", "color-scheme"
            )
        )?.let(LinuxThemeParser::portalColorScheme)
        if (gdbus != null) return gdbus

        return command(
            listOf(
                "dbus-send", "--session", "--print-reply", "--reply-timeout=1000",
                "--dest=org.freedesktop.portal.Desktop", "/org/freedesktop/portal/desktop",
                "org.freedesktop.portal.Settings.Read",
                "string:org.freedesktop.appearance", "string:color-scheme"
            )
        )?.let(LinuxThemeParser::portalColorScheme)
    }

    private fun readGsettings(schema: String): Boolean? {
        val preference = command(listOf("gsettings", "get", schema, "color-scheme"))
            ?.let(LinuxThemeParser::colorSchemePreference)
        return preference ?: command(listOf("gsettings", "get", schema, "gtk-theme"))
            ?.let(LinuxThemeParser::themeName)
    }

    private fun readXfceTheme(): Boolean? = command(
        listOf("xfconf-query", "-c", "xsettings", "-p", "/Net/ThemeName")
    )?.let(LinuxThemeParser::themeName)

    private fun readKdeTheme(): Boolean? {
        configValue("kdeglobals", "Colors:Window", "BackgroundNormal")
            ?.let(LinuxThemeParser::backgroundColor)?.let { return it }
        val scheme = configValue("kdeglobals", "General", "ColorScheme") ?: return null
        // KDE can keep only a scheme name in kdeglobals. Read that scheme's actual
        // window color before considering explicit Dark/Light variants in its name.
        if (scheme.isNotBlank() && '/' !in scheme && '\\' !in scheme && scheme != "." && scheme != "..") {
            dataDirectories().forEach { directory ->
                val palette = readFile(directory.resolve("color-schemes").resolve("$scheme.colors"))
                palette?.let { LinuxThemeParser.iniValue(it, "Colors:Window", "BackgroundNormal") }
                    ?.let(LinuxThemeParser::backgroundColor)?.let { return it }
            }
        }
        return LinuxThemeParser.themeName(scheme)
    }

    private fun readLxqtTheme(): Boolean? =
        configValue("lxqt/lxqt.conf", "Palette", "window_color")
            ?.let(LinuxThemeParser::backgroundColor)

    private fun readGtkTheme(): Boolean? {
        for (version in listOf("4.0", "3.0")) {
            val relativePath = "gtk-$version/settings.ini"
            val preferDark = configValue(relativePath, "Settings", "gtk-application-prefer-dark-theme")
            if (preferDark == "1" || preferDark.equals("true", ignoreCase = true)) return true
            configValue(relativePath, "Settings", "gtk-theme-name")
                ?.let(LinuxThemeParser::themeName)?.let { return it }
        }
        return null
    }

    private fun configValue(relativePath: String, section: String, key: String): String? =
        configDirectories().firstNotNullOfOrNull { directory ->
            readFile(directory.resolve(relativePath))?.let { LinuxThemeParser.iniValue(it, section, key) }
        }

    private fun configDirectories(): List<Path> =
        listOf(environmentPath("XDG_CONFIG_HOME") ?: userHome.resolve(".config")) +
            environmentDirectories("XDG_CONFIG_DIRS", "/etc/xdg")

    private fun dataDirectories(): List<Path> =
        listOf(environmentPath("XDG_DATA_HOME") ?: userHome.resolve(".local/share")) +
            environmentDirectories("XDG_DATA_DIRS", "/usr/local/share:/usr/share")

    private fun environmentPath(name: String): Path? =
        environment(name)?.takeIf(String::isNotBlank)?.let(Paths::get)?.takeIf(Path::isAbsolute)

    private fun environmentDirectories(name: String, fallback: String): List<Path> =
        (environment(name)?.takeIf(String::isNotBlank) ?: fallback)
            .split(':').filter(String::isNotBlank).map(Paths::get).filter(Path::isAbsolute)
}

internal object LinuxThemeParser {
    fun portalColorScheme(output: String): Long? =
        Regex("""\buint32\s+(\d+)\b""").find(output)?.groupValues?.get(1)?.toLongOrNull()

    fun colorSchemePreference(output: String): Boolean? = when (unquote(output).lowercase()) {
        "prefer-dark" -> true
        "prefer-light" -> false
        else -> null
    }

    fun themeName(output: String): Boolean? {
        val name = unquote(output).lowercase()
        val tokens = name.split(Regex("[^a-z0-9]+"))
        return when {
            "dark" in tokens || name == "breezedark" || name == "highcontrastinverse" -> true
            "light" in tokens -> false
            name in setOf("adwaita", "breeze", "oxygen", "arc", "greybird", "yaru", "highcontrast") -> false
            name == "arc-darker" || name == "greybird-accessibility" -> false
            name == "mint-x" || name.startsWith("mint-x-") -> false
            name == "mint-y" || name.startsWith("mint-y-") || name.startsWith("yaru-") -> false
            else -> null
        }
    }

    fun iniValue(config: String, section: String, key: String): String? {
        var currentSection = ""
        var value: String? = null
        config.lineSequence().forEach { rawLine ->
            val line = rawLine.trim()
            when {
                line.startsWith('#') || line.startsWith(';') -> Unit
                line.startsWith('[') && line.endsWith(']') -> currentSection = line.substring(1, line.lastIndex)
                currentSection == section && line.substringBefore('=').trim() == key && '=' in line ->
                    value = unquote(line.substringAfter('=')).takeIf(String::isNotBlank)
            }
        }
        return value
    }

    fun backgroundColor(output: String): Boolean? {
        val value = unquote(output)
        val channels = if (value.startsWith('#')) {
            val hex = value.removePrefix("#").let { if (it.length == 8) it.drop(2) else it }
            if (hex.length != 6) return null
            hex.chunked(2).map { it.toIntOrNull(16) ?: return null }
        } else {
            val components = value.split(',')
            if (components.size != 3) return null
            components.map { it.trim().toIntOrNull()?.takeIf { channel -> channel in 0..255 } ?: return null }
        }
        // Infer appearance from the configured window surface, including palettes
        // with custom names, without treating every unfamiliar theme as light.
        return channels[0] * 299 + channels[1] * 587 + channels[2] * 114 < 128_000
    }

    private fun unquote(value: String): String = value.trim().removeSurrounding("'").removeSurrounding("\"")
}

private fun readThemeFile(path: Path): String? = try {
    if (Files.isRegularFile(path) && Files.size(path) <= 256 * 1024) Files.readString(path) else null
} catch (_: IOException) {
    null
} catch (_: SecurityException) {
    null
}

private fun runThemeCommand(command: List<String>): String? {
    var process: Process? = null
    try {
        process = ProcessBuilder(command).redirectError(ProcessBuilder.Redirect.DISCARD).start()
        if (!process.waitFor(1_200, TimeUnit.MILLISECONDS) || process.exitValue() != 0) return null
        return process.inputStream.use { it.readNBytes(16 * 1024).toString(Charsets.UTF_8).trim() }
            .takeIf(String::isNotBlank)
    } catch (_: IOException) {
        return null
    } catch (_: SecurityException) {
        return null
    } finally {
        process?.let(::closeThemeProcess)
    }
}

private fun closeThemeProcess(process: Process) {
    var interrupted = false
    try {
        if (process.isAlive) {
            process.destroy()
            try {
                if (!process.waitFor(100, TimeUnit.MILLISECONDS)) process.destroyForcibly()
            } catch (_: InterruptedException) {
                interrupted = true
                process.destroyForcibly()
            }
            try {
                process.waitFor(500, TimeUnit.MILLISECONDS)
            } catch (_: InterruptedException) {
                interrupted = true
                process.destroyForcibly()
            }
        }
    } finally {
        runCatching { process.inputStream.close() }
        runCatching { process.errorStream.close() }
        runCatching { process.outputStream.close() }
        if (interrupted) Thread.currentThread().interrupt()
    }
}
