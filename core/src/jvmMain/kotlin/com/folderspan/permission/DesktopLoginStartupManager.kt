package com.folderspan.permission

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.util.*
import java.util.concurrent.TimeUnit

internal object DesktopLoginStartupManager {
    const val APP_ID = "com.folderspan"
    const val LABEL = "$APP_ID.login"
    const val STARTUP_ITEM_NAME = "FolderSpan"
    const val DISPLAY_NAME = "FolderSpan"

    private val genericJvmLaunchers = setOf(
        "java",
        "java.exe",
        "javaw",
        "javaw.exe",
        "kotlin",
        "kotlin.exe"
    )

    fun isCurrentPlatformSupported(osName: String = System.getProperty("os.name").orEmpty()): Boolean {
        return detectOs(osName) != null
    }

    fun status(): PermissionStatus {
        val backend = currentBackend() ?: return PermissionStatus.Unsupported
        val executable = resolveCurrentExecutable() ?: return PermissionStatus.Unsupported
        return runCatching { backend.status(executable) }
            .getOrDefault(PermissionStatus.Unsupported)
    }

    fun enable(): PermissionStatus {
        val backend = currentBackend() ?: return PermissionStatus.Unsupported
        val executable = resolveCurrentExecutable() ?: return PermissionStatus.Unsupported
        val enabled = runCatching { backend.enable(executable) }.getOrDefault(false)
        if (!enabled) return PermissionStatus.Unsupported
        return runCatching { backend.status(executable) }
            .getOrDefault(PermissionStatus.Unsupported)
    }

    fun disable(): Boolean {
        val backend = currentBackend() ?: return true
        return runCatching { backend.disable() }.getOrDefault(false)
    }

    fun openSettings() {
        currentBackend()?.let { backend ->
            runCatching { backend.openSettings() }
        }
    }

    internal fun resolveCurrentExecutable(
        command: String? = ProcessHandle.current().info().command().orElse(null),
        requireExisting: Boolean = true
    ): Path? {
        val trimmed = command?.trim()?.takeIf { item -> item.isNotEmpty() } ?: return null
        val path = runCatching { Paths.get(trimmed) }.getOrNull() ?: return null
        val fileName = trimmed
            .substringAfterLast('/')
            .substringAfterLast('\\')
            .lowercase(Locale.US)
        if (fileName in genericJvmLaunchers) return null

        val executable = path.toAbsolutePath().normalize()
        if (requireExisting && !Files.isRegularFile(executable)) return null
        return executable
    }

    internal fun detectOs(osName: String): DesktopLoginStartupOs? {
        val normalized = osName.lowercase(Locale.US)
        return when {
            normalized.contains("win") -> DesktopLoginStartupOs.Windows
            normalized.contains("mac") || normalized.contains("darwin") -> DesktopLoginStartupOs.MacOs
            normalized.contains("linux") -> DesktopLoginStartupOs.Linux
            else -> null
        }
    }

    internal fun executableCommandMatches(
        commandValue: String,
        executable: Path,
        unescapeBackslash: Boolean = false
    ): Boolean {
        val extracted = extractExecutableCommand(commandValue, unescapeBackslash) ?: return false
        val expected = normalizeExecutableForComparison(executable.toString())
        return normalizeExecutableForComparison(extracted) == expected ||
            normalizeExecutableForComparison(commandValue) == expected
    }

    internal fun extractExecutableCommand(commandValue: String, unescapeBackslash: Boolean = false): String? {
        val trimmed = commandValue.trim()
        if (trimmed.isEmpty()) return null
        val first = trimmed.first()
        if (first == '"' || first == '\'') {
            val builder = StringBuilder()
            var escaped = false
            for (index in 1 until trimmed.length) {
                val char = trimmed[index]
                when {
                    escaped -> {
                        builder.append(char)
                        escaped = false
                    }
                    unescapeBackslash && char == '\\' -> escaped = true
                    char == first -> return builder.toString().takeIf { item -> item.isNotBlank() }
                    else -> builder.append(char)
                }
            }
            return builder.toString().takeIf { item -> item.isNotBlank() }
        }
        return trimmed.takeWhile { item -> !item.isWhitespace() }
            .takeIf { item -> item.isNotBlank() }
    }

    private fun currentBackend(): DesktopLoginStartupBackend? {
        val runner = ProcessDesktopCommandRunner()
        val homePath = Paths.get(System.getProperty("user.home"))
        return when (detectOs(System.getProperty("os.name").orEmpty())) {
            DesktopLoginStartupOs.Windows -> WindowsLoginStartupBackend(runner)
            DesktopLoginStartupOs.MacOs -> MacLoginStartupBackend(homePath, runner)
            DesktopLoginStartupOs.Linux -> LinuxLoginStartupBackend(homePath, runner)
            null -> null
        }
    }

    private fun normalizeExecutableForComparison(value: String): String {
        val stripped = value.trim().trim('"', '\'')
        if (looksLikeWindowsPath(stripped)) {
            return stripped.replace('\\', '/').lowercase(Locale.US)
        }
        return runCatching { Paths.get(stripped).toAbsolutePath().normalize().toString() }
            .getOrDefault(stripped)
    }

