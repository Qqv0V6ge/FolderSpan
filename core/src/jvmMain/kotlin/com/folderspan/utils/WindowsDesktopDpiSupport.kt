package com.folderspan.utils

import java.util.concurrent.TimeUnit

internal object WindowsDesktopDpiSupport {
    private const val COMMAND_TIMEOUT_MILLIS = 1500L

    private val registryDwordRegex = Regex(
        """REG_DWORD\s+(0x[0-9a-fA-F]+|\d+)""",
        RegexOption.IGNORE_CASE
    )
    private val numericValueRegex = Regex("""-?\d+(?:\.\d+)?""")

    fun detectSystemScale(osName: String = System.getProperty("os.name").orEmpty()): WindowsDesktopScaleDetection? {
        if (!DesktopDpiSupport.isWindowsOs(osName)) return null

        return WindowsDesktopScaleDetection(
            appliedDpiScale = readAppliedDpiScale(),
            logPixelsScale = readLogPixelsScale()
        )
    }

    internal fun readAppliedDpiScale(): Float? {
        return runCommand(
            "reg",
            "query",
            "HKCU\\Control Panel\\Desktop\\WindowMetrics",
            "/v",
            "AppliedDPI"
        )?.let(::parseRegistryDpiScale)
            ?: runPowerShellDpiQuery(
                "(Get-ItemProperty -Path 'HKCU:\\Control Panel\\Desktop\\WindowMetrics' -Name AppliedDPI -ErrorAction SilentlyContinue).AppliedDPI"
            )
    }

    internal fun readLogPixelsScale(): Float? {
        return runCommand(
            "reg",
            "query",
            "HKCU\\Control Panel\\Desktop",
            "/v",
            "LogPixels"
        )?.let(::parseRegistryDpiScale)
            ?: runPowerShellDpiQuery(
                "(Get-ItemProperty -Path 'HKCU:\\Control Panel\\Desktop' -Name LogPixels -ErrorAction SilentlyContinue).LogPixels"
            )
    }

    internal fun parseRegistryDpiScale(output: String): Float? {
        val valueText = output.lineSequence().firstNotNullOfOrNull { line ->
            registryDwordRegex.find(line)
                ?.groupValues
                ?.getOrNull(1)
        }
            ?: return null

        val dpi = parseRegistryDword(valueText) ?: return null
        return DesktopDpiSupport.scaleFromDpi(dpi)
    }

    internal fun parsePlainDpiScale(output: String): Float? {
        val dpi = numericValueRegex.findAll(output)
            .mapNotNull { match -> match.value.toFloatOrNull() }
            .lastOrNull()
            ?: return null
        return DesktopDpiSupport.scaleFromRawDpi(dpi)
    }

    private fun parseRegistryDword(valueText: String): Int? {
        val trimmed = valueText.trim()
        return if (trimmed.startsWith("0x", ignoreCase = true)) {
            trimmed.removePrefix("0x").removePrefix("0X").toIntOrNull(16)
        } else {
            trimmed.toIntOrNull()
        }
    }

    private fun runPowerShellDpiQuery(command: String): Float? {
        return runCommand("powershell.exe", "-NoProfile", "-Command", command)
            ?.let(::parsePlainDpiScale)
            ?: runCommand("powershell", "-NoProfile", "-Command", command)
                ?.let(::parsePlainDpiScale)
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
}

internal data class WindowsDesktopScaleDetection(
    val appliedDpiScale: Float?,
    val logPixelsScale: Float?
) {
    val selectedScale: Float?
        get() = appliedDpiScale ?: logPixelsScale

    fun asLogFields(): String {
        return buildString {
            append("windowsAppliedDpi=${appliedDpiScale?.let(DesktopDpiSupport::formatScale) ?: "unknown"}, ")
            append("windowsLogPixels=${logPixelsScale?.let(DesktopDpiSupport::formatScale) ?: "unknown"}, ")
            append("windowsSelected=${selectedScale?.let(DesktopDpiSupport::formatScale) ?: "unknown"}")
        }
    }
}
