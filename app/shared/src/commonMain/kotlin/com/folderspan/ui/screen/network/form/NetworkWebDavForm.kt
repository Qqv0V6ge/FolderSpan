package com.folderspan.ui.screen.network.form

import strings.AppStrings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridScope
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material3.*
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.folderspan.data.main.network.WebDavAuthType
import com.folderspan.ui.components.fields.PasswordOutlinedTextField
import com.folderspan.ui.components.menu.EditableExposedDropdownMenu
import com.folderspan.ui.components.model.StringListUiState
import com.folderspan.ui.screen.network.NetworkEditSectionTitle

internal data class WebDavHeaderEntry(
    val key: String,
    val value: String,
)

internal val WebDavHeaderEntryListSaver = listSaver(
    save = { headers -> headers.flatMap { header -> listOf(header.key, header.value) } },
    restore = { values ->
        values.chunked(2).map { chunk ->
            WebDavHeaderEntry(
                key = chunk.getOrElse(0) { "" },
                value = chunk.getOrElse(1) { "" }
            )
        }
    }
)

internal fun LazyGridScope.NetworkWebDavForm(
    name: String,
    onNameChange: (String) -> Unit,
    nameError: Boolean,
    host: String,
    onHostChange: (String) -> Unit,
    hostError: Boolean,
    hostErrorMessage: String = AppStrings.ui_link_address_cannot_empty,
    resolvedBaseUrl: String = "",
    authType: WebDavAuthType,
    onAuthTypeChange: (WebDavAuthType) -> Unit,
    username: String,
    onUsernameChange: (String) -> Unit,
    password: String,
    onPasswordChange: (String) -> Unit,
    token: String,
    onTokenChange: (String) -> Unit,
    tokenHeaderName: String,
    onTokenHeaderNameChange: (String) -> Unit,
    tokenPrefix: String,
    onTokenPrefixChange: (String) -> Unit,
    headers: List<WebDavHeaderEntry>,
    onHeaderKeyChange: (Int, String) -> Unit,
    onHeaderValueChange: (Int, String) -> Unit,
    onAddHeader: () -> Unit,
    onRemoveHeader: (Int) -> Unit,
) {
    val resolvedLabel = resolvedBaseUrl.ifBlank { host }
    val authOptions = WebDavAuthType.entries.map { it.label() }

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
        EditableExposedDropdownMenu(
            optionsUiState = StringListUiState(authOptions),
            value = authType.label(),
            onValueChange = { label -> onAuthTypeChange(webDavAuthTypeFromLabel(label)) },
            label = { Text(AppStrings.ui_certification_type) },
            singleLine = true,
            readOnly = true,
            modifier = Modifier.fillMaxWidth()
        )
    }
    if (authType == WebDavAuthType.Basic || authType == WebDavAuthType.Digest) {
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
    if (authType == WebDavAuthType.Token) {
        item {
            OutlinedTextField(
                value = tokenHeaderName,
                onValueChange = onTokenHeaderNameChange,
                label = { Text(AppStrings.ui_token_header_name) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
        }
        item {
            OutlinedTextField(
                value = tokenPrefix,
                onValueChange = onTokenPrefixChange,
                label = { Text(AppStrings.ui_token_prefix) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
        }
        item {
            OutlinedTextField(
                value = token,
                onValueChange = onTokenChange,
                label = { Text(AppStrings.network_token_label) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
        }
    }

    item(span = { GridItemSpan(maxLineSpan) }) {
        NetworkEditSectionTitle(AppStrings.ui_custom_header)
    }
    headers.forEachIndexed { index, header ->
        item(span = { GridItemSpan(maxLineSpan) }) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                OutlinedTextField(
                    value = header.key,
                    onValueChange = { onHeaderKeyChange(index, it) },
                    label = { Text(AppStrings.ui_header_name) },
                    singleLine = true,
                    modifier = Modifier.weight(1f)
                )
                OutlinedTextField(
                    value = header.value,
                    onValueChange = { onHeaderValueChange(index, it) },
                    label = { Text(AppStrings.ui_header_value) },
                    singleLine = true,
                    modifier = Modifier.weight(1f)
                )
                IconButton(
                    onClick = { onRemoveHeader(index) },
                    modifier = Modifier.padding(top = 4.dp)
                ) {
                    Icon(Icons.Outlined.Delete, contentDescription = AppStrings.ui_delete)
                }
            }
        }
    }
    item(span = { GridItemSpan(maxLineSpan) }) {
        TextButton(onClick = onAddHeader) {
            Icon(Icons.Default.Add, contentDescription = null)
            Text(AppStrings.ui_add_header)
        }
    }
}

private fun WebDavAuthType.label(): String = when (this) {
    WebDavAuthType.Basic -> "Basic"
    WebDavAuthType.Digest -> "Digest"
    WebDavAuthType.Token -> "Token"
}

private fun webDavAuthTypeFromLabel(label: String): WebDavAuthType {
    return when (label) {
        "Digest" -> WebDavAuthType.Digest
        "Token" -> WebDavAuthType.Token
        else -> WebDavAuthType.Basic
    }
}
