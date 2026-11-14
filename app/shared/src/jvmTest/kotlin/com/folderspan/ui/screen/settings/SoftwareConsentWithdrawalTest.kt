package com.folderspan.ui.screen.settings

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.*
import androidx.compose.ui.test.v2.runComposeUiTest
import strings.AppStrings
import kotlin.test.Test
import kotlin.test.assertEquals

@OptIn(ExperimentalTestApi::class)
class SoftwareConsentWithdrawalTest {
    @Test fun backgroundStartupWaitsForActiveSoftwareConsent() = runComposeUiTest {
        val accepted = androidx.compose.runtime.mutableStateOf(false)
        var starts = 0
        setContent { com.folderspan.rememberAppRuntimeReady({ starts++ }, accepted.value) }
        waitForIdle()
        assertEquals(0, starts)
        runOnIdle { accepted.value = true }
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