    private fun looksLikeWindowsPath(value: String): Boolean {
        return (value.length >= 3 &&
            value[1] == ':' &&
            value[0].isLetter() &&
            (value[2] == '\\' || value[2] == '/')) ||
            value.startsWith("\\\\")
    }
}

internal enum class DesktopLoginStartupOs {
    Windows,
    MacOs,
    Linux
}

internal interface DesktopLoginStartupBackend {
    fun status(executable: Path): PermissionStatus
    fun enable(executable: Path): Boolean
    fun disable(): Boolean
    fun openSettings()
}

internal data class DesktopCommandResult(
    val exitCode: Int,
    val output: String
)

internal interface DesktopCommandRunner {
    fun run(command: List<String>): DesktopCommandResult
}

internal class ProcessDesktopCommandRunner(
    private val timeoutMillis: Long = 3_000L
) : DesktopCommandRunner {
    override fun run(command: List<String>): DesktopCommandResult {
        return runCatching {
            val process = ProcessBuilder(command)
                .redirectErrorStream(true)
                .start()
            if (!process.waitFor(timeoutMillis, TimeUnit.MILLISECONDS)) {
                process.destroyForcibly()
                return@runCatching DesktopCommandResult(-1, "")
            }
            val output = process.inputStream.bufferedReader().use { reader -> reader.readText().trim() }
            DesktopCommandResult(process.exitValue(), output)
        }.getOrDefault(DesktopCommandResult(-1, ""))
    }
}

internal class WindowsLoginStartupBackend(
    private val commandRunner: DesktopCommandRunner
) : DesktopLoginStartupBackend {
    override fun status(executable: Path): PermissionStatus {
        val value = readRunValue() ?: return PermissionStatus.NotDetermined
        return if (DesktopLoginStartupManager.executableCommandMatches(value, executable)) {
            PermissionStatus.Granted
        } else {
            PermissionStatus.NotDetermined
        }
    }

    override fun enable(executable: Path): Boolean {
        val result = commandRunner.run(
            listOf(
                "reg",
                "add",
                RUN_KEY,
                "/v",
                DesktopLoginStartupManager.STARTUP_ITEM_NAME,
                "/t",
                "REG_SZ",
                "/d",
                registryValueForExecutable(executable),
                "/f"
            )
        )
        return result.exitCode == 0
    }

    override fun disable(): Boolean {
        return readRunValue() == null || commandRunner.run(
            listOf(
                "reg",
                "delete",
                RUN_KEY,
                "/v",
                DesktopLoginStartupManager.STARTUP_ITEM_NAME,
                "/f"
            )
        ).exitCode == 0
    }

    override fun openSettings() {
        commandRunner.run(listOf("cmd", "/c", "start", "", "ms-settings:startupapps"))
    }

    internal fun registryValueForExecutable(executable: Path): String =
        "\"${executable.toString().replace("\"", "")}\""

    internal fun parseRunValue(output: String): String? {
        return output.lineSequence()
            .map { line -> line.trim() }
            .firstNotNullOfOrNull { line ->
                val parts = line.split(Regex("\\s+"), limit = 3)
                if (
                    parts.size == 3 &&
                    parts[0].equals(DesktopLoginStartupManager.STARTUP_ITEM_NAME, ignoreCase = true) &&
                    parts[1].equals("REG_SZ", ignoreCase = true)
                ) {
                    parts[2].trim().takeIf { item -> item.isNotEmpty() }
                } else {
                    null
                }
            }
    }

    private fun readRunValue(): String? {
        val result = commandRunner.run(
            listOf(
                "reg",
                "query",
                RUN_KEY,
                "/v",
                DesktopLoginStartupManager.STARTUP_ITEM_NAME
            )
        )
        if (result.exitCode != 0) return null
        return parseRunValue(result.output)
    }

    private companion object {
        const val RUN_KEY = "HKCU\\Software\\Microsoft\\Windows\\CurrentVersion\\Run"
    }
}

