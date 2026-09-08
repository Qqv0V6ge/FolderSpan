package com.folderspan.utils

import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LegacyWebRtcSettingsRemovalTest {
    @Test
    fun legacyWebRtcSettingsKeysAreRemovedFromSettingsUtils() {
        val source = readProjectFile("core/src/commonMain/kotlin/com/folderspan/utils/SettingsUtils.kt")

        listOf(
            "KEY_WEBRTC_WSS_URL",
            "KEY_WEBRTC_ROOM_ID",
            "KEY_WEBRTC_STUN_URL",
            "KEY_WEBRTC_TURN_URL",
            "KEY_WEBRTC_TURN_USERNAME",
            "KEY_WEBRTC_TURN_PASSWORD",
            "\"webrtc.wssUrl\"",
            "\"webrtc.roomId\"",
            "\"webrtc.stunUrl\"",
            "\"webrtc.turnUrl\"",
            "\"webrtc.turnUsername\"",
            "\"webrtc.turnPassword\"",
        ).forEach { token ->
            assertFalse(source.contains(token), "$token should not remain in SettingsUtils.kt")
        }
    }

    @Test
    fun legacyWebRtcSettingsStateAccessorsAreRemoved() {
        val source = readProjectFile("core/src/commonMain/kotlin/com/folderspan/ui/state/settings/SettingsState.kt")

        listOf(
            "webrtcWssUrl",
            "webrtcRoomId",
            "webrtcStunUrl",
            "webrtcTurnUrl",
            "webrtcTurnUsername",
            "webrtcTurnPassword",
            "setWebRtcWssUrl",
            "setWebRtcRoomId",
            "setWebRtcStunUrl",
            "setWebRtcTurnUrl",
            "setWebRtcTurnUsername",
            "setWebRtcTurnPassword",
        ).forEach { token ->
            assertFalse(source.contains(token), "$token should not remain in SettingsState.kt")
        }
    }

    @Test
    fun usedWebRtcDrawerSettingsRemainAvailable() {
        val settingsUtils = readProjectFile("core/src/commonMain/kotlin/com/folderspan/utils/SettingsUtils.kt")
        val drawerState = readProjectFile("core/src/commonMain/kotlin/com/folderspan/ui/state/main/DrawerState.kt")
        val pageSettings = readProjectFile(
            "app/shared/src/commonMain/kotlin/com/folderspan/ui/screen/settings/PageSettingsScreen.kt"
        )
        val appDrawer = readProjectFile("app/shared/src/commonMain/kotlin/com/folderspan/ui/components/drawer/AppDrawer.kt")

        listOf(
            "KEY_DRAWER_EXPAND_WEBRTC",
            "KEY_DRAWER_SHOW_WEBRTC",
            "\"settings.drawer.expand.webrtc\"",
            "\"settings.drawer.show.webrtc\"",
        ).forEach { token ->
            assertTrue(settingsUtils.contains(token), "$token should remain in SettingsUtils.kt")
        }

        listOf(
            "isExpandWebRtc",
            "updateExpandWebRtc",
            "isShowWebRtc",
            "updateShowWebRtc",
            "KEY_DRAWER_EXPAND_WEBRTC",
            "KEY_DRAWER_SHOW_WEBRTC",
        ).forEach { token ->
            assertTrue(drawerState.contains(token), "$token should remain in DrawerState.kt")
        }

        listOf(
            "AppStrings.webrtc_label",
            "isShowWebRtc",
            "updateShowWebRtc",
        ).forEach { token ->
            assertTrue(pageSettings.contains(token), "$token should remain in PageSettingsScreen.kt")
        }

        listOf(
            "isShowWebRtc",
            "rememberAppDrawerWebRtcUiState()",
        ).forEach { token ->
            assertTrue(appDrawer.contains(token), "$token should remain in AppDrawer.kt")
        }

        assertTrue(
            Files.exists(
                projectRoot().resolve("app/shared/src/commonMain/kotlin/com/folderspan/ui/components/drawer/AppDrawerWebRtc.kt")
            ),
            "AppDrawerWebRtc.kt should remain because the drawer uses it"
        )
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
