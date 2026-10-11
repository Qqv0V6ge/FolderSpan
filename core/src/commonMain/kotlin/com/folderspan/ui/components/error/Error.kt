package com.folderspan.ui.components.error

import strings.AppStrings

import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import com.folderspan.ui.components.status.StatusWidget
import com.folderspan.ui.components.status.StatusWidgetDefaults

// 空数据错误
@Composable
fun ErrorEmptyData(
    message: String = AppStrings.ui_data_not_found,
    modifier: Modifier = Modifier,
    supportingText: String? = null,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
) {
    ErrorBase(
        text = message,
        modifier = modifier,
        supportingText = supportingText,
        actionLabel = actionLabel,
        onAction = onAction,
    )
}

// 连接错误
@Composable
fun ErrorConnection(
    message: String? = null,
    modifier: Modifier = Modifier,
    supportingText: String? = null,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
) {
    ErrorBase(
        text = message ?: AppStrings.ui_connection_failed_please_check_network_connection_device_status,
        imageVector = Icons.Default.CloudOff,
        modifier = modifier,
        supportingText = supportingText,
        actionLabel = actionLabel,
        onAction = onAction,
    )
}

// 静止访问错误
@Composable
fun ErrorBlock(
    message: String?,
    modifier: Modifier = Modifier,
    supportingText: String? = null,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
) {
    ErrorBase(
        text = message ?: "",
        imageVector = Icons.Default.Block,
        modifier = modifier,
        supportingText = supportingText,
        actionLabel = actionLabel,
        onAction = onAction,
    )
}

@Composable
fun ErrorBase(
    text: String,
    imageVector: ImageVector = Icons.Outlined.Info,
    modifier: Modifier = Modifier,
    supportingText: String? = null,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
) {
    StatusWidget(
        title = text,
        modifier = modifier,
        supportingText = supportingText,
        actionLabel = actionLabel,
        onAction = onAction,
    ) {
        Icon(
            imageVector = imageVector,
            contentDescription = null,
            modifier = Modifier.size(StatusWidgetDefaults.VisualSize),
        )
    }
}
