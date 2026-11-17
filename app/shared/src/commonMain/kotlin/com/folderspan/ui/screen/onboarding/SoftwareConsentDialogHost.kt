package com.folderspan.ui.screen.onboarding

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.folderspan.ui.components.agreement.AgreementDocumentDialog
import com.folderspan.utils.SettingsUtils
import strings.AppStrings

@Composable
internal fun SoftwareConsentDialogHost() {
    val requested by SettingsUtils.softwareConsentRequested.collectAsState()
    if (!requested) return
    SoftwareConsentDialog(onDismiss = SettingsUtils::dismissSoftwareConsentRequest)
}

@Composable
internal fun SoftwareConsentDialog(onDismiss: () -> Unit) {
    var accepted by rememberSaveable { mutableStateOf(false) }
    var documentType by remember { mutableStateOf<WelcomeAgreementDocumentType?>(null) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(AppStrings.welcome_agree_unlock) },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(AppStrings.welcome_unlock_description)
                AgreementConsent(accepted, { accepted = it }, { documentType = it })
            }
        },
        confirmButton = {
            TextButton(
                enabled = accepted,
                onClick = {
                    SettingsUtils.setWelcomeAgreementAccepted(true)
                    onDismiss()
                },
            ) { Text(AppStrings.welcome_agree_unlock) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(AppStrings.welcome_local_continue) }
        },
    )
    documentType?.let { type ->
        val document = welcomeAgreementDocument(type)
        AgreementDocumentDialog(document.title, document.paragraphs, onDismiss = { documentType = null })
    }
}
