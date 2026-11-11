package com.folderspan.ui.screen.network

import com.folderspan.ui.screen.network.form.*

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import com.folderspan.ui.components.grid.GridList
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.test.*
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.test.v2.runSkikoComposeUiTest
import androidx.compose.ui.unit.dp
import com.folderspan.service.network.StorageAuthorizationStart
import com.folderspan.service.network.StorageAccount
import com.folderspan.service.network.StorageAccountKind
import strings.AppStrings
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import java.io.File
import javax.imageio.ImageIO

@OptIn(ExperimentalTestApi::class)
class NetworkStorageFormTest {
    @Test
    fun switchesAlignWithFieldsAndKeepTheirToggleSemantics() {
        for (width in listOf(360f, 900f)) runSkikoComposeUiTest(size = Size(width, 720f)) {
            var checked by androidx.compose.runtime.mutableStateOf(false)
            setContent {
                MaterialTheme {
                    GridList(modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp), fixedColumnCount = 1) {
                        item { NetworkNameField("", {}, "nas.example.test") }
                        item { NetworkFormSwitch(AppStrings.ui_passive_mode, checked) { checked = it } }
                    }
                }
            }
            val name = onNodeWithText(AppStrings.ui_name).fetchSemanticsNode().boundsInRoot
            val row = onNodeWithText(AppStrings.ui_passive_mode).fetchSemanticsNode().boundsInRoot
            assertEquals(name.left, row.left)
            assertEquals(name.right, row.right)
            onNodeWithText(AppStrings.ui_passive_mode).assertIsOff().performClick().assertIsOn()
            assertTrue(checked)
        }
    }

    @Test
    fun nfsShowsRequiredHintsAndUpdatesFieldErrors() = runSkikoComposeUiTest(size = Size(390f, 844f)) {
        var values by androidx.compose.runtime.mutableStateOf(StorageFormValues())
        setContent {
            MaterialTheme {
                Surface(Modifier.fillMaxSize()) {
                    GridList(modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp), fixedColumnCount = 1, verticalSpacing = 16.dp) {
                        NetworkStorageForm("NFS", values, { values = it }, "", {}, "", {}, emptyList(), emptyList(), null, false, null)
                    }
                }
            }
        }
        onNodeWithText(AppStrings.ui_link_address_cannot_empty).assertExists()
        onNodeWithText(AppStrings.ui_link_address).performTextReplacement("nas.example.test")
        onNodeWithText(AppStrings.ui_link_address_cannot_empty).assertDoesNotExist()
        onNodeWithText(AppStrings.storage_nfs_server_hint).assertExists()
        onNodeWithText("UID").performScrollTo().performTextReplacement("-1")
        assertTrue("uid" in values.validationErrors("NFS"))
        onNodeWithText("UID").performTextReplacement("1000")
        assertTrue("uid" !in values.validationErrors("NFS"))
        onNodeWithText(AppStrings.ui_name).performScrollTo()
        val evidence = File("build/network-form-ui-evidence/add-nfs.png")
        evidence.parentFile.mkdirs()
        ImageIO.write(onRoot().captureToImage().toAwtImage(), "png", evidence)
    }

    @Test
    fun authorizationActionsStayUsableOnCompactAndShortWindows() {
        for ((width,height) in listOf(360 to 720,600 to 800,900 to 700,1300 to 800,1600 to 900,900 to 360)) runComposeUiTest {
            var opens = 0
            var cancels = 0
            setContent {
                MaterialTheme {
                    Box(Modifier.size(width.dp,height.dp)) {
                        GridList {
                            NetworkStorageForm("GoogleDrive",StorageFormValues(name = "Drive"),{},"",{},"",{},emptyList(),emptyList(),
                            StorageAuthorizationStart("handle","https://broker.example.test/authorize","123456",Long.MAX_VALUE),false,null,
                            onOpen = { opens++ },onCancel = { cancels++ })
                        }
                    }
                }
            }
            onNodeWithText(AppStrings.storage_authorize).performScrollTo().assertIsNotEnabled()
            onNodeWithText(AppStrings.storage_open_authorization).performScrollTo().performClick()
            onNodeWithText(AppStrings.ui_cancel).performScrollTo().performClick()
            assertEquals(1,opens);assertEquals(1,cancels)
        }
    }

    @Test
    fun httpDirectoryShowsReadOnlyStateAndNoCloudAuthorizationAction() = runComposeUiTest {
        setContent {
            MaterialTheme {
                Box(Modifier.size(600.dp,800.dp)) {
                    GridList {
                        NetworkStorageForm("HttpDirectory",StorageFormValues(name = "Mirror",server = "https://mirror.example.test"),{},"",{},"",{},
                        emptyList(),emptyList(),null,false,null)
                    }
                }
            }
        }
        onNodeWithText(AppStrings.storage_http_read_only).assertExists()
        onNodeWithText(AppStrings.storage_authorize).assertDoesNotExist()
    }
    @Test
    fun sharingAuthorizationIsSeparateFromDefaultLoginAndRequiresAnExistingAccount() {
        for ((protocol, account) in listOf(
            "GoogleDrive" to StorageAccount("existingaccount0001", StorageAccountKind.GoogleDrive, "Drive"),
            "Dropbox" to StorageAccount("existingaccount0001", StorageAccountKind.Dropbox, "Dropbox"))) {
            runComposeUiTest {
                var ordinary = 0
                var sharing = 0
                setContent {
                    MaterialTheme {
                        Box(Modifier.size(360.dp, 720.dp)) {
                            GridList {
                                NetworkStorageForm(protocol, StorageFormValues(name = protocol, account = account.id), {},
                                    "", {}, "", {}, listOf(account), emptyList(), null, false, null,
                                    onAuthorize = { ordinary++ }, onAuthorizeSharing = { sharing++ })
                            }
                        }
                    }
                }
                onNodeWithText(AppStrings.storage_sharing_authorization_hint).performScrollTo().assertExists()
                onNodeWithText(AppStrings.storage_authorize_sharing).performScrollTo().performClick()
                assertEquals(1, sharing); assertEquals(0, ordinary)
                onNodeWithText(AppStrings.storage_reauthorize).performScrollTo().performClick()
                assertEquals(1, ordinary)
            }
        }
    }

}
