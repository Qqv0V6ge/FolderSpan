package com.folderspan.ui.screen.network.form

import strings.AppStrings

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridScope
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import com.folderspan.ui.components.fields.PasswordOutlinedTextField
import com.folderspan.ui.screen.network.NetworkEditSectionTitle

internal fun LazyGridScope.NetworkStandardForm(
    name: String,
    onNameChange: (String) -> Unit,
    nameError: Boolean,
    host: String,
    onHostChange: (String) -> Unit,
    hostError: Boolean,
    username: String,
    onUsernameChange: (String) -> Unit,
    password: String,
    onPasswordChange: (String) -> Unit,
    resolvedBaseUrl: String = "",
    hostErrorMessage: String = AppStrings.ui_link_address_cannot_empty,
) {
    val resolvedLabel = resolvedBaseUrl.ifBlank { host }
    item(span = { GridItemSpan(maxLineSpan) }) {
        NetworkEditSectionTitle(AppStrings.ui_basic_information)
    }
    item {
        OutlinedTextField(
            value = name,
            onValueChange = onNameChange,
            label = { Text(AppStrings.ui_name) },
            isError = nameError,
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )
    }
    item {
        OutlinedTextField(
            value = host,
            onValueChange = onHostChange,
            label = { Text(AppStrings.ui_link_address) },
            isError = hostError,
            singleLine = true,
            supportingText = {
                if (hostError) {
                    Text(hostErrorMessage, color = MaterialTheme.colorScheme.error)
                } else {
                    Text(AppStrings.ui_will_use_arg0.format(arg0 = resolvedLabel))
                }
            },
            modifier = Modifier.fillMaxWidth()
        )
    }
    item(span = { GridItemSpan(maxLineSpan) }) {
        NetworkEditSectionTitle(AppStrings.ui_certification)
    }
    item {
        OutlinedTextField(
            value = username,
            onValueChange = onUsernameChange,
            label = { Text(AppStrings.ui_username) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )
    }
    item {
        PasswordOutlinedTextField(
            value = password,
            onValueChange = onPasswordChange,
            label = { Text(AppStrings.ui_password) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )
    }
}
