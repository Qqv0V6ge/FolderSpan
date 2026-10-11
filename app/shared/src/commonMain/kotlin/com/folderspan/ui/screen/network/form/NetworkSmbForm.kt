package com.folderspan.ui.screen.network.form

import strings.AppStrings

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridScope
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import com.folderspan.ui.screen.network.NetworkEditSectionTitle

internal fun LazyGridScope.NetworkSmbForm(
    name: String,
    onNameChange: (String) -> Unit,
    nameError: Boolean,
    host: String,
    onHostChange: (String) -> Unit,
    hostError: Boolean,
    hostErrorMessage: String = AppStrings.ui_link_address_cannot_empty,
    resolvedBaseUrl: String = "",
    username: String,
    onUsernameChange: (String) -> Unit,
    password: String,
    onPasswordChange: (String) -> Unit,
    shareName: String,
    onShareNameChange: (String) -> Unit,
    shareNameError: Boolean,
    shareNameErrorMessage: String = AppStrings.ui_share_name_cannot_empty,
    resolvedShareName: String = "",
    domain: String,
    onDomainChange: (String) -> Unit,
) {
    NetworkStandardForm(
        name = name,
        onNameChange = onNameChange,
        nameError = nameError,
        host = host,
        onHostChange = onHostChange,
        hostError = hostError,
        hostErrorMessage = hostErrorMessage,
        resolvedBaseUrl = resolvedBaseUrl,
        username = username,
        onUsernameChange = onUsernameChange,
        password = password,
        onPasswordChange = onPasswordChange
    )

    item(span = { GridItemSpan(maxLineSpan) }) {
        NetworkEditSectionTitle(AppStrings.ui_smb_settings)
    }
    item {
        OutlinedTextField(
            value = shareName,
            onValueChange = onShareNameChange,
            label = { Text(AppStrings.ui_share_name) },
            isError = shareNameError,
            singleLine = true,
            supportingText = {
                if (shareNameError) {
                    Text(shareNameErrorMessage, color = MaterialTheme.colorScheme.error)
                } else if (shareName.isBlank() && resolvedShareName.isNotBlank()) {
                    Text(AppStrings.ui_will_use_arg0.format(arg0 = resolvedShareName))
                } else if (shareName.isBlank()) {
                    Text(AppStrings.ui_if_link_address_contains_share_name_it_can_left)
                }
            },
            modifier = Modifier.fillMaxWidth()
        )
    }
    item {
        OutlinedTextField(
            value = domain,
            onValueChange = onDomainChange,
            label = { Text(AppStrings.ui_domain_workgroup_optional) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )
    }
}
