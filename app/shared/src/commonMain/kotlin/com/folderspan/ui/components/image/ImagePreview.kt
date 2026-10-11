package com.folderspan.ui.components.image

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.seiko.imageloader.model.ImageAction
import com.seiko.imageloader.rememberImageSuccessPainter
import com.seiko.imageloader.ui.AutoSizeBox
import strings.AppStrings

internal const val IMAGE_PREVIEW_DIALOG_TEST_TAG = "image-preview-dialog"
internal const val IMAGE_PREVIEW_MIN_SCALE = 1f
internal const val IMAGE_PREVIEW_MAX_SCALE = 5f
internal const val IMAGE_PREVIEW_DOUBLE_TAP_SCALE = 2.5f

data class ImagePreviewRequest(
    val url: String,
    val title: String,
)

class ImagePreviewController {
    var request: ImagePreviewRequest? by mutableStateOf(null)
        private set

    fun show(url: String, title: String) {
        request = ImagePreviewRequest(url = url, title = title)
    }

    fun dismiss() {
        request = null
    }
}

val LocalImagePreviewController = staticCompositionLocalOf<ImagePreviewController?> { null }

@Composable
fun ImagePreviewHost(content: @Composable () -> Unit) {
    val controller = remember { ImagePreviewController() }
    CompositionLocalProvider(LocalImagePreviewController provides controller) {
        content()
        val request = controller.request
        if (request != null) {
            ImagePreviewDialog(
                url = request.url,
                title = request.title,
                onDismiss = controller::dismiss,
            )
        }
    }
}

@Composable
internal fun ProvideImagePreviewController(content: @Composable () -> Unit) {
    if (LocalImagePreviewController.current != null) {
        content()
    } else {
        ImagePreviewHost(content)
    }
}

@Composable
internal fun ImagePreviewDialog(
    url: String,
    title: String,
    onDismiss: () -> Unit,
) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            dismissOnBackPress = true,
            dismissOnClickOutside = true,
        ),
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.94f))
                .windowInsetsPadding(WindowInsets.safeDrawing)
                .testTag(IMAGE_PREVIEW_DIALOG_TEST_TAG),
        ) {
            ZoomablePreviewImage(
                url = url,
                contentDescription = title.ifBlank { null },
                modifier = Modifier.fillMaxSize(),
            )
            Row(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (title.isNotBlank()) {
                    Text(
                        text = title,
                        modifier = Modifier
                            .weight(1f)
                            .padding(start = 8.dp),
                        color = Color.White,
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                } else {
                    Box(Modifier.weight(1f))
                }
                IconButton(onClick = onDismiss) {
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = AppStrings.ui_close,
                        tint = Color.White,
                    )
                }
            }
        }
    }
}

@Composable
private fun ZoomablePreviewImage(
    url: String,
    contentDescription: String?,
    modifier: Modifier = Modifier,
) {
    var scale by remember { mutableFloatStateOf(IMAGE_PREVIEW_MIN_SCALE) }
    var offset by remember { mutableStateOf(Offset.Zero) }
    val transformableState = rememberTransformableState { _, zoomChange, panChange, _ ->
        scale = coerceImagePreviewScale(scale * zoomChange)
        offset = imagePreviewOffsetAfterTransform(scale, offset, panChange)
    }
    Box(
        modifier = modifier
            .clipToBounds()
            .pointerInput(Unit) {
                detectTapGestures(
                    onDoubleTap = {
                        scale = imagePreviewScaleAfterDoubleTap(scale)
                        if (scale == IMAGE_PREVIEW_MIN_SCALE) offset = Offset.Zero
                    },
                )
            }
            .pointerInput(Unit) {
                awaitPointerEventScope {
                    while (true) {
                        val event = awaitPointerEvent(PointerEventPass.Initial)
                        if (event.type != PointerEventType.Scroll) continue
                        val scrollY = event.changes.fold(0f) { total, change ->
                            total + change.scrollDelta.y
                        }
                        val nextScale = coerceImagePreviewScale(scale * imagePreviewWheelZoom(scrollY))
                        if (nextScale != scale) {
                            scale = nextScale
                            offset = imagePreviewOffsetAfterTransform(scale, offset, Offset.Zero)
                            event.changes.forEach { change -> change.consume() }
                        }
                    }
                }
            }
            .transformable(transformableState),
        contentAlignment = Alignment.Center,
    ) {
        AutoSizeBox(url = url, modifier = Modifier.fillMaxSize()) { action ->
            when (action) {
                is ImageAction.Success -> Image(
                    painter = rememberImageSuccessPainter(action),
                    contentDescription = contentDescription,
                    modifier = Modifier
                        .fillMaxSize()
                        .graphicsLayer {
                            scaleX = scale
                            scaleY = scale
                            translationX = offset.x
                            translationY = offset.y
                        },
                    contentScale = ContentScale.Fit,
                )
                is ImageAction.Loading -> CircularProgressIndicator(
                    modifier = Modifier.size(32.dp),
                    color = Color.White,
                    strokeWidth = 3.dp,
                )
                is ImageAction.Failure -> Text(
                    text = AppStrings.ui_loading_failed,
                    color = Color.White,
                    style = MaterialTheme.typography.bodyLarge,
                )
            }
        }
    }
}

internal fun coerceImagePreviewScale(scale: Float): Float =
    scale.coerceIn(IMAGE_PREVIEW_MIN_SCALE, IMAGE_PREVIEW_MAX_SCALE)

internal fun imagePreviewOffsetAfterTransform(
    scale: Float,
    offset: Offset,
    pan: Offset,
): Offset = if (scale <= IMAGE_PREVIEW_MIN_SCALE) Offset.Zero else offset + pan

internal fun imagePreviewWheelZoom(scrollY: Float): Float {
    if (!scrollY.isFinite() || scrollY == 0f) return 1f
    return (1f - scrollY * 0.12f).coerceIn(0.5f, 2f)
}

internal fun imagePreviewScaleAfterDoubleTap(scale: Float): Float =
    if (scale > IMAGE_PREVIEW_MIN_SCALE) IMAGE_PREVIEW_MIN_SCALE else IMAGE_PREVIEW_DOUBLE_TAP_SCALE

internal fun imagePreviewCaption(title: String, alt: String): String = title.ifBlank { alt }
