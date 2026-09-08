package com.folderspan.ui.screen.network.form

import strings.AppStrings

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridScope
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.folderspan.ui.components.fields.PasswordOutlinedTextField
import com.folderspan.ui.screen.network.NetworkEditSectionTitle

internal fun LazyGridScope.NetworkS3Form(
    name: String,
    onNameChange: (String) -> Unit,
    nameError: Boolean,
    endpoint: String,
    onEndpointChange: (String) -> Unit,
    endpointError: Boolean,
    endpointErrorMessage: String,
    resolvedEndpoint: String,
    accessKeyId: String,
    onAccessKeyIdChange: (String) -> Unit,
    accessKeyIdError: Boolean,
    secretAccessKey: String,
    onSecretAccessKeyChange: (String) -> Unit,
    secretAccessKeyError: Boolean,
    sessionToken: String,
    onSessionTokenChange: (String) -> Unit,
    bucket: String,
    onBucketChange: (String) -> Unit,
    bucketError: Boolean,
    region: String,
    onRegionChange: (String) -> Unit,
    regionError: Boolean,
    forcePathStyle: Boolean,
    onForcePathStyleChange: (Boolean) -> Unit,
) {
    val secretAccessKeySupportingText: (@Composable () -> Unit)? =
        if (secretAccessKeyError) {
            { Text(AppStrings.ui_secretaccesskey_cannot_empty, color = MaterialTheme.colorScheme.error) }
        } else {
            null
        }

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
            value = endpoint,
            onValueChange = onEndpointChange,
            label = { Text(AppStrings.network_endpoint_label) },
            placeholder = { Text("https://s3.us-east-1.amazonaws.com") },
            isError = endpointError,
            singleLine = true,
            supportingText = {
                when {
                    endpointError -> Text(endpointErrorMessage, color = MaterialTheme.colorScheme.error)
                    endpoint.isNotBlank() -> Text(AppStrings.ui_will_use_arg0.format(arg0 = resolvedEndpoint))
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
            value = accessKeyId,
            onValueChange = onAccessKeyIdChange,
            label = { Text(AppStrings.network_access_key_id_label) },
            isError = accessKeyIdError,
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )
    }
    item {
        PasswordOutlinedTextField(
            value = secretAccessKey,
            onValueChange = onSecretAccessKeyChange,
            label = { Text(AppStrings.network_secret_access_key_label) },
            singleLine = true,
            isError = secretAccessKeyError,
            supportingText = secretAccessKeySupportingText,
            modifier = Modifier.fillMaxWidth()
        )
    }
    item {
        PasswordOutlinedTextField(
            value = sessionToken,
            onValueChange = onSessionTokenChange,
            label = { Text(AppStrings.ui_sessiontoken_optional) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )
    }

    item(span = { GridItemSpan(maxLineSpan) }) {
        NetworkEditSectionTitle(AppStrings.ui_s3_settings)
    }
    item {
        OutlinedTextField(
            value = bucket,
            onValueChange = onBucketChange,
            label = { Text(AppStrings.network_bucket_label) },
            isError = bucketError,
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )
    }
    item {
        OutlinedTextField(
            value = region,
            onValueChange = onRegionChange,
            label = { Text(AppStrings.network_region_label) },
            isError = regionError,
            singleLine = true,
            supportingText = {
                if (regionError) {
                    Text(AppStrings.ui_region_cannot_empty, color = MaterialTheme.colorScheme.error)
                }
            },
            modifier = Modifier.fillMaxWidth()
        )
    }
    item(span = { GridItemSpan(maxLineSpan) }) {
        ListItem(
            headlineContent = { Text(AppStrings.network_force_path_style_label) },
            trailingContent = {
                Switch(
                    checked = forcePathStyle,
                    onCheckedChange = onForcePathStyleChange
                )
            },
            modifier = Modifier.fillMaxWidth()
        )
    }
}
