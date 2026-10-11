package com.folderspan.ui.components.file

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.absoluteOffset
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.AbsoluteAlignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.folderspan.data.file.FileSimpleInfo
import com.folderspan.ui.state.file.FileDragSession
import kotlin.math.roundToInt
import strings.AppStrings

@Composable
internal fun FileDragPreview(session: FileDragSession?, modifier: Modifier = Modifier) {
    if (session == null || session.external || session.files.isEmpty()) return
    val file = session.previewFile ?: session.files.first()
    var origin by remember { mutableStateOf(Offset.Zero) }
    var viewportSize by remember { mutableStateOf(IntSize.Zero) }
    var previewSize by remember { mutableStateOf(IntSize.Zero) }
    val pointerGap = with(LocalDensity.current) { 32.dp.toPx() }
    Box(
        modifier = modifier.fillMaxSize().onGloballyPositioned { coordinates ->
            origin = coordinates.positionInRoot()
            viewportSize = coordinates.size
        },
        contentAlignment = AbsoluteAlignment.TopLeft,
    ) {
        FileDragPreviewContent(
            file = file,
            count = session.files.size,
            modifier = Modifier.absoluteOffset {
                fileDragPreviewOffset(
                    pointer = Offset(session.horizontal, session.vertical) - origin,
                    viewportSize = viewportSize,
                    previewSize = previewSize,
                    pointerGap = pointerGap,
                    abovePointer = session.isTouch,
                )
            }.onSizeChanged { previewSize = it },
        )
    }
}

@Composable
internal fun FileDragPreviewContent(file: FileSimpleInfo, count: Int, modifier: Modifier = Modifier) {
    val colors = MaterialTheme.colorScheme
    val shape = MaterialTheme.shapes.medium
    Row(
        modifier = modifier
            .widthIn(max = 280.dp)
            .shadow(8.dp, shape)
            .background(colors.surfaceContainerHigh.copy(alpha = 0.96f), shape)
            .border(1.dp, colors.outlineVariant, shape)
            .semantics(mergeDescendants = true) {}
            .padding(12.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CompositionLocalProvider(LocalContentColor provides colors.onSurface) {
            FileIcon(file, Modifier.size(32.dp))
        }
        Column(modifier = Modifier.weight(1f, fill = false), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                text = file.name,
                color = colors.onSurface,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Outlined.ContentCopy, null, Modifier.size(16.dp), tint = colors.onSurfaceVariant)
                Text(AppStrings.ui_copy, color = colors.onSurfaceVariant, style = MaterialTheme.typography.labelMedium)
                if (count > 1) {
                    Text(
                        AppStrings.ui_arg0_items.format(arg0 = count.toString()),
                        color = colors.onSurfaceVariant,
                        style = MaterialTheme.typography.labelMedium,
                    )
                }
            }
        }
    }
}

internal fun fileDragPreviewOffset(
    pointer: Offset,
    viewportSize: IntSize,
    previewSize: IntSize,
    pointerGap: Float,
    abovePointer: Boolean,
): IntOffset {
    val horizontal: Float
    val vertical: Float
    if (abovePointer) {
        val right = pointer.x + pointerGap
        val left = pointer.x - pointerGap - previewSize.width
        val below = pointer.y + pointerGap
        val above = pointer.y - pointerGap - previewSize.height
        horizontal = if (right + previewSize.width <= viewportSize.width) right else left
        vertical = if (above >= 0f) above else below
    } else {
        horizontal = pointer.x - previewSize.width / 2f
        vertical = pointer.y - previewSize.height / 2f
    }
    return IntOffset(
        horizontal.roundToInt().coerceIn(0, (viewportSize.width - previewSize.width).coerceAtLeast(0)),
        vertical.roundToInt().coerceIn(0, (viewportSize.height - previewSize.height).coerceAtLeast(0)),
    )
}
