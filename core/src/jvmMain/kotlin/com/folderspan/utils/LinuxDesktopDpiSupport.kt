package com.folderspan.utils

import org.w3c.dom.Element
import org.xml.sax.InputSource
import java.io.StringReader
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.util.concurrent.TimeUnit
import javax.xml.parsers.DocumentBuilderFactory

internal object LinuxDesktopDpiSupport {
    private const val COMMAND_TIMEOUT_MILLIS = 1500L

    private val gnomeLogicalMonitorScaleRegex = Regex(
        """\((-?\d+),\s*(-?\d+),\s*([0-9]+(?:\.[0-9]+)?),\s*uint32\s+\d+,\s*(true|false),"""
    )
    private val gsettingsUintRegex = Regex("""uint32\s+(\d+)""")
    private val kdeScaleRegex = Regex("""\bScale:\s*([0-9]+(?:\.[0-9]+)?)""")
    private val hyprlandScaleRegex = Regex(""""scale"\s*:\s*([0-9]+(?:\.[0-9]+)?)""")
    private val hyprlandFocusedRegex = Regex(""""focused"\s*:\s*(true|false)""")
    private val numericValueRegex = Regex("""-?\d+(?:\.\d+)?""")

    fun detectSystemScale(osName: String = System.getProperty("os.name").orEmpty()): LinuxDesktopScaleDetection? {
        if (!DesktopDpiSupport.isLinuxOs(osName)) return null

        return LinuxDesktopScaleDetection(
            gnomeDisplayConfigScale = readGnomeDisplayConfigScale(),
            gnomeMonitorsXmlScale = readGnomeMonitorsXmlScale(),
            gnomeSettingsScale = readGnomeSettingsScale(),
            kdeKscreenDoctorScale = readKdeKscreenDoctorScale(),
            hyprlandHyprctlScale = readHyprlandHyprctlScale(),
            hyprlandConfigScale = readHyprlandConfigScale(),
            xfceWindowScale = readXfceWindowScale(),
            xfceDpiScale = readXfceDpiScale(),
            cinnamonScale = readCinnamonScale(),
            mateScale = readMateScale(),
            deepinDbusScale = readDeepinDbusScale(),
            deepinGsettingsScale = readDeepinGsettingsScale(),
            deepinWindowScale = readDeepinWindowScale(),
            deepinXftDpiScale = readDeepinXftDpiScale()
        )
    }

    internal fun readGnomeDisplayConfigScale(): Float? {
        if (!isGnomeSession()) return null
        val output = runCommand(
            "gdbus",
            "call",
            "--session",
            "--dest",
            "org.gnome.Mutter.DisplayConfig",
            "--object-path",
            "/org/gnome/Mutter/DisplayConfig",
            "--method",
            "org.gnome.Mutter.DisplayConfig.GetCurrentState"
        ) ?: return null
        return parseGnomeDisplayConfigScale(output)
    }

    internal fun readGnomeMonitorsXmlScale(): Float? {
        if (!isGnomeSession()) return null
        val path = Paths.get(System.getProperty("user.home"), ".config", "monitors.xml")
        if (!Files.isRegularFile(path)) return null
        val xml = runCatching { Files.readString(path) }.getOrNull() ?: return null
        return parseGnomeMonitorsXmlScale(xml)
    }

    internal fun readGnomeSettingsScale(): Float? {
        if (!isGnomeSession()) return null
        val output = runCommand(
            "gsettings",
            "get",
            "org.gnome.desktop.interface",
            "scaling-factor"
        ) ?: return null
        return parseGnomeSettingsScale(output)
    }

    internal fun readKdeKscreenDoctorScale(): Float? {
        if (!isKdeSession()) return null
        val output = runCommand("kscreen-doctor", "-o")
            ?: runCommand("kscreen-doctor", "--outputs")
            ?: return null
        return parseKdeKscreenDoctorScale(output)
    }

    internal fun readHyprlandHyprctlScale(): Float? {
        if (!isHyprlandSession()) return null
        val output = runCommand("hyprctl", "-j", "monitors") ?: return null
        return parseHyprctlMonitorsScale(output)
    }

    internal fun readHyprlandConfigScale(): Float? {
        if (!isHyprlandSession()) return null
        val path = resolveHyprlandConfigPath() ?: return null
        val config = runCatching { Files.readString(path) }.getOrNull() ?: return null
        return parseHyprlandConfigScale(config)
    }

