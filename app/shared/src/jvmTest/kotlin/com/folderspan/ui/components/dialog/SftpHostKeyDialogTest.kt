package com.folderspan.ui.components.dialog

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.v2.runComposeUiTest
import com.folderspan.service.network.SftpHostKey
import strings.AppStrings
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@OptIn(ExperimentalTestApi::class)
class SftpHostKeyDialogTest {
    @Test
    fun displaysTheServerFingerprintAndRequiresAnExplicitTrustClick() = runComposeUiTest {
        val key = SftpHostKey("sftp.example", 2220, "SHA256:example")
        var trusted = false
        var cancelled = false
        setContent {
            MaterialTheme {
                SftpHostKeyDialog(key, onTrust = { trusted = true }, onCancel = { cancelled = true })
            }
        }
        onNodeWithText(key.endpoint).assertIsDisplayed()
        onNodeWithText(key.fingerprint).assertIsDisplayed()
        assertFalse(trusted)
        onNodeWithText(AppStrings.ui_cancel).performClick()
        assertTrue(cancelled)
        assertFalse(trusted)
        onNodeWithText(AppStrings.ui_trust_connect).performClick()
        assertTrue(trusted)
    }
}
