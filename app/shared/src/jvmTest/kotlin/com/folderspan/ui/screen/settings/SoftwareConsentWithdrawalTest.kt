package com.folderspan.ui.screen.settings

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.*
import androidx.compose.ui.test.v2.runComposeUiTest
import strings.AppStrings
import kotlin.test.Test
import kotlin.test.assertEquals

@OptIn(ExperimentalTestApi::class)
class SoftwareConsentWithdrawalTest {
    @Test fun firstFrameDoesNotWaitForSoftwareConsent() = runComposeUiTest {
        com.folderspan.utils.SettingsUtils.init(com.folderspan.test.createInMemorySettings())
        kotlin.test.assertFalse(com.folderspan.utils.SettingsUtils.isWelcomeAgreementAccepted())
        var starts = 0
        setContent { com.folderspan.rememberAppRuntimeReady({ starts++ }) }
        waitForIdle()
        assertEquals(1, starts)
    }

    @Test fun withdrawalNeedsConfirmationAndCanBeCancelled() = runComposeUiTest {
        var withdrawals = 0
        setContent { MaterialTheme { SoftwareConsentWithdrawal { withdrawals++ } } }
        onNodeWithTag("withdraw-software-consent").performClick()
        assertEquals(0, withdrawals)
        onNodeWithText(AppStrings.ui_return).performClick()
        assertEquals(0, withdrawals)
        onNodeWithTag("withdraw-software-consent").performClick()
        onNodeWithText(AppStrings.privacy_software_withdraw_exit).performClick()
        assertEquals(1, withdrawals)
    }
}
