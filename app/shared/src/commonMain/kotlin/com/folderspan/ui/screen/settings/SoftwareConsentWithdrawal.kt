package com.folderspan.ui.screen.settings

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import com.folderspan.crash.exitApp
import com.folderspan.utils.SettingsUtils
import strings.AppStrings

internal fun withdrawSoftwareConsent() {
    SettingsUtils.setWelcomeAgreementAccepted(false)
    SettingsUtils.putBoolean(SettingsUtils.KEY_LAST_SESSION_FOREGROUND, false)
    exitApp()
}

@Composable
internal fun SoftwareConsentWithdrawal(onWithdraw: () -> Unit = ::withdrawSoftwareConsent) {
    var confirm by remember { mutableStateOf(false) }
    AboutSoftwareNavigationItem(
        title = AppStrings.privacy_withdraw_software,
        supportingText = AppStrings.privacy_software_withdraw_description,
        testTag = "withdraw-software-consent",
        onClick = { confirm = true },
    )
    if (confirm) AlertDialog(
        onDismissRequest = { confirm = false },
        title = { Text(AppStrings.privacy_withdraw_software) },
        text = { Text(AppStrings.privacy_software_withdraw_confirm) },
        confirmButton = {
            TextButton(onClick = { confirm = false; onWithdraw() }) { Text(AppStrings.privacy_software_withdraw_exit) }
        },
        dismissButton = { TextButton(onClick = { confirm = false }) { Text(AppStrings.ui_return) } },
    )
}
