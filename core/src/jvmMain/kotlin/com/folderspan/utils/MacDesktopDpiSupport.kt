package com.folderspan.utils

import java.util.concurrent.TimeUnit

internal object MacDesktopDpiSupport {
    private const val COMMAND_TIMEOUT_MILLIS = 2000L

    private val resolutionRegex = Regex(
        """Resolution:\s*(\d+)\s*x\s*(\d+)""",
        RegexOption.IGNORE_CASE
    )
    private val uiLooksLikeRegex = Regex(
        """(?:UI\s+Looks\s+like|Looks\s+like):\s*(\d+)\s*x\s*(\d+)""",
        RegexOption.IGNORE_CASE
    )
    private val primaryDisplayRegex = Regex(
        """Main\s+Display:\s*Yes""",
        RegexOption.IGNORE_CASE
    )
    private val displayHeaderRegex = Regex("""(?m)^\s{6,}[^:\n]+:\s*$""")
    private val numericValueRegex = Regex("""-?\d+(?:\.\d+)?""")

    fun detectSystemScale(osName: String = System.getProperty("os.name").orEmpty()): MacDesktopScaleDetection? {
        if (!DesktopDpiSupport.isMacOs(osName)) return null

        return MacDesktopScaleDetection(
            systemProfilerScale = readSystemProfilerScale(),
            appleDisplayScaleFactor = readAppleDisplayScaleFactor()
        )
    }

    internal fun readSystemProfilerScale(): Float? {
        val output = runCommand("system_profiler", "SPDisplaysDataType") ?: return null
        return parseSystemProfilerScale(output)
    }

    internal fun readAppleDisplayScaleFactor(): Float? {
        val output = runCommand("defaults", "read", "-g", "AppleDisplayScaleFactor")
            ?: runCommand("defaults", "-currentHost", "read", "-globalDomain", "AppleDisplayScaleFactor")
            ?: return null
        return parseAppleDisplayScaleFactor(output)
    }

    internal fun parseSystemProfilerScale(output: String): Float? {
        val displayBlocks = splitDisplayBlocks(output)
        val parsedBlocks = displayBlocks.mapNotNull(::parseDisplayBlock)
        return parsedBlocks.firstOrNull { it.isPrimary }?.scale
            ?: parsedBlocks.firstOrNull()?.scale
    }

    internal fun parseAppleDisplayScaleFactor(output: String): Float? {
        val scale = numericValueRegex.findAll(output)
            .mapNotNull { match -> match.value.toFloatOrNull() }
            .lastOrNull()
            ?: return null
        return DesktopDpiSupport.sanitizeScale(scale)
    }

    private fun parseDisplayBlock(block: String): ParsedDisplayScale? {
        val resolutionMatch = resolutionRegex.find(block) ?: return null
        val resolutionWidth = resolutionMatch.groupValues[1].toFloatOrNull() ?: return null
        val resolutionHeight = resolutionMatch.groupValues[2].toFloatOrNull() ?: return null
        val retina = block.contains("Retina", ignoreCase = true)
        val uiLooksLikeMatch = uiLooksLikeRegex.find(block)
        val scale = when {
            uiLooksLikeMatch != null -> {
                val logicalWidth = uiLooksLikeMatch.groupValues[1].toFloatOrNull() ?: return null
                val logicalHeight = uiLooksLikeMatch.groupValues[2].toFloatOrNull() ?: return null
                val widthScale = resolutionWidth / logicalWidth
                val heightScale = resolutionHeight / logicalHeight
                DesktopDpiSupport.sanitizeScale(maxOf(widthScale, heightScale))
            }

            retina -> DesktopDpiSupport.sanitizeScale(2f)
            else -> null
        } ?: return null

        return ParsedDisplayScale(
            scale = scale,
            isPrimary = primaryDisplayRegex.containsMatchIn(block)
        )
    }

    private fun splitDisplayBlocks(output: String): List<String> {
        val matches = displayHeaderRegex.findAll(output).toList()
        if (matches.isEmpty()) return emptyList()

        return matches.mapIndexedNotNull { index, match ->
            val start = match.range.first
            val end = matches.getOrNull(index + 1)?.range?.first ?: output.length
            output.substring(start, end)
                .takeIf { it.contains("Resolution:", ignoreCase = true) }
        }
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

    private data class ParsedDisplayScale(
        val scale: Float,
        val isPrimary: Boolean
    )
}

internal data class MacDesktopScaleDetection(
    val systemProfilerScale: Float?,
    val appleDisplayScaleFactor: Float?
) {
    val selectedScale: Float?
        get() = systemProfilerScale ?: appleDisplayScaleFactor

    fun asLogFields(): String {
        return buildString {
            append("macProfiler=${systemProfilerScale?.let(DesktopDpiSupport::formatScale) ?: "unknown"}, ")
            append("macDefaults=${appleDisplayScaleFactor?.let(DesktopDpiSupport::formatScale) ?: "unknown"}, ")
            append("macSelected=${selectedScale?.let(DesktopDpiSupport::formatScale) ?: "unknown"}")
        }
    }
}
