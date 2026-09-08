package com.folderspan.permission

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import kotlin.test.*

class DesktopLoginStartupManagerTest {
    @Test
    fun resolveCurrentExecutableRejectsGenericJvmLaunchers() {
        assertNull(
            DesktopLoginStartupManager.resolveCurrentExecutable(
                command = "/usr/bin/java",
                requireExisting = false
            )
        )
        assertNull(
            DesktopLoginStartupManager.resolveCurrentExecutable(
                command = "C:\\Program Files\\Java\\bin\\javaw.exe",
                requireExisting = false
            )
        )

        val resolved = DesktopLoginStartupManager.resolveCurrentExecutable(
            command = "/opt/com.folderspan/bin/FolderSpan",
            requireExisting = false
        )

        assertNotNull(resolved)
        assertEquals(Paths.get("/opt/com.folderspan/bin/FolderSpan"), resolved)
    }

    @Test
    fun detectOsRecognizesSupportedDesktopPlatforms() {
        assertEquals(DesktopLoginStartupOs.Windows, DesktopLoginStartupManager.detectOs("Windows 11"))
        assertEquals(DesktopLoginStartupOs.MacOs, DesktopLoginStartupManager.detectOs("Mac OS X"))
        assertEquals(DesktopLoginStartupOs.Linux, DesktopLoginStartupManager.detectOs("Linux"))
        assertNull(DesktopLoginStartupManager.detectOs("Solaris"))
    }

    @Test
    fun windowsRegistryParsingMatchesCurrentExecutable() {
        val executable = Paths.get("C:\\Program Files\\FolderSpan\\FolderSpan.exe")
        val commands = mutableListOf<List<String>>()
        val output = """
            HKEY_CURRENT_USER\Software\Microsoft\Windows\CurrentVersion\Run
                FolderSpan    REG_SZ    "C:\Program Files\FolderSpan\FolderSpan.exe"
        """.trimIndent()
        val backend = WindowsLoginStartupBackend(
            StaticCommandRunner { command ->
                commands.add(command)
                if (command.contains("query")) DesktopCommandResult(0, output) else DesktopCommandResult(0, "")
            }
        )

        assertEquals("\"C:\\Program Files\\FolderSpan\\FolderSpan.exe\"", backend.registryValueForExecutable(executable))
        assertEquals("\"C:\\Program Files\\FolderSpan\\FolderSpan.exe\"", backend.parseRunValue(output))
        assertEquals(PermissionStatus.Granted, backend.status(executable))
        assertEquals(PermissionStatus.NotDetermined, backend.status(Paths.get("C:\\Other\\FolderSpan.exe")))
        assertTrue(backend.disable())
        assertTrue(commands.any { command -> command.contains("delete") && command.contains("FolderSpan") })
    }

    @Test
    fun macLaunchAgentGenerationAndStatusAreStable() {
        withTempDir { home ->
            val executable = Paths.get("/Applications/File & Manager.app/Contents/MacOS/FolderSpan")
            val backend = MacLoginStartupBackend(home, NoopCommandRunner)

            assertEquals(PermissionStatus.NotDetermined, backend.status(executable))
            assertTrue(backend.enable(executable))

            val content = Files.readString(
                home.resolve("Library")
                    .resolve("LaunchAgents")
                    .resolve("${DesktopLoginStartupManager.LABEL}.plist")
            )
            assertTrue(content.contains("File &amp; Manager.app"))
            assertTrue(backend.programArgumentMatches(content, executable))
            assertEquals(PermissionStatus.Granted, backend.status(executable))

            val disabledContent = content.replace("<key>RunAtLoad</key>", "<key>Disabled</key>\n    <true/>\n    <key>RunAtLoad</key>")
            Files.writeString(
                home.resolve("Library")
                    .resolve("LaunchAgents")
                    .resolve("${DesktopLoginStartupManager.LABEL}.plist"),
                disabledContent
            )
            assertEquals(PermissionStatus.Denied, backend.status(executable))
            assertTrue(backend.disable())
            assertFalse(Files.exists(home.resolve("Library/LaunchAgents/${DesktopLoginStartupManager.LABEL}.plist")))
        }
    }

    @Test
    fun linuxAutostartEntryGenerationAndStatusAreStable() {
        withTempDir { home ->
            val executable = Paths.get("/opt/com.folderspan/bin/FolderSpan")
            val backend = LinuxLoginStartupBackend(home, NoopCommandRunner)

            assertEquals(PermissionStatus.NotDetermined, backend.status(executable))
            assertTrue(backend.enable(executable))

            val entryPath = home.resolve(".config")
                .resolve("autostart")
                .resolve("${DesktopLoginStartupManager.APP_ID}.desktop")
            val content = Files.readString(entryPath)
            assertTrue(content.contains("Exec=\"/opt/com.folderspan/bin/FolderSpan\""))
            assertEquals("\"/opt/com.folderspan/bin/FolderSpan\"", backend.desktopEntryValue(content, "Exec"))
            assertFalse(backend.isDisabled(content))
            assertEquals(PermissionStatus.Granted, backend.status(executable))

            Files.writeString(
                entryPath,
                content.replace("X-GNOME-Autostart-enabled=true", "X-GNOME-Autostart-enabled=false")
            )
            assertEquals(PermissionStatus.Denied, backend.status(executable))
            assertTrue(backend.disable())
            assertFalse(Files.exists(entryPath))
        }
    }

    private fun withTempDir(block: (Path) -> Unit) {
        val path = Files.createTempDirectory("folderspan-startup-test")
        try {
            block(path)
        } finally {
            path.toFile().deleteRecursively()
        }
    }
}

private object NoopCommandRunner : DesktopCommandRunner {
    override fun run(command: List<String>): DesktopCommandResult = DesktopCommandResult(0, "")
}

private class StaticCommandRunner(
    private val handler: (List<String>) -> DesktopCommandResult
) : DesktopCommandRunner {
    override fun run(command: List<String>): DesktopCommandResult = handler(command)
}