    internal fun readXfceWindowScale(): Float? {
        if (!isXfceSession()) return null
        val output = runCommand(
            "xfconf-query",
            "-c",
            "xsettings",
            "-p",
            "/Gdk/WindowScalingFactor"
        ) ?: return null
        return parseScaleOutput(output)
    }

    internal fun readXfceDpiScale(): Float? {
        if (!isXfceSession()) return null
        val output = runCommand(
            "xfconf-query",
            "-c",
            "xsettings",
            "-p",
            "/Xft/DPI"
        ) ?: return null
        return parseDpiScaleOutput(output)
    }

    internal fun readCinnamonScale(): Float? {
        if (!isCinnamonSession()) return null
        val output = runCommand(
            "gsettings",
            "get",
            "org.cinnamon.desktop.interface",
            "scaling-factor"
        ) ?: return null
        return parseScaleOutput(output)
    }

    internal fun readMateScale(): Float? {
        if (!isMateSession()) return null
        val output = runCommand(
            "gsettings",
            "get",
            "org.mate.interface",
            "window-scaling-factor"
        ) ?: return null
        return parseScaleOutput(output)
    }

    internal fun readDeepinDbusScale(): Float? {
        if (!isDeepinSession()) return null
        val output = runCommand(
            "gdbus",
            "call",
            "--session",
            "--dest",
            "org.deepin.dde.XSettings1",
            "--object-path",
            "/org/deepin/dde/XSettings1",
            "--method",
            "org.deepin.dde.XSettings1.GetScaleFactor"
        ) ?: return null
        return parseScaleOutput(output)
    }

    internal fun readDeepinGsettingsScale(): Float? {
        if (!isDeepinSession()) return null
        val output = runCommand(
            "gsettings",
            "get",
            "com.deepin.xsettings",
            "scale-factor"
        ) ?: return null
        return parseScaleOutput(output)
    }

    internal fun readDeepinWindowScale(): Float? {
        if (!isDeepinSession()) return null
        val output = runCommand(
            "gsettings",
            "get",
            "com.deepin.xsettings",
            "window-scale"
        ) ?: return null
        return parseScaleOutput(output)
    }

    internal fun readDeepinXftDpiScale(): Float? {
        if (!isDeepinSession()) return null
        val output = runCommand(
            "gsettings",
            "get",
            "com.deepin.xsettings",
            "xft-dpi"
        ) ?: return null
        return parseDpiScaleOutput(output, divisor = 1024f)
    }

    internal fun parseGnomeDisplayConfigScale(output: String): Float? {
        val matches = gnomeLogicalMonitorScaleRegex.findAll(output)
            .mapNotNull { match ->
                val scale = match.groupValues[3].toFloatOrNull()?.let(DesktopDpiSupport::sanitizeScale)
                    ?: return@mapNotNull null
                ParsedScale(scale = scale, isPrimary = match.groupValues[4] == "true")
            }
            .toList()

        return matches.firstOrNull { it.isPrimary }?.scale ?: matches.firstOrNull()?.scale
    }

    internal fun parseGnomeMonitorsXmlScale(xml: String): Float? {
        return runCatching {
            val factory = DocumentBuilderFactory.newInstance()
            val builder = factory.newDocumentBuilder()
            val document = builder.parse(InputSource(StringReader(xml)))
            val configurations = document.getElementsByTagName("configuration")
            val primaryScales = mutableListOf<Float>()
            val allScales = mutableListOf<Float>()

            for (configurationIndex in 0 until configurations.length) {
                val configuration = configurations.item(configurationIndex) as? Element ?: continue
                val logicalMonitors = configuration.getElementsByTagName("logicalmonitor")
                for (logicalIndex in 0 until logicalMonitors.length) {
                    val logicalMonitor = logicalMonitors.item(logicalIndex) as? Element ?: continue
                    val scale = logicalMonitor.childText("scale")
                        ?.toFloatOrNull()
                        ?.let(DesktopDpiSupport::sanitizeScale)
                        ?: continue
                    allScales += scale
                    if (logicalMonitor.childText("primary").equals("yes", ignoreCase = true)) {
                        primaryScales += scale
                    }
                }
            }

            mostFrequentScale(primaryScales) ?: mostFrequentScale(allScales)
        }.getOrNull()
    }

