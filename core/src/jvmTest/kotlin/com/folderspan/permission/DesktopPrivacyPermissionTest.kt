package com.folderspan.permission

import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DesktopPrivacyPermissionTest {
    @Test
    fun windowsNotificationsReadTheActualToastSettingWithoutRequestingAuthorization() {
        val runner = FakePrivacyCommandRunner()
        val manager = DesktopPrivacyPermissionManager("Windows 11", runner = runner)
        listOf(
            "Enabled" to PermissionStatus.Granted,
            "DisabledForApplication" to PermissionStatus.Denied,
            "DisabledForUser" to PermissionStatus.Denied,
            "DisabledByGroupPolicy" to PermissionStatus.Denied,
            "DisabledByManifest" to PermissionStatus.Denied,
            "" to PermissionStatus.Unknown,
        ).forEach { (setting, expected) ->
            runner.result = DesktopCommandResult(0, setting)
            assertEquals(expected, manager.status(PermissionIds.Notifications))
        }
        runner.result = DesktopCommandResult(1, "Enabled")
        assertEquals(PermissionStatus.Unknown, manager.status(PermissionIds.Notifications))
        assertTrue(runner.commands.all { "-NonInteractive" in it && "-EncodedCommand" in it })
    }

    @Test
    fun networkAndFileChecksNeverProbeProtectedFilesOrClaimFirewallAccess() {
        listOf("Windows 11", "Linux").forEach { os ->
            val runner = FakePrivacyCommandRunner()
            val manager = DesktopPrivacyPermissionManager(os, runner = runner)
            assertEquals(PermissionStatus.Unknown, manager.status(PermissionIds.LocalNetwork))
            assertEquals(PermissionStatus.Unknown, manager.status(PermissionIds.FilesAndFolders))
            if (os == "Linux") assertEquals(PermissionStatus.Unknown, manager.status(PermissionIds.Notifications))
            assertTrue(runner.commands.isEmpty())
        }
    }

    @Test
    fun windowsSettingsOpenTheCorrectNativePanels() {
        val launched = mutableListOf<List<String>>()
        val manager = DesktopPrivacyPermissionManager("Windows 11", launch = { launched += it; true })
        assertTrue(manager.openSettings(PermissionIds.Notifications))
        assertTrue(manager.openSettings(PermissionIds.LocalNetwork))
        assertEquals("ms-settings:notifications", launched[0].last())
        assertEquals(listOf("control.exe", "/name", "Microsoft.WindowsFirewall"), launched[1])
    }

    @Test
    fun windowsQueryUsesTheNotificationBackendsIdentityAndEscapesItAsData() {
        val runner = FakePrivacyCommandRunner()
        DesktopPrivacyPermissionManager("Windows 11", runner = runner, notificationAppId = { "FolderSpan's App" })
            .status(PermissionIds.Notifications)
        val encoded = runner.commands.single().last()
        val script = String(Base64.getDecoder().decode(encoded), Charsets.UTF_16LE)
        assertTrue(script.contains("CreateToastNotifier('FolderSpan''s App').Setting.ToString()"))
        assertFalse(script.contains("requestAuthorization"))
    }

    @Test
    fun linuxFallbackInstructionsMatchTheCurrentDesktop() {
        listOf("KDE" to "https://docs.kde.org/", "XFCE" to "https://docs.xfce.org/").forEach { (desktop, domain) ->
            val commands = mutableListOf<List<String>>()
            val manager = DesktopPrivacyPermissionManager("Linux", desktopEnvironment = desktop, launch = {
                commands += it
                it.first() == "xdg-open"
            })
            assertTrue(manager.openSettings(PermissionIds.Notifications))
            assertTrue(commands.last().last().startsWith(domain))
        }
    }

    @Test
    fun linuxSettingsFallBackWhenNoDesktopSettingsToolIsInstalled() {
        val launched = mutableListOf<List<String>>()
        val manager = DesktopPrivacyPermissionManager("Linux", desktopEnvironment = "GNOME", launch = {
            launched += it
            it.first() == "xdg-open"
        })
        assertTrue(manager.openSettings(PermissionIds.Notifications))
        assertTrue(launched.any { it == listOf("gnome-control-center", "notifications") })
        assertEquals("xdg-open", launched.last().first())
        assertTrue(launched.last().last().startsWith("https://help.gnome.org/"))
        launched.clear()
        assertTrue(manager.openSettings(PermissionIds.LocalNetwork))
        assertTrue(launched.any { it.first() == "firewall-config" })
        assertTrue(launched.any { it.first() == "gufw" })
        assertEquals("xdg-open", launched.last().first())
        assertFalse(launched.flatten().any { it in setOf("sudo", "pkexec", "--add-port", "--add-service") })
    }
}

private class FakePrivacyCommandRunner : DesktopCommandRunner {
    var result = DesktopCommandResult(0, "")
    val commands = mutableListOf<List<String>>()
    override fun run(command: List<String>): DesktopCommandResult {
        commands += command
        return result
    }
}
