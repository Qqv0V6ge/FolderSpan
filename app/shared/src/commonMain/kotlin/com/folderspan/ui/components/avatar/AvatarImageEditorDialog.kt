package com.folderspan.ui.components.avatar

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.RotateLeft
import androidx.compose.material.icons.automirrored.filled.RotateRight
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material.icons.filled.SwapVert
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.FilledTonalIconToggleButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import strings.AppStrings

internal const val AVATAR_EDITOR_COMPACT_TEST_TAG = "avatar_editor_compact"
internal const val AVATAR_EDITOR_EXPANDED_TEST_TAG = "avatar_editor_expanded"
internal const val AVATAR_EDITOR_CROP_TEST_TAG = "avatar_editor_crop"
internal const val AVATAR_EDITOR_PROGRESS_TEST_TAG = "avatar_editor_progress"

internal enum class AvatarEditorLayout {
    Compact,
    Expanded,
}

internal fun resolveAvatarEditorLayout(width: Dp): AvatarEditorLayout =
    if (width >= 840.dp) AvatarEditorLayout.Expanded else AvatarEditorLayout.Compact

internal fun resolveAvatarCropEdge(availableWidth: Dp, availableHeight: Dp): Dp =
    minOf(availableWidth, availableHeight, 640.dp).coerceAtLeast(0.dp)

internal fun avatarEditorWheelZoom(scrollY: Float): Float {
    if (!scrollY.isFinite() || scrollY == 0f) return 1f
    return (1f - scrollY * 0.12f).coerceIn(0.5f, 2f)
}