    internal fun parseGnomeSettingsScale(output: String): Float? {
        val intScale = gsettingsUintRegex.find(output)
            ?.groupValues
            ?.getOrNull(1)
            ?.toIntOrNull()
            ?: return null
        if (intScale <= 0) return null
        return DesktopDpiSupport.sanitizeScale(intScale.toFloat())
    }

    internal fun parseKdeKscreenDoctorScale(output: String): Float? {
        val matches = splitKscreenDoctorOutputBlocks(output)
            .mapNotNull { block ->
                val scale = kdeScaleRegex.find(block)
                    ?.groupValues
                    ?.getOrNull(1)
                    ?.toFloatOrNull()
                    ?.let(DesktopDpiSupport::sanitizeScale)
                    ?: return@mapNotNull null
                ParsedScale(scale = scale, isPrimary = isKdePrimaryOutputBlock(block))
            }
            .toList()

        return matches.firstOrNull { it.isPrimary }?.scale ?: matches.firstOrNull()?.scale
    }

    internal fun parseHyprctlMonitorsScale(output: String): Float? {
        val jsonArray = extractJsonArray(output) ?: return null
        val matches = splitTopLevelJsonObjects(jsonArray)
            .mapNotNull { block ->
                val scale = hyprlandScaleRegex.find(block)
                    ?.groupValues
                    ?.getOrNull(1)
                    ?.toFloatOrNull()
                    ?.let(DesktopDpiSupport::sanitizeScale)
                    ?: return@mapNotNull null
                val isFocused = hyprlandFocusedRegex.find(block)
                    ?.groupValues
                    ?.getOrNull(1)
                    ?.toBooleanStrictOrNull()
                    ?: false
                ParsedScale(scale = scale, isPrimary = isFocused)
            }
            .toList()

        return matches.firstOrNull { it.isPrimary }?.scale ?: matches.firstOrNull()?.scale
    }

    internal fun parseHyprlandConfigScale(config: String): Float? {
        val scales = config.lineSequence()
            .map { line -> line.substringBefore('#').trim() }
            .filter { line -> line.startsWith("monitor", ignoreCase = true) }
            .mapNotNull { line ->
                val assignment = line.substringAfter('=', missingDelimiterValue = "")
                    .trim()
                    .takeIf { it.isNotEmpty() }
                    ?: return@mapNotNull null
                val scaleText = assignment.substringAfterLast(',', missingDelimiterValue = "")
                    .trim()
                    .takeIf { it.isNotEmpty() && !it.equals("auto", ignoreCase = true) }
                    ?: return@mapNotNull null
                scaleText.toFloatOrNull()?.let(DesktopDpiSupport::sanitizeScale)
            }
            .toList()

        return mostFrequentScale(scales)
    }

    internal fun parseScaleOutput(output: String): Float? {
        val rawScale = numericValueRegex.findAll(output)
            .mapNotNull { match -> match.value.toFloatOrNull() }
            .lastOrNull()
            ?: return null
        return DesktopDpiSupport.sanitizeScale(rawScale)
    }

    internal fun parseDpiScaleOutput(output: String, divisor: Float = 1f): Float? {
        if (!divisor.isFinite() || divisor <= 0f) return null
        val rawDpi = numericValueRegex.findAll(output)
            .mapNotNull { match -> match.value.toFloatOrNull() }
            .lastOrNull()
            ?: return null
        return DesktopDpiSupport.scaleFromRawDpi(rawDpi / divisor)
    }

    internal fun resolveHyprlandConfigPath(
        configPath: String? = System.getenv("HYPRLAND_CONFIG"),
        homePath: String = System.getProperty("user.home")
    ): Path? {
        val candidates = listOfNotNull(
            configPath?.takeIf { it.isNotBlank() },
            Paths.get(homePath, ".config", "hypr", "hyprland.conf").toString()
        )

        return candidates
            .map { candidate -> Paths.get(candidate) }
            .firstOrNull { path -> Files.isRegularFile(path) }
    }

    internal fun isGnomeSession(
        currentDesktop: String = System.getenv("XDG_CURRENT_DESKTOP").orEmpty(),
        desktopSession: String = System.getenv("DESKTOP_SESSION").orEmpty(),
        gdmSession: String = System.getenv("GDMSESSION").orEmpty()
    ): Boolean {
        return matchesDesktopEnvironment(currentDesktop, desktopSession, gdmSession, listOf("GNOME"))
    }

