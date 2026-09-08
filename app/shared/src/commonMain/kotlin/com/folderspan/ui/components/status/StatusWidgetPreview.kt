package com.folderspan.ui.components.status

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import strings.AppStrings

@Preview
@Composable
private fun StatusWidgetPreview() {
    MaterialTheme {
        StatusWidget(
            title = AppStrings.ui_file_loading_failed,
            supportingText = AppStrings.ui_check_network_connection_and_retry,
            actionLabel = AppStrings.ui_try_again,
            onAction = {},
            containerColor = MaterialTheme.colorScheme.errorContainer,
            contentColor = MaterialTheme.colorScheme.onErrorContainer,
            modifier = Modifier.fillMaxSize(),
        ) {
            Icon(
                imageVector = Icons.Outlined.ErrorOutline,
                contentDescription = null,
                modifier = Modifier.size(StatusWidgetDefaults.VisualSize),
            )
        }
    }
}
