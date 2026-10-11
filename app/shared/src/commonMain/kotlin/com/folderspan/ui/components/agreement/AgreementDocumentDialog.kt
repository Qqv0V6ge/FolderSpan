package com.folderspan.ui.components.agreement

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import strings.AppStrings

@Composable
fun AgreementDocumentDialog(
    title: String,
    paragraphs: List<String>,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        modifier = modifier,
        title = { Text(title) },
        text = {
            Column(
                modifier = Modifier
                    .heightIn(max = 480.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                paragraphs.forEachIndexed { index, paragraph ->
                    Text(
                        text = "${index + 1}. $paragraph",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(AppStrings.ui_got_it)
            }
        },
    )
}

@Preview
@Composable
private fun AgreementDocumentDialogPreview() {
    MaterialTheme {
        AgreementDocumentDialog(
            title = AppStrings.ui_privacy_policy,
            paragraphs = listOf(
                AppStrings.agreement_privacy_scope,
                AppStrings.agreement_privacy_local_files,
            ),
            onDismiss = {},
        )
    }
}
