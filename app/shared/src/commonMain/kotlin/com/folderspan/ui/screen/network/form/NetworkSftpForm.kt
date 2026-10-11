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
import com.folderspan.ui.components.menu.EditableExposedOutlinedDropdownMenu
import com.folderspan.ui.components.model.StringListUiState
import com.folderspan.ui.screen.network.NetworkEditSectionTitle

internal enum class SftpAuthType {
    Password,
    PrivateKey,
}

internal fun LazyGridScope.NetworkSftpForm(
    name: String,
    onNameChange: (String) -> Unit,
    nameError: Boolean,
    host: String,
    onHostChange: (String) -> Unit,
    hostError: Boolean,
    hostErrorMessage: String = AppStrings.ui_link_address_cannot_empty,
    resolvedBaseUrl: String = "",
    authType: SftpAuthType,
    onAuthTypeChange: (SftpAuthType) -> Unit,
    username: String,
    onUsernameChange: (String) -> Unit,
    password: String,
    onPasswordChange: (String) -> Unit,
    privateKey: String,
    onPrivateKeyChange: (String) -> Unit,
    knownHosts: String,
    onKnownHostsChange: (String) -> Unit,
) {
    val resolvedLabel = resolvedBaseUrl.ifBlank { host }
    val authOptions = SftpAuthType.entries.map { item -> item.label() }

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
    item(span = { GridItemSpan(maxLineSpan) }) {
        EditableExposedOutlinedDropdownMenu(
            optionsUiState = StringListUiState(authOptions),
            value = authType.label(),
            onValueChange = { label -> onAuthTypeChange(sftpAuthTypeFromLabel(label)) },
            label = { Text(AppStrings.ui_authentication_method) },
            singleLine = true,
            readOnly = true,
            modifier = Modifier.fillMaxWidth()
        )
    }
    item(span = { GridItemSpan(maxLineSpan) }) {
        OutlinedTextField(
            value = username,
            onValueChange = onUsernameChange,
            label = { Text(AppStrings.ui_username) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )
    }
    when (authType) {
        SftpAuthType.Password -> {
            item(span = { GridItemSpan(maxLineSpan) }) {
                PasswordOutlinedTextField(
                    value = password,
                    onValueChange = onPasswordChange,
                    label = { Text(AppStrings.ui_password) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }

        SftpAuthType.PrivateKey -> {
            item(span = { GridItemSpan(maxLineSpan) }) {
                OutlinedTextField(
                    value = privateKey,
                    onValueChange = onPrivateKeyChange,
                    label = { Text(AppStrings.ui_private_key) },
                    minLines = 3,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }
    }

    item(span = { GridItemSpan(maxLineSpan) }) {
        NetworkEditSectionTitle(AppStrings.ui_sftp_settings)
    }
    item(span = { GridItemSpan(maxLineSpan) }) {
        OutlinedTextField(
            value = knownHosts,
            onValueChange = onKnownHostsChange,
            label = { Text(AppStrings.ui_known_hosts_optional) },
            supportingText = { Text(AppStrings.ui_sftp_known_hosts_help) },
            minLines = 3,
            modifier = Modifier.fillMaxWidth()
        )
    }
}

internal fun sftpAuthTypeFromPrivateKey(privateKey: String): SftpAuthType {
    return if (privateKey.isNotBlank()) SftpAuthType.PrivateKey else SftpAuthType.Password
}

internal fun SftpAuthType.selectedPassword(password: String): String {
    return if (this == SftpAuthType.Password) password else ""
}

internal fun SftpAuthType.selectedPrivateKey(privateKey: String): String {
    return if (this == SftpAuthType.PrivateKey) privateKey else ""
}

private fun SftpAuthType.label(): String = when (this) {
    SftpAuthType.Password -> AppStrings.ui_password
    SftpAuthType.PrivateKey -> AppStrings.ui_private_key
}

private fun sftpAuthTypeFromLabel(label: String): SftpAuthType {
    return when (label) {
        AppStrings.ui_private_key -> SftpAuthType.PrivateKey
        else -> SftpAuthType.Password
    }
}
