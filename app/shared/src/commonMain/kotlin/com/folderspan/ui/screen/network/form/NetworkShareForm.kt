package com.folderspan.ui.screen.network.form

import strings.AppStrings

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridScope
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.folderspan.ui.components.fields.PasswordOutlinedTextField
import com.folderspan.ui.screen.network.NetworkEditSectionTitle
import com.folderspan.utils.ShareNetworkLink

internal fun LazyGridScope.NetworkShareForm(
    name: String,
    onNameChange: (String) -> Unit,
    address: String,
    onAddressChange: (String) -> Unit,
    addressError: Boolean,
    addressErrorMessage: String,
    resolvedBaseUrl: String,
    password: String,
    onPasswordChange: (String) -> Unit,
    passwordLabel: String,
    parsedShare: ShareNetworkLink?,
) {
    val passwordSupportingText: (@Composable () -> Unit)? =
        if (password.isBlank() && parsedShare?.password?.isNotBlank() == true) {
            { Text(AppStrings.ui_password_link_detected) }
        } else {
            null
        }

    item(span = { GridItemSpan(maxLineSpan) }) {
        NetworkEditSectionTitle(AppStrings.ui_basic_information)
    }
    item {
        NetworkNameField(name, onNameChange, parsedShare?.hostLabel ?: AppStrings.ui_link_sharing)
    }
    item {
        OutlinedTextField(
            value = address,
            onValueChange = onAddressChange,
            label = { Text(AppStrings.ui_link_address) },
            isError = addressError,
            singleLine = true,
            supportingText = {
                if (addressError) {
                    Text(addressErrorMessage, color = MaterialTheme.colorScheme.error)
                } else {
                    Text(AppStrings.ui_will_use_arg0.format(arg0 = resolvedBaseUrl))
                }
            },
            modifier = Modifier.fillMaxWidth()
        )
    }
    item(span = { GridItemSpan(maxLineSpan) }) {
        NetworkEditSectionTitle(AppStrings.ui_access_credentials)
    }
    item {
        PasswordOutlinedTextField(
            value = password,
            onValueChange = onPasswordChange,
            label = { Text(passwordLabel) },
            singleLine = true,
            supportingText = passwordSupportingText,
            modifier = Modifier.fillMaxWidth()
        )
    }
    item(span = { GridItemSpan(maxLineSpan) }) {
        Text(AppStrings.ui_read_only, style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
