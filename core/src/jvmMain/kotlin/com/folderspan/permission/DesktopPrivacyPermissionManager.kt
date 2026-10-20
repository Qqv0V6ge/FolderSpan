package com.folderspan.permission

import dev.nucleusframework.core.runtime.NucleusApp
import java.util.Base64
import java.util.concurrent.TimeUnit

internal class DesktopPrivacyPermissionManager(
    private val osName: String = System.getProperty("os.name").orEmpty(),
    private val desktopEnvironment: String = System.getenv("XDG_CURRENT_DESKTOP").orEmpty(),
    private val runner: DesktopCommandRunner = ProcessDesktopCommandRunner(),
    private val launch: (List<String>) -> Boolean = ::launchDesktopPermissionSettings,
    private val notificationAppId: () -> String = { NucleusApp.aumid },
) {
    private val isWindows = osName.contains("windows", ignoreCase = true)
    private val isLinux = osName.contains("linux", ignoreCase = true)

    fun status(permissionId: String): PermissionStatus {
        if (!isWindows && !isLinux) return PermissionStatus.Unsupported
        return when (permissionId) {
            PermissionIds.Notifications -> if (isWindows) windowsNotificationStatus() else PermissionStatus.Unknown
            // Firewall rules depend on the interface, network profile, executable,
            // direction and remote peer. File access depends on each selected path.
            // Neither can be represented as a single global permission switch.
            PermissionIds.LocalNetwork, PermissionIds.FilesAndFolders -> PermissionStatus.Unknown
            else -> PermissionStatus.Unsupported
        }
    }

    fun openSettings(permissionId: String): Boolean {
        val commands = when {
            isWindows -> when (permissionId) {
                PermissionIds.Notifications -> listOf(listOf("rundll32.exe", "url.dll,FileProtocolHandler", "ms-settings:notifications"))
                PermissionIds.LocalNetwork -> listOf(listOf("control.exe", "/name", "Microsoft.WindowsFirewall"))
                else -> emptyList()
            }
            isLinux -> when (permissionId) {
                PermissionIds.Notifications -> linuxNotificationSettingsCommands() + listOf(listOf("xdg-open", linuxNotificationGuide()))
                PermissionIds.LocalNetwork -> listOf(
                    listOf("firewall-config"),
                    listOf("gufw"),
                    listOf("xdg-open", "https://firewalld.org/documentation/utilities/firewall-config.html"),
                )
                else -> emptyList()
            }
            else -> emptyList()
        }
        return commands.any { command -> runCatching { launch(command) }.getOrDefault(false) }
    }

    private fun linuxNotificationSettingsCommands(): List<List<String>> = when {
        desktopEnvironment.contains("KDE", ignoreCase = true) -> listOf(
            listOf("systemsettings", "kcm_notifications"), listOf("systemsettings5", "kcm_notifications"),
        )
        desktopEnvironment.contains("XFCE", ignoreCase = true) -> listOf(listOf("xfce4-notifyd-config"))
        else -> listOf(listOf("gnome-control-center", "notifications"))
    }

    private fun linuxNotificationGuide(): String = when {
        desktopEnvironment.contains("KDE", ignoreCase = true) -> "https://docs.kde.org/stable_kf6/en/plasma-workspace/kcontrol/notifications/index.html"
        desktopEnvironment.contains("XFCE", ignoreCase = true) -> "https://docs.xfce.org/apps/xfce4-notifyd/preferences"
        else -> "https://help.gnome.org/users/gnome-help/stable/shell-notifications.html"
    }

    private fun windowsNotificationStatus(): PermissionStatus = runCatching {
        // Use the same AUMID as Nucleus's notification backend. This reads the
        // documented WinRT ToastNotifier.Setting; it does not post a notification
        // or create authorization/registry/firewall entries.
        val appId = notificationAppId().replace("'", "''")
        val script = """
            ${'$'}ErrorActionPreference = 'Stop'
            [Windows.UI.Notifications.ToastNotificationManager, Windows.UI.Notifications, ContentType = WindowsRuntime] | Out-Null
            [Windows.UI.Notifications.ToastNotificationManager]::CreateToastNotifier('$appId').Setting.ToString()
        """.trimIndent()
        val encoded = Base64.getEncoder().encodeToString(script.toByteArray(Charsets.UTF_16LE))
        val result = runner.run(listOf("powershell.exe", "-NoLogo", "-NoProfile", "-NonInteractive", "-EncodedCommand", encoded))
        if (result.exitCode != 0) return@runCatching PermissionStatus.Unknown
        when (result.output.trim()) {
            "Enabled" -> PermissionStatus.Granted
            "DisabledForApplication", "DisabledForUser", "DisabledByGroupPolicy", "DisabledByManifest" -> PermissionStatus.Denied
            else -> PermissionStatus.Unknown
        }
    }.getOrDefault(PermissionStatus.Unknown)
}

private fun launchDesktopPermissionSettings(command: List<String>): Boolean = runCatching {
    val process = ProcessBuilder(command)
        .redirectOutput(ProcessBuilder.Redirect.DISCARD)
        .redirectError(ProcessBuilder.Redirect.DISCARD)
        .start()
    // Settings windows may remain open after this call. Do not use the bounded
    // command runner, which would terminate the user's settings window.
    !process.waitFor(150, TimeUnit.MILLISECONDS) || process.exitValue() == 0
}.getOrDefault(false)
