package com.folderspan.ui

import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AppDrawerStructureTest {

    @Test
    fun drawerMoreOptionsUsesDedicatedMenuIconInsteadOfSettingsIcon() {
        val source = readProjectFile("app/shared/src/commonMain/kotlin/com/folderspan/ui/components/drawer/AppDrawer.kt")

        assertTrue(
            source.contains("Icon(Icons.Default.MoreVert, contentDescription = AppStrings.ui_more_options)"),
            "More options should use a dedicated more-options icon"
        )
        assertFalse(
            source.contains("Icon(Icons.Default.Settings, contentDescription = AppStrings.ui_more_options)"),
            "More options should not look like the settings shortcut"
        )
    }

    @Test
    fun moreOptionsDropdownContainsBottomExitItemThatExitsApp() {
        val source = readProjectFile("app/shared/src/commonMain/kotlin/com/folderspan/ui/components/drawer/AppDrawer.kt")
        val dropdownSource = source.substringAfter("private fun MoreOptionsDropdown")
        val settingsLabelIndex = dropdownSource.indexOf("Text(AppStrings.settings_title)")
        val exitLabelIndex = dropdownSource.indexOf("Text(AppStrings.ui_exit_software)")
        val exitCallbackIndex = dropdownSource.indexOf("onExit()", startIndex = exitLabelIndex.coerceAtLeast(0))

        assertTrue(source.contains("import com.folderspan.crash.exitApp"), "AppDrawer should import exitApp")
        assertTrue(
            source.contains("Icons.AutoMirrored.Filled.ExitToApp"),
            "Exit item should use the non-deprecated auto-mirrored exit icon"
        )
        assertTrue(
            dropdownSource.contains("leadingIcon = { Icon(Icons.AutoMirrored.Filled.ExitToApp, null) }"),
            "Exit item should be part of MoreOptionsDropdown"
        )
        assertTrue(exitLabelIndex > settingsLabelIndex, "Exit item should be the bottom item in MoreOptionsDropdown")
        assertTrue(exitCallbackIndex > exitLabelIndex, "Exit item should call the injected exit callback")
        assertTrue(
            source.contains("onExit = ::exitApp"),
            "AppDrawer container should bind the exit callback to exitApp"
        )
    }

    @Test
    fun componentDependencyInjectionIsLimitedToExplicitContainersAndStateProducers() {
        val componentRoot = projectRoot()
            .resolve("app/shared/src/commonMain/kotlin/com/folderspan/ui/components")
        val functionPattern = Regex("fun\\s+([A-Za-z0-9_]+)\\s*\\(")
        val violations = mutableListOf<String>()
        Files.walk(componentRoot).use { paths ->
            paths.filter { path -> path.toString().endsWith(".kt") }.forEach { path ->
                val source = Files.readString(path)
                source.lineSequence().withIndex()
                    .filter { (_, line) -> "koinInject<" in line }
                    .forEach { (lineIndex, _) ->
                        val prefix = source.lineSequence().take(lineIndex + 1).joinToString("\n")
                        val functionName = functionPattern.findAll(prefix).lastOrNull()?.groupValues?.get(1)
                        val isAllowed = functionName != null &&
                            (functionName.endsWith("Container") || functionName.startsWith("remember"))
                        if (!isAllowed) {
                            violations +=
                                "${componentRoot.relativize(path)}:${lineIndex + 1} (${functionName ?: "unknown"})"
                        }
                    }
            }
        }

        assertTrue(
            violations.isEmpty(),
            "Component dependency injection must stay in explicit containers/state producers: $violations"
        )
    }

    private fun readProjectFile(relativePath: String): String {
        return Files.readString(projectRoot().resolve(relativePath))
    }

    private fun projectRoot(): Path {
        var current: Path? = Path.of("").toAbsolutePath()
        while (current != null) {
            if (Files.exists(current.resolve("settings.gradle.kts"))) {
                return current
            }
            current = current.parent
        }
        error("Could not locate project root")
    }
}
