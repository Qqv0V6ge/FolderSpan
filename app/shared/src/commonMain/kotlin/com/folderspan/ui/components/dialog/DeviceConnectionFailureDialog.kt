package com.folderspan.ui.components.dialog

import strings.AppStrings

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import com.folderspan.service.data.SocketDevice
import com.folderspan.service.http.tls.DEVICE_CERTIFICATE_FINGERPRINT_MISMATCH_MESSAGE
import com.folderspan.service.http.tls.SERVER_CERTIFICATE_FINGERPRINT_MISMATCH_MESSAGE
import com.folderspan.service.http.tls.containsMessage
import com.folderspan.ui.state.main.HttpDeviceConnectionFailure

enum class DeviceConnectionFailureDialogAction {
    Dismiss,
    TrustNewCertificate,
}

data class DeviceConnectionFailureDialogState(
    val title: String,
    val message: String,
    val confirmText: String = AppStrings.ui_got_it,
    val dismissText: String? = null,
    val confirmAction: DeviceConnectionFailureDialogAction = DeviceConnectionFailureDialogAction.Dismiss,
)

internal fun buildHttpDeviceConnectionFailureDialogOrNull(
    device: SocketDevice,
    error: Throwable,
): DeviceConnectionFailureDialogState? {
    val deviceName = device.name.ifBlank { AppStrings.ui_unknown_device }
    val endpoint = if (device.host.isBlank()) {
        AppStrings.ui_unknown_address
    } else {
        "${device.host}:${device.httpsPort}"
    }
    if (error.containsMessage(DEVICE_CERTIFICATE_FINGERPRINT_MISMATCH_MESSAGE)) {
        return DeviceConnectionFailureDialogState(
            title = AppStrings.ui_device_authentication_failed,
            message = AppStrings.ui_unable_connect_arg0_arg1_device_certificate_fingerprint_has_changed.format(arg0 = deviceName, arg1 = endpoint) +
                AppStrings.ui_please_confirm_that_this_same_trusted_device_before_trusting,
            confirmText = AppStrings.ui_trust_connect,
            dismissText = AppStrings.ui_cancel,
            confirmAction = DeviceConnectionFailureDialogAction.TrustNewCertificate,
        )
    }

    if (!error.containsMessage(SERVER_CERTIFICATE_FINGERPRINT_MISMATCH_MESSAGE)) return null

    return DeviceConnectionFailureDialogState(
        title = AppStrings.ui_device_authentication_failed,
        message = AppStrings.ui_unable_connect_arg0_arg1_arg2.format(arg0 = deviceName, arg1 = endpoint, arg2 = SERVER_CERTIFICATE_FINGERPRINT_MISMATCH_MESSAGE) +
            AppStrings.ui_please_confirm_that_other_party_s_device_trustworthy_then
    )
}

@Composable
fun HttpDeviceConnectionFailureDialog(
    failure: HttpDeviceConnectionFailure,
    onDismiss: () -> Unit,
    onTrustNewCertificate: () -> Unit,
) {
    val dialog = buildHttpDeviceConnectionFailureDialogOrNull(failure.device, failure.error)
    if (dialog == null) {
        LaunchedEffect(failure) {
            onDismiss()
        }
        return
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(dialog.title) },
        text = { Text(dialog.message) },
        confirmButton = {
            val onConfirm = when (dialog.confirmAction) {
                DeviceConnectionFailureDialogAction.Dismiss -> onDismiss
                DeviceConnectionFailureDialogAction.TrustNewCertificate -> onTrustNewCertificate
            }
            TextButton(onClick = onConfirm) {
                Text(dialog.confirmText)
            }
        },
        dismissButton = dialog.dismissText?.let { dismissText ->
            {
                TextButton(onClick = onDismiss) {
                    Text(dismissText)
                }
            }
        },
    )
}
