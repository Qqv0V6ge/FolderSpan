package com.folderspan.ui.components.dialog

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.folderspan.service.session.DeviceIdentityTrust
import strings.AppStrings

@Composable
internal fun DeviceIdentityTrustDialogHost(trust: DeviceIdentityTrust = DeviceIdentityTrust.shared) {
    DisposableEffect(trust) {
        trust.attachDialogHost()
        onDispose { trust.detachDialogHost() }
    }
    val request by trust.request.collectAsState()
    request?.let { pending ->
        DeviceIdentityTrustDialog(
            deviceName = pending.deviceName,
            endpoint = pending.endpoint,
            fingerprint = pending.fingerprint,
            previousFingerprint = pending.previousFingerprint,
            onTrust = { trust.respond(pending, true) },
            onCancel = { trust.respond(pending, false) },
        )
    }
}

@Composable
internal fun DeviceIdentityTrustDialog(
    deviceName: String,
    endpoint: String,
    fingerprint: String,
    previousFingerprint: String,
    onTrust: () -> Unit,
    onCancel: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text(AppStrings.ui_device_identity_title) },
        text = {
            SelectionContainer {
                Column(
                    modifier = Modifier.verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Text(deviceName, style = MaterialTheme.typography.titleSmall)
                    Text(endpoint)
                    Text(
                        if (previousFingerprint.isEmpty()) AppStrings.ui_device_identity_description
                        else AppStrings.ui_device_identity_changed,
                    )
                    Text(AppStrings.ui_device_identity_fingerprint)
                    Text(fingerprint.chunked(2).joinToString(":"), fontFamily = FontFamily.Monospace)
                    if (previousFingerprint.isNotEmpty()) {
                        Text(AppStrings.ui_device_identity_previous_fingerprint)
                        Text(previousFingerprint.chunked(2).joinToString(":"), fontFamily = FontFamily.Monospace)
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onTrust) { Text(AppStrings.ui_device_identity_trust_continue) }
        },
        dismissButton = {
            TextButton(onClick = onCancel) { Text(AppStrings.ui_cancel) }
        },
    )
}

@Preview
@Composable
private fun DeviceIdentityTrustDialogPreview() {
    MaterialTheme {
        DeviceIdentityTrustDialog(
            deviceName = "MacBook", endpoint = "10.0.0.122:12040",
            fingerprint = "1234567890ABCDEF".repeat(4), previousFingerprint = "",
            onTrust = {}, onCancel = {},
        )
    }
}
