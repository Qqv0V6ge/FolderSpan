package com.folderspan.utils

import com.folderspan.test.createInMemorySettings
import com.folderspan.ui.state.main.DrawerState
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LegacyWebRtcSettingsRemovalTest {
    @Test
    fun webRtcDrawerPreferencesUseExistingSettingsKeys() {
        val expandKey = "settings.drawer.expand.webrtc"
        val showKey = "settings.drawer.show.webrtc"
        val settings = createInMemorySettings(
            expandKey to false,
            showKey to true,
        )
        val drawerState = DrawerState(settings)

        assertFalse(drawerState.isExpandWebRtc.value)
        assertTrue(drawerState.isShowWebRtc.value)

        drawerState.updateExpandWebRtc(true)
        drawerState.updateShowWebRtc(false)

        assertTrue(drawerState.isExpandWebRtc.value)
        assertFalse(drawerState.isShowWebRtc.value)
        assertEquals(true, settings.getBooleanOrNull(expandKey))
        assertEquals(false, settings.getBooleanOrNull(showKey))
    }

    @Test
    fun obsoleteSecuritySettingsScreenIsRemoved() {
        val settingsScreen = readProjectFile(
            "app/shared/src/commonMain/kotlin/com/folderspan/ui/screen/settings/SettingsScreen.kt"
        )
        val securitySettingsScreen = projectRoot().resolve(
            "app/shared/src/commonMain/kotlin/com/folderspan/ui/screen/settings/SecuritySettingsScreen.kt"
        )

        assertFalse(settingsScreen.contains("SecuritySettingsScreen"))
        assertFalse(settingsScreen.contains("id = \"security\""))
        assertFalse(Files.exists(securitySettingsScreen))
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
