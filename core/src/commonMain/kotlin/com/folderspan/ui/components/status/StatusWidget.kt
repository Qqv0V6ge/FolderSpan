package com.folderspan.ui.components.status

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp

/**
 * 统一展示加载、空数据、错误等页面状态。
 *
 * [visualContent] 只负责提供图标、进度指示器等视觉内容；组件统一管理文案、操作、
 * 间距、颜色和无障碍状态播报。操作仅在 [actionLabel] 与 [onAction] 同时提供时显示。
 */
@Composable
fun StatusWidget(
    title: String,
    modifier: Modifier = Modifier,
    supportingText: String? = null,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
    containerColor: Color = Color.Transparent,
    contentColor: Color = MaterialTheme.colorScheme.onSurface,
    visualContent: @Composable () -> Unit,
) {
    Surface(
        modifier = modifier,
        color = containerColor,
        contentColor = contentColor,
    ) {
        Column(
            modifier = Modifier
                .padding(StatusWidgetDefaults.ContentPadding)
                .semantics { liveRegion = LiveRegionMode.Polite },
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(
                space = StatusWidgetDefaults.ContentSpacing,
                alignment = Alignment.CenterVertically,
            ),
        ) {
            visualContent()
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                textAlign = TextAlign.Center,
                modifier = Modifier.semantics { heading() },
            )
            supportingText?.let { message ->
                Text(
                    text = message,
                    style = MaterialTheme.typography.bodyMedium,
                    textAlign = TextAlign.Center,
                )
            }
            if (actionLabel != null && onAction != null) {
                Button(
                    onClick = onAction,
                    modifier = Modifier
                        .padding(top = StatusWidgetDefaults.ActionTopSpacing)
                        .widthIn(min = StatusWidgetDefaults.ActionMinWidth)
                        .heightIn(min = StatusWidgetDefaults.MinimumTouchTarget),
                ) {
                    Text(
                        text = actionLabel,
                        style = MaterialTheme.typography.labelLarge,
                    )
                }
            }
        }
    }
}

object StatusWidgetDefaults {
    val ContentPadding = 24.dp
    val ContentSpacing = 12.dp
    val ActionTopSpacing = 8.dp
    val ActionMinWidth = 160.dp
    val VisualSize = 48.dp
    val MinimumTouchTarget = 48.dp
}
