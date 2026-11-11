package com.folderspan.ui.screen.network.form

import strings.AppStrings

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridScope
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.folderspan.ui.components.fields.PasswordOutlinedTextField
import com.folderspan.ui.screen.network.NetworkEditSectionTitle
import com.folderspan.utils.parseNetworkAddress
import com.folderspan.utils.parseSmbAddress
import io.ktor.http.Url

internal fun defaultNetworkName(protocol: String, host: String): String {
    val label = when (protocol) {
        "HttpDirectory" -> AppStrings.storage_http_directory
        "GoogleDrive" -> "Google Drive"
        else -> protocol
    }
    if (host.isBlank()) return label
    val hostLabel = when (protocol) {
        "GoogleDrive", "Dropbox" -> null
        "FTP", "SFTP", "NFS" -> parseNetworkAddress(host)?.hostLabel
        "SMB" -> parseSmbAddress(host)?.hostLabel
        else -> runCatching { Url(host.trim()).host }.getOrNull()
    }
    return hostLabel?.takeIf { it.isNotBlank() } ?: label
}

@Composable
internal fun NetworkNameField(name: String, onNameChange: (String) -> Unit, defaultName: String) {
    OutlinedTextField(
        value = name,
        onValueChange = onNameChange,
        label = { Text(AppStrings.ui_name) },
        placeholder = { Text(defaultName) },
        supportingText = { Text(AppStrings.ui_leave_blank_use_arg0.format(arg0 = defaultName)) },
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
internal fun NetworkFormSwitch(label: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)
            .toggleable(checked, role = Role.Switch, onValueChange = onCheckedChange),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, modifier = Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = null)
    }
}

internal fun LazyGridScope.NetworkStandardForm(
    name: String,
    onNameChange: (String) -> Unit,
    host: String,
    onHostChange: (String) -> Unit,
    hostError: Boolean,
    username: String,
    onUsernameChange: (String) -> Unit,
    password: String,
    onPasswordChange: (String) -> Unit,
    resolvedBaseUrl: String = "",
    hostErrorMessage: String = AppStrings.ui_link_address_cannot_empty,
    protocol: String = "FTP",
) {
    val resolvedLabel = resolvedBaseUrl.ifBlank { host }
    item(span = { GridItemSpan(maxLineSpan) }) {
        NetworkEditSectionTitle(AppStrings.ui_basic_information)
    }
    item {
        NetworkNameField(name, onNameChange, defaultNetworkName(protocol, host))
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