internal class MacLoginStartupBackend(
    private val homePath: Path,
    private val commandRunner: DesktopCommandRunner
) : DesktopLoginStartupBackend {
    private val launchAgentPath: Path
        get() = homePath
            .resolve("Library")
            .resolve("LaunchAgents")
            .resolve("${DesktopLoginStartupManager.LABEL}.plist")

    override fun status(executable: Path): PermissionStatus {
        val path = launchAgentPath
        if (!Files.exists(path)) return PermissionStatus.NotDetermined
        val content = runCatching { Files.readString(path) }.getOrNull() ?: return PermissionStatus.Unsupported
        if (!programArgumentMatches(content, executable)) return PermissionStatus.NotDetermined
        return if (isDisabled(content)) PermissionStatus.Denied else PermissionStatus.Granted
    }

    override fun enable(executable: Path): Boolean {
        return runCatching {
            Files.createDirectories(launchAgentPath.parent)
            Files.writeString(launchAgentPath, buildPlist(executable))
            true
        }.getOrDefault(false)
    }

    override fun disable(): Boolean = runCatching {
        Files.deleteIfExists(launchAgentPath)
        true
    }.getOrDefault(false)

    override fun openSettings() {
        val result = commandRunner.run(
            listOf("open", "x-apple.systempreferences:com.apple.LoginItems-Settings.extension")
        )
        if (result.exitCode != 0) {
            commandRunner.run(listOf("open", launchAgentPath.parent.toString()))
        }
    }

    internal fun buildPlist(executable: Path): String {
        val executableText = xmlEscape(executable.toString())
        return """
            |<?xml version="1.0" encoding="UTF-8"?>
            |<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
            |<plist version="1.0">
            |<dict>
            |    <key>Label</key>
            |    <string>${DesktopLoginStartupManager.LABEL}</string>
            |    <key>ProgramArguments</key>
            |    <array>
            |        <string>$executableText</string>
            |    </array>
            |    <key>RunAtLoad</key>
            |    <true/>
            |</dict>
            |</plist>
            |
        """.trimMargin()
    }

    internal fun programArgumentMatches(content: String, executable: Path): Boolean {
        val value = Regex(
            """<key>\s*ProgramArguments\s*</key>\s*<array>\s*<string>(.*?)</string>""",
            setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE)
        ).find(content)?.groupValues?.getOrNull(1)?.let(::xmlUnescape) ?: return false
        return DesktopLoginStartupManager.executableCommandMatches(value, executable)
    }

    internal fun isDisabled(content: String): Boolean {
        return Regex(
            """<key>\s*Disabled\s*</key>\s*<true\s*/>""",
            setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE)
        ).containsMatchIn(content)
    }
}

internal class LinuxLoginStartupBackend(
    private val homePath: Path,
    private val commandRunner: DesktopCommandRunner
) : DesktopLoginStartupBackend {
    private val autostartPath: Path
        get() = homePath
            .resolve(".config")
            .resolve("autostart")
            .resolve("${DesktopLoginStartupManager.APP_ID}.desktop")

    override fun status(executable: Path): PermissionStatus {
        val path = autostartPath
        if (!Files.exists(path)) return PermissionStatus.NotDetermined
        val content = runCatching { Files.readString(path) }.getOrNull() ?: return PermissionStatus.Unsupported
        val exec = desktopEntryValue(content, "Exec") ?: return PermissionStatus.NotDetermined
        if (!DesktopLoginStartupManager.executableCommandMatches(exec, executable, unescapeBackslash = true)) {
            return PermissionStatus.NotDetermined
        }
        return if (isDisabled(content)) PermissionStatus.Denied else PermissionStatus.Granted
    }

    override fun enable(executable: Path): Boolean {
        return runCatching {
            Files.createDirectories(autostartPath.parent)
            Files.writeString(autostartPath, buildDesktopEntry(executable))
            true
        }.getOrDefault(false)
    }

    override fun disable(): Boolean = runCatching {
        Files.deleteIfExists(autostartPath)
        true
    }.getOrDefault(false)

    override fun openSettings() {
        runCatching { Files.createDirectories(autostartPath.parent) }
        commandRunner.run(listOf("xdg-open", autostartPath.parent.toString()))
    }

    internal fun buildDesktopEntry(executable: Path): String {
        return """
            |[Desktop Entry]
            |Type=Application
            |Version=1.0
            |Name=${DesktopLoginStartupManager.DISPLAY_NAME}
            |Comment=Start ${DesktopLoginStartupManager.DISPLAY_NAME} at login
            |Exec=${quoteDesktopExec(executable.toString())}
            |Terminal=false
            |X-GNOME-Autostart-enabled=true
            |
        """.trimMargin()
    }

    internal fun desktopEntryValue(content: String, key: String): String? {
        return content.lineSequence()
            .map { line -> line.trim() }
            .filter { line -> line.isNotEmpty() && !line.startsWith("#") && !line.startsWith(";") }
            .firstNotNullOfOrNull { line ->
                val separator = line.indexOf('=')
                if (separator <= 0) return@firstNotNullOfOrNull null
                val entryKey = line.substring(0, separator).trim()
                if (entryKey == key) {
                    line.substring(separator + 1).trim().takeIf { item -> item.isNotEmpty() }
                } else {
                    null
                }
            }
    }

    internal fun isDisabled(content: String): Boolean {
        val hidden = desktopEntryValue(content, "Hidden")?.equals("true", ignoreCase = true) == true
        val gnomeDisabled = desktopEntryValue(content, "X-GNOME-Autostart-enabled")
            ?.equals("false", ignoreCase = true) == true
        return hidden || gnomeDisabled
    }

    private fun quoteDesktopExec(value: String): String {
        val escaped = value
            .replace("\\", "\\\\")
            .replace("\"", "\\\"")
        return "\"$escaped\""
    }
}

private fun xmlEscape(value: String): String =
    value
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")
        .replace("'", "&apos;")

private fun xmlUnescape(value: String): String =
    value
        .replace("&apos;", "'")
        .replace("&quot;", "\"")
        .replace("&gt;", ">")
        .replace("&lt;", "<")
        .replace("&amp;", "&")