    internal fun isKdeSession(
        currentDesktop: String = System.getenv("XDG_CURRENT_DESKTOP").orEmpty(),
        desktopSession: String = System.getenv("DESKTOP_SESSION").orEmpty(),
        gdmSession: String = System.getenv("GDMSESSION").orEmpty()
    ): Boolean {
        return matchesDesktopEnvironment(currentDesktop, desktopSession, gdmSession, listOf("KDE", "PLASMA"))
    }

    internal fun isHyprlandSession(
        currentDesktop: String = System.getenv("XDG_CURRENT_DESKTOP").orEmpty(),
        desktopSession: String = System.getenv("DESKTOP_SESSION").orEmpty(),
        hyprlandInstanceSignature: String = System.getenv("HYPRLAND_INSTANCE_SIGNATURE").orEmpty()
    ): Boolean {
        return hyprlandInstanceSignature.isNotBlank() ||
            matchesDesktopEnvironment(currentDesktop, desktopSession, "", listOf("HYPRLAND"))
    }

    internal fun isXfceSession(
        currentDesktop: String = System.getenv("XDG_CURRENT_DESKTOP").orEmpty(),
        desktopSession: String = System.getenv("DESKTOP_SESSION").orEmpty(),
        gdmSession: String = System.getenv("GDMSESSION").orEmpty()
    ): Boolean {
        return matchesDesktopEnvironment(currentDesktop, desktopSession, gdmSession, listOf("XFCE"))
    }

    internal fun isCinnamonSession(
        currentDesktop: String = System.getenv("XDG_CURRENT_DESKTOP").orEmpty(),
        desktopSession: String = System.getenv("DESKTOP_SESSION").orEmpty(),
        gdmSession: String = System.getenv("GDMSESSION").orEmpty()
    ): Boolean {
        return matchesDesktopEnvironment(currentDesktop, desktopSession, gdmSession, listOf("CINNAMON"))
    }

    internal fun isMateSession(
        currentDesktop: String = System.getenv("XDG_CURRENT_DESKTOP").orEmpty(),
        desktopSession: String = System.getenv("DESKTOP_SESSION").orEmpty(),
        gdmSession: String = System.getenv("GDMSESSION").orEmpty()
    ): Boolean {
        return matchesDesktopEnvironment(currentDesktop, desktopSession, gdmSession, listOf("MATE"))
    }

    internal fun isDeepinSession(
        currentDesktop: String = System.getenv("XDG_CURRENT_DESKTOP").orEmpty(),
        desktopSession: String = System.getenv("DESKTOP_SESSION").orEmpty(),
        gdmSession: String = System.getenv("GDMSESSION").orEmpty()
    ): Boolean {
        return matchesDesktopEnvironment(
            currentDesktop,
            desktopSession,
            gdmSession,
            listOf("DDE", "DEEPIN", "STARTDDE", "UOS")
        )
    }

    private fun runCommand(vararg command: String): String? {
        return runCatching {
            val process = ProcessBuilder(*command)
                .redirectErrorStream(true)
                .start()

            if (!process.waitFor(COMMAND_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS)) {
                process.destroyForcibly()
                return@runCatching null
            }

            val output = process.inputStream.bufferedReader().use { it.readText().trim() }
            output.takeIf { process.exitValue() == 0 && it.isNotBlank() }
        }.getOrNull()
    }

    private fun Element.childText(tagName: String): String? {
        return getElementsByTagName(tagName)
            .item(0)
            ?.textContent
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
    }

    private fun splitKscreenDoctorOutputBlocks(output: String): List<String> {
        return output
            .split(Regex("(?m)(?=^Output:)"))
            .map(String::trim)
            .filter(String::isNotEmpty)
    }

    private fun isKdePrimaryOutputBlock(block: String): Boolean {
        val normalized = block.lowercase()
        return " primary " in " $normalized " ||
            Regex("""\bpriority\s+1\b""").containsMatchIn(normalized) ||
            Regex("""\bprimary:\s*true\b""").containsMatchIn(normalized)
    }

    private fun extractJsonArray(text: String): String? {
        val start = text.indexOf('[')
        val end = text.lastIndexOf(']')
        if (start == -1 || end <= start) return null
        return text.substring(start, end + 1)
    }

