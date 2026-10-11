package com.folderspan.ui.screen.network.form

import strings.AppStrings

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridScope
import androidx.compose.material3.ListItem
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import com.folderspan.data.main.network.FtpPathEncoding
import com.folderspan.ui.components.menu.EditableExposedDropdownMenu
import com.folderspan.ui.components.model.StringListUiState
import com.folderspan.ui.screen.network.NetworkEditSectionTitle

internal fun LazyGridScope.NetworkFtpForm(
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
    passiveMode: Boolean,
    onPassiveModeChange: (Boolean) -> Unit,
    ftpsEnabled: Boolean,
    onFtpsEnabledChange: (Boolean) -> Unit,
    pathEncoding: FtpPathEncoding,
    onPathEncodingChange: (FtpPathEncoding) -> Unit,
) {
    val encodingOptions = FtpPathEncoding.entries.map { it.label() }

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
        NetworkEditSectionTitle(AppStrings.ui_ftp_settings)
    }
    item(span = { GridItemSpan(maxLineSpan) }) {
        EditableExposedDropdownMenu(
            optionsUiState = StringListUiState(encodingOptions),
            value = pathEncoding.label(),
            onValueChange = { label -> onPathEncodingChange(ftpPathEncodingFromLabel(label)) },
            label = { Text(AppStrings.ui_encoding) },
            singleLine = true,
            readOnly = true,
            modifier = Modifier.fillMaxWidth()
        )
    }
    item(span = { GridItemSpan(maxLineSpan) }) {
        ListItem(
            headlineContent = { Text(AppStrings.ui_passive_mode) },
            trailingContent = {
                Switch(
                    checked = passiveMode,
                    onCheckedChange = onPassiveModeChange
                )
            },
            modifier = Modifier.fillMaxWidth()
        )
    }
    item(span = { GridItemSpan(maxLineSpan) }) {
        ListItem(
            headlineContent = { Text(AppStrings.ui_enable_ftps) },
            trailingContent = {
                Switch(
                    checked = ftpsEnabled,
                    onCheckedChange = onFtpsEnabledChange
                )
            },
            modifier = Modifier.fillMaxWidth()
        )
    }
}

private fun FtpPathEncoding.label(): String = when (this) {
    FtpPathEncoding.Auto -> AppStrings.ui_auto_recommended
    FtpPathEncoding.Utf8 -> "UTF-8"
    FtpPathEncoding.Gb18030 -> "GB18030"
    FtpPathEncoding.ShiftJis -> "Shift_JIS"
    FtpPathEncoding.Cp949 -> "CP949"
    FtpPathEncoding.Windows1251 -> "Windows-1251"
}

private fun ftpPathEncodingFromLabel(label: String): FtpPathEncoding {
    return when (label) {
        "UTF-8" -> FtpPathEncoding.Utf8
        "GB18030" -> FtpPathEncoding.Gb18030
        "Shift_JIS" -> FtpPathEncoding.ShiftJis
        "CP949" -> FtpPathEncoding.Cp949
        "Windows-1251" -> FtpPathEncoding.Windows1251
        else -> FtpPathEncoding.Auto
    }
}
