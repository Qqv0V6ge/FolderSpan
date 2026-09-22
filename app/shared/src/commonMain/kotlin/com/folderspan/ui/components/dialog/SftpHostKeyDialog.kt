package com.folderspan.ui.components.dialog

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.folderspan.service.network.SftpHostKey
import com.folderspan.service.network.SftpHostKeyTrust
import strings.AppStrings

@Composable
internal fun SftpHostKeyDialogHost(trust: SftpHostKeyTrust = SftpHostKeyTrust.shared) {
    DisposableEffect(trust) {
        trust.attachDialogHost()
        onDispose { trust.detachDialogHost() }
    }
    val request by trust.request.collectAsState()
    request?.let { pending ->
        SftpHostKeyDialog(
            hostKey = pending.hostKey,
            onTrust = { trust.respond(pending, true) },
            onCancel = { trust.respond(pending, false) },
        )
    }
}

@Composable
internal fun SftpHostKeyDialog(
    hostKey: SftpHostKey,
    onTrust: () -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
) {
    AlertDialog(
        modifier = modifier,
        onDismissRequest = onCancel,
        title = { Text(AppStrings.ui_sftp_trust_host_title) },
        text = {
            SelectionContainer {
                Column(
                    modifier = Modifier.verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Text(hostKey.endpoint)
                    Text(hostKey.fingerprint)
                    Text(AppStrings.ui_sftp_trust_host_description)
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onTrust) { Text(AppStrings.ui_trust_connect) }
        },
        dismissButton = {
            TextButton(onClick = onCancel) { Text(AppStrings.ui_cancel) }
        },
    )
}

@Preview
@Composable
private fun SftpHostKeyDialogPreview() {
    SftpHostKeyDialog(
        hostKey = SftpHostKey("sftp.example", 2220, "SHA256:Q7De9bNz96o2kabaaWPYGkPbUTMFZVnM/wbLJh45CS4"),
        onTrust = {},
        onCancel = {},
    )
}
