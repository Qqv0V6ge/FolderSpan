package com.folderspan.ui.components.dialog

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.v2.runComposeUiTest
import com.folderspan.data.main.device.DeviceType
import com.folderspan.service.data.ConnectType
import com.folderspan.service.data.DeviceDiscoveryStatus
import com.folderspan.service.data.SocketDevice
import com.folderspan.ui.components.drawer.DeviceDrawerListItem
import com.folderspan.ui.screen.device.DeviceOutgoingConnectionBlock
import java.io.File
import javax.imageio.ImageIO
import strings.AppStrings
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@OptIn(ExperimentalTestApi::class)
class DeviceIdentityTrustDialogTest {
    @Test
    fun changedCertificateShowsBothFingerprintsAndRequiresExplicitTrust() = runComposeUiTest {
        val fingerprint = "AB".repeat(32)
        val previous = "CD".repeat(32)
        var trusted = false
        var cancelled = false
        setContent {
            MaterialTheme {
                DeviceIdentityTrustDialog(
                    "MacBook", "10.0.0.122:12040", fingerprint, previous,
                    onTrust = { trusted = true }, onCancel = { cancelled = true },
                )
            }
        }
        onNodeWithText("10.0.0.122:12040").assertIsDisplayed()
        onNodeWithText(AppStrings.ui_device_identity_changed).assertIsDisplayed()
        val screenshot = File("build/reports/device-identity-trust/changed-certificate.png")
        screenshot.parentFile.mkdirs()
        ImageIO.write(onNode(isDialog()).captureToImage().toAwtImage(), "png", screenshot)
        onNodeWithText(fingerprint.chunked(2).joinToString(":")).performScrollTo().assertIsDisplayed()
        onNodeWithText(previous.chunked(2).joinToString(":")).performScrollTo().assertIsDisplayed()
        assertFalse(trusted)
        onNodeWithText(AppStrings.ui_cancel).performClick()
        assertTrue(cancelled)
        assertFalse(trusted)
        onNodeWithText(AppStrings.ui_device_identity_trust_continue).performClick()
        assertTrue(trusted)
    }

    @Test
    fun drawerShowsUnverifiedStatusInsteadOfGenericConnectionFailure() = runComposeUiTest {
        setContent {
            MaterialTheme {
                DeviceDrawerListItem(
                    device = SocketDevice(
                        id = "peer", name = "MacBook", pathSeparator = "/", type = DeviceType.JVM,
                        connectType = ConnectType.Fail, discoveryStatus = DeviceDiscoveryStatus.Unverified,
                    ),
                    selected = false, onClick = {},
                )
            }
        }
        onNodeWithText("MacBook").assertIsDisplayed()
        onNodeWithText(AppStrings.ui_device_identity_unverified).assertIsDisplayed()
        onNodeWithText(AppStrings.ui_connection_failed).assertDoesNotExist()
    }

    @Test
    fun devicePageOffersManualIdentityConfirmation() = runComposeUiTest {
        var requested = false
        setContent {
            MaterialTheme {
                DeviceOutgoingConnectionBlock(
                    connection = SocketDevice(
                        id = "peer", name = "MacBook", pathSeparator = "/", type = DeviceType.JVM,
                        connectType = ConnectType.UnConnect, discoveryStatus = DeviceDiscoveryStatus.Unverified,
                    ),
                    enabled = true, onPrimaryAction = { requested = true }, onDisconnect = {},
                )
            }
        }
        onNodeWithText(AppStrings.ui_device_identity_unverified).assertIsDisplayed()
        assertFalse(requested)
        onNodeWithText(AppStrings.ui_device_identity_confirm).performClick()
        assertTrue(requested)
    }
}