    private fun splitTopLevelJsonObjects(jsonArray: String): List<String> {
        val objects = mutableListOf<String>()
        var depth = 0
        var startIndex = -1
        var inString = false
        var escaping = false

        jsonArray.forEachIndexed { index, char ->
            if (inString) {
                when {
                    escaping -> escaping = false
                    char == '\\' -> escaping = true
                    char == '"' -> inString = false
                }
                return@forEachIndexed
            }

            when (char) {
                '"' -> inString = true
                '{' -> {
                    if (depth == 0) startIndex = index
                    depth += 1
                }
                '}' -> {
                    depth -= 1
                    if (depth == 0 && startIndex >= 0) {
                        objects += jsonArray.substring(startIndex, index + 1)
                        startIndex = -1
                    }
                }
            }
        }

        return objects
    }

    private fun matchesDesktopEnvironment(
        currentDesktop: String,
        desktopSession: String,
        gdmSession: String,
        expectedTokens: List<String>
    ): Boolean {
        val values = listOf(currentDesktop, desktopSession, gdmSession)
            .filter(String::isNotBlank)
        return values.any { value -> expectedTokens.any { token -> value.contains(token, ignoreCase = true) } }
    }

    private fun mostFrequentScale(scales: List<Float>): Float? {
        return scales
            .groupingBy { it }
            .eachCount()
            .maxByOrNull { entry -> entry.value }
            ?.key
    }

    private data class ParsedScale(
        val scale: Float,
        val isPrimary: Boolean
    )
}

internal data class LinuxDesktopScaleDetection(
    val gnomeDisplayConfigScale: Float?,
    val gnomeMonitorsXmlScale: Float?,
    val gnomeSettingsScale: Float?,
    val kdeKscreenDoctorScale: Float?,
    val hyprlandHyprctlScale: Float?,
    val hyprlandConfigScale: Float?,
    val xfceWindowScale: Float?,
    val xfceDpiScale: Float?,
    val cinnamonScale: Float?,
    val mateScale: Float?,
    val deepinDbusScale: Float?,
    val deepinGsettingsScale: Float?,
    val deepinWindowScale: Float?,
    val deepinXftDpiScale: Float?
) {
    val selectedScale: Float?
        get() = gnomeDisplayConfigScale
            ?: gnomeMonitorsXmlScale
            ?: gnomeSettingsScale
            ?: kdeKscreenDoctorScale
            ?: hyprlandHyprctlScale
            ?: hyprlandConfigScale
            ?: xfceWindowScale
            ?: xfceDpiScale
            ?: cinnamonScale
            ?: mateScale
            ?: deepinDbusScale
            ?: deepinGsettingsScale
            ?: deepinWindowScale
            ?: deepinXftDpiScale

    fun asLogFields(): String {
        return buildString {
            append("gnomeDisplay=${gnomeDisplayConfigScale?.let(DesktopDpiSupport::formatScale) ?: "unknown"}, ")
            append("gnomeXml=${gnomeMonitorsXmlScale?.let(DesktopDpiSupport::formatScale) ?: "unknown"}, ")
            append("gnomeSettings=${gnomeSettingsScale?.let(DesktopDpiSupport::formatScale) ?: "unknown"}, ")
            append("kde=${kdeKscreenDoctorScale?.let(DesktopDpiSupport::formatScale) ?: "unknown"}, ")
            append("hyprctl=${hyprlandHyprctlScale?.let(DesktopDpiSupport::formatScale) ?: "unknown"}, ")
            append("hyprConfig=${hyprlandConfigScale?.let(DesktopDpiSupport::formatScale) ?: "unknown"}, ")
            append("xfceWindow=${xfceWindowScale?.let(DesktopDpiSupport::formatScale) ?: "unknown"}, ")
            append("xfceDpi=${xfceDpiScale?.let(DesktopDpiSupport::formatScale) ?: "unknown"}, ")
            append("cinnamon=${cinnamonScale?.let(DesktopDpiSupport::formatScale) ?: "unknown"}, ")
            append("mate=${mateScale?.let(DesktopDpiSupport::formatScale) ?: "unknown"}, ")
            append("deepinDbus=${deepinDbusScale?.let(DesktopDpiSupport::formatScale) ?: "unknown"}, ")
            append("deepinScale=${deepinGsettingsScale?.let(DesktopDpiSupport::formatScale) ?: "unknown"}, ")
            append("deepinWindow=${deepinWindowScale?.let(DesktopDpiSupport::formatScale) ?: "unknown"}, ")
            append("deepinDpi=${deepinXftDpiScale?.let(DesktopDpiSupport::formatScale) ?: "unknown"}, ")
            append("linuxSelected=${selectedScale?.let(DesktopDpiSupport::formatScale) ?: "unknown"}")
        }
    }
}
