package com.folderspan.ui.screen.network.form

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.folderspan.ui.components.fields.PasswordOutlinedTextField
import strings.AppStrings

@Composable
internal fun StorageContentSection(state: StorageContentFormState, modifier: Modifier = Modifier) {
    if (!state.supportedProtocol) return
    Column(modifier.fillMaxWidth().testTag("storage-content-section"), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(Modifier.fillMaxWidth().heightIn(min = 56.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(AppStrings.storage_content_title, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
            Switch(
                checked = state.enabled,
                enabled = !state.busy && (state.canConfigure || state.enabled),
                onCheckedChange = state::changeEnabled,
                modifier = Modifier.testTag("storage-content-toggle").semantics { contentDescription = AppStrings.storage_content_title },
            )
        }
        if (state.enabled) {
            PasswordOutlinedTextField(
                value = state.password,
                onValueChange = state::editPassword,
                label = { Text(AppStrings.storage_content_password) },
                enabled = !state.busy,
                isError = state.validationMessage != null,
                supportingText = {
                    Text(state.validationMessage ?: if (state.existingLocation) AppStrings.storage_content_existing_password_hint
                        else AppStrings.storage_content_password_length)
                },
                modifier = Modifier.fillMaxWidth().testTag("storage-content-password-input"),
            )
            TextButton(
                onClick = state::generatePassword,
                enabled = !state.busy,
                modifier = Modifier.testTag("storage-content-generate-password"),
            ) {
                Icon(Icons.Default.AutoAwesome, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text(AppStrings.storage_content_generate_password)
            }
            Text(AppStrings.storage_content_loss_notice, style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        val problem = when {
            state.location?.available == false -> AppStrings.storage_content_unavailable
            state.location?.writable == false -> AppStrings.ui_read_only
            else -> state.message
        }
        problem?.let {
            Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
        }
        if (state.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
    }
}