@Composable
internal fun AvatarImageEditorDialog(
    source: ImageBitmap,
    isProcessing: Boolean,
    onDismiss: () -> Unit,
    onConfirm: (AvatarImageEditorState, Float) -> Unit,
) {
    var state by remember(source) { mutableStateOf(AvatarImageEditorState()) }
    var cropSizePx by remember { mutableFloatStateOf(0f) }

    fun updateCropSize(size: Float) {
        cropSizePx = size
        if (size > 0f) {
            state = state.normalized(source.width, source.height, size)
        }
    }

    fun updateState(transform: (AvatarImageEditorState) -> AvatarImageEditorState) {
        val transformed = transform(state)
        state = if (cropSizePx > 0f) {
            transformed.normalized(source.width, source.height, cropSizePx)
        } else {
            transformed
        }
    }

    fun transform(zoom: Float, panX: Float, panY: Float) {
        if (cropSizePx > 0f) {
            state = state.transformed(
                zoomChange = zoom,
                panChange = Offset(panX, panY),
                sourceWidth = source.width,
                sourceHeight = source.height,
                cropSize = cropSizePx,
            )
        }
    }

    Dialog(
        onDismissRequest = { if (!isProcessing) onDismiss() },
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        BoxWithConstraints(
            modifier = Modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.safeDrawing),
            contentAlignment = Alignment.Center,
        ) {
            val outerPadding = if (maxWidth < 600.dp || maxHeight < 480.dp) 8.dp else 20.dp
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(outerPadding),
                contentAlignment = Alignment.Center,
            ) {
                Surface(
                    modifier = Modifier
                        .widthIn(max = 1120.dp)
                        .heightIn(max = 820.dp)
                        .fillMaxSize(),
                    shape = MaterialTheme.shapes.extraLarge,
                    color = MaterialTheme.colorScheme.surfaceContainerHigh,
                    contentColor = MaterialTheme.colorScheme.onSurface,
                    tonalElevation = 6.dp,
                    shadowElevation = 12.dp,
                ) {
                    BoxWithConstraints(Modifier.fillMaxSize()) {
                        val expanded = resolveAvatarEditorLayout(maxWidth) == AvatarEditorLayout.Expanded
                        Column(
                            modifier = Modifier
                                .fillMaxSize()
                                .semantics {
                                    contentDescription = if (expanded) {
                                        AVATAR_EDITOR_EXPANDED_TEST_TAG
                                    } else {
                                        AVATAR_EDITOR_COMPACT_TEST_TAG
                                    }
                                },
                        ) {
                            AvatarEditorHeader()
                            if (expanded) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .weight(1f)
                                        .padding(horizontal = 24.dp, vertical = 12.dp),
                                    horizontalArrangement = Arrangement.spacedBy(24.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    AvatarEditingSurface(
                                        source = source,
                                        state = state,
                                        enabled = !isProcessing,
                                        onCropSizeChanged = ::updateCropSize,
                                        onTransform = ::transform,
                                        modifier = Modifier.weight(1f),
                                    )
                                    AvatarEditorTools(
                                        state = state,
                                        isProcessing = isProcessing,
                                        onStateChange = { state = it },
                                        normalize = ::updateState,
                                        modifier = Modifier.widthIn(max = 336.dp),
                                    )
                                }
                            } else {
                                Column(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .weight(1f)
                                        .padding(horizontal = 16.dp, vertical = 8.dp),
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                ) {
                                    AvatarEditingSurface(
                                        source = source,
                                        state = state,
                                        enabled = !isProcessing,
                                        onCropSizeChanged = ::updateCropSize,
                                        onTransform = ::transform,
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .weight(1f),
                                    )
                                }
                            }
                            AvatarEditorFooter(
                                isProcessing = isProcessing,
                                onDismiss = onDismiss,
                                onConfirm = { onConfirm(state, cropSizePx) },
                                compactTools = if (expanded) {
                                    null
                                } else {
                                    {
                                        AvatarEditorCompactTools(
                                            state = state,
                                            isProcessing = isProcessing,
                                            onStateChange = { state = it },
                                            normalize = ::updateState,
                                        )
                                    }
                                },
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun AvatarEditingSurface(
    source: ImageBitmap,
    state: AvatarImageEditorState,
    enabled: Boolean,
    onCropSizeChanged: (Float) -> Unit,
    onTransform: (zoom: Float, panX: Float, panY: Float) -> Unit,
    modifier: Modifier = Modifier,
) {
    val currentOnTransform by rememberUpdatedState(onTransform)
    val frameColor = MaterialTheme.colorScheme.primary
    Surface(
        modifier = modifier,
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceContainerHighest,
    ) {
        BoxWithConstraints(
            modifier = Modifier
                .fillMaxSize()
                .padding(12.dp),
            contentAlignment = Alignment.Center,
        ) {
            val cropEdge = resolveAvatarCropEdge(maxWidth, maxHeight)
            Canvas(
                modifier = Modifier
                    .size(cropEdge)
                    .clipToBounds()
                    .background(MaterialTheme.colorScheme.scrim)
                    .semantics { contentDescription = AVATAR_EDITOR_CROP_TEST_TAG }
                    .onSizeChanged { onCropSizeChanged(it.width.toFloat()) }
                    .pointerInput(enabled) {
                        if (!enabled) return@pointerInput
                        awaitPointerEventScope {
                            while (true) {
                                val event = awaitPointerEvent(PointerEventPass.Initial)
                                if (event.type != PointerEventType.Scroll) continue
                                val scrollY = event.changes.fold(0f) { total, change ->
                                    total + change.scrollDelta.y
                                }
                                val zoom = avatarEditorWheelZoom(scrollY)
                                if (zoom != 1f) {
                                    currentOnTransform(zoom, 0f, 0f)
                                    event.changes.forEach { change -> change.consume() }
                                }
                            }
                        }
                    }
                    .pointerInput(enabled) {
                        if (enabled) {
                            detectTransformGestures { _, pan, zoom, _ ->
                                currentOnTransform(zoom, pan.x, pan.y)
                            }
                        }
                    },
            ) {
                val cropSize = size.minDimension
                drawIntoCanvas { canvas ->
                    drawAvatarImage(
                        canvas = canvas,
                        source = source,
                        state = state.normalized(source.width, source.height, cropSize),
                        cropSize = cropSize,
                        destinationSize = cropSize,
                    )
                }
                val guideColor = Color.White.copy(alpha = 0.48f)
                val guideStroke = 1.dp.toPx()
                val firstThird = cropSize / 3f
                val secondThird = cropSize * 2f / 3f
                drawLine(
                    color = guideColor,
                    start = Offset(firstThird, 0f),
                    end = Offset(firstThird, cropSize),
                    strokeWidth = guideStroke,
                )
                drawLine(
                    color = guideColor,
                    start = Offset(secondThird, 0f),
                    end = Offset(secondThird, cropSize),
                    strokeWidth = guideStroke,
                )
                drawLine(
                    color = guideColor,
                    start = Offset(0f, firstThird),
                    end = Offset(cropSize, firstThird),
                    strokeWidth = guideStroke,
                )
                drawLine(
                    color = guideColor,
                    start = Offset(0f, secondThird),
                    end = Offset(cropSize, secondThird),
                    strokeWidth = guideStroke,
                )
                drawCircle(
                    color = Color.White.copy(alpha = 0.82f),
                    radius = (cropSize / 2f - 12.dp.toPx()).coerceAtLeast(0f),
                    style = Stroke(width = 1.5.dp.toPx()),
                )
                drawRect(
                    color = frameColor,
                    style = Stroke(width = 3.dp.toPx()),
                )
            }
        }
    }
}

@Composable
private fun AvatarEditorHeader(modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(start = 24.dp, top = 20.dp, end = 24.dp, bottom = 8.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(AppStrings.ui_profile_avatar_editor_title, style = MaterialTheme.typography.headlineSmall)
        Text(
            AppStrings.ui_profile_avatar_editor_help,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}

@Composable
private fun AvatarEditorTools(
    state: AvatarImageEditorState,
    isProcessing: Boolean,
    onStateChange: (AvatarImageEditorState) -> Unit,
    normalize: ((AvatarImageEditorState) -> AvatarImageEditorState) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            FilledTonalButton(
                onClick = { normalize(AvatarImageEditorState::rotateCounterClockwise) },
                modifier = Modifier.weight(1f),
                enabled = !isProcessing,
            ) {
                Icon(
                    Icons.AutoMirrored.Filled.RotateLeft,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                )
                Spacer(Modifier.width(8.dp))
                Text(AppStrings.ui_profile_avatar_rotate_left, maxLines = 1)
            }
            FilledTonalButton(
                onClick = { normalize(AvatarImageEditorState::rotateClockwise) },
                modifier = Modifier.weight(1f),
                enabled = !isProcessing,
            ) {
                Icon(
                    Icons.AutoMirrored.Filled.RotateRight,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                )
                Spacer(Modifier.width(8.dp))
                Text(AppStrings.ui_profile_avatar_rotate_right, maxLines = 1)
            }
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            FilterChip(
                selected = state.flipHorizontal,
                onClick = { onStateChange(state.flipHorizontally()) },
                modifier = Modifier
                    .weight(1f)
                    .heightIn(min = 48.dp),
                enabled = !isProcessing,
                label = { Text(AppStrings.ui_profile_avatar_flip_horizontal, maxLines = 1) },
                leadingIcon = {
                    Icon(
                        Icons.Default.SwapHoriz,
                        contentDescription = null,
                        modifier = Modifier.size(FilterChipDefaults.IconSize),
                    )
                },
            )
            FilterChip(
                selected = state.flipVertical,
                onClick = { onStateChange(state.flipVertically()) },
                modifier = Modifier
                    .weight(1f)
                    .heightIn(min = 48.dp),
                enabled = !isProcessing,
                label = { Text(AppStrings.ui_profile_avatar_flip_vertical, maxLines = 1) },
                leadingIcon = {
                    Icon(
                        Icons.Default.SwapVert,
                        contentDescription = null,
                        modifier = Modifier.size(FilterChipDefaults.IconSize),
                    )
                },
            )
        }
    }
}

@Composable
private fun AvatarEditorCompactTools(
    state: AvatarImageEditorState,
    isProcessing: Boolean,
    onStateChange: (AvatarImageEditorState) -> Unit,
    normalize: ((AvatarImageEditorState) -> AvatarImageEditorState) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        FilledTonalIconButton(
            onClick = { normalize(AvatarImageEditorState::rotateCounterClockwise) },
            enabled = !isProcessing,
        ) {
            Icon(
                Icons.AutoMirrored.Filled.RotateLeft,
                contentDescription = AppStrings.ui_profile_avatar_rotate_left,
            )
        }
        FilledTonalIconButton(
            onClick = { normalize(AvatarImageEditorState::rotateClockwise) },
            enabled = !isProcessing,
        ) {
            Icon(
                Icons.AutoMirrored.Filled.RotateRight,
                contentDescription = AppStrings.ui_profile_avatar_rotate_right,
            )
        }
        FilledTonalIconToggleButton(
            checked = state.flipHorizontal,
            onCheckedChange = { onStateChange(state.flipHorizontally()) },
            enabled = !isProcessing,
        ) {
            Icon(
                Icons.Default.SwapHoriz,
                contentDescription = AppStrings.ui_profile_avatar_flip_horizontal,
            )
        }
        FilledTonalIconToggleButton(
            checked = state.flipVertical,
            onCheckedChange = { onStateChange(state.flipVertically()) },
            enabled = !isProcessing,
        ) {
            Icon(
                Icons.Default.SwapVert,
                contentDescription = AppStrings.ui_profile_avatar_flip_vertical,
            )
        }
    }
}

@Composable
private fun AvatarEditorFooter(
    isProcessing: Boolean,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
    modifier: Modifier = Modifier,
    compactTools: (@Composable () -> Unit)? = null,
) {
    Column(modifier = modifier.fillMaxWidth()) {
        if (isProcessing) {
            Surface(
                color = MaterialTheme.colorScheme.secondaryContainer,
                contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 24.dp, vertical = 10.dp)
                        .semantics { contentDescription = AVATAR_EDITOR_PROGRESS_TEST_TAG },
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                    Text(
                        AppStrings.ui_profile_avatar_processing,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            compactTools?.invoke()
            if (compactTools != null) {
                VerticalDivider(modifier = Modifier.heightIn(max = 32.dp))
            }
            Spacer(Modifier.weight(1f))
            OutlinedButton(onClick = onDismiss, enabled = !isProcessing) {
                Text(AppStrings.ui_cancel)
            }
            Button(onClick = onConfirm, enabled = !isProcessing) {
                Icon(
                    Icons.Default.Check,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                )
                Spacer(Modifier.width(8.dp))
                Text(AppStrings.ui_confirm)
            }
        }
    }
}
