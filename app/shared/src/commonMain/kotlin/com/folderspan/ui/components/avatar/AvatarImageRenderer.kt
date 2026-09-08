package com.folderspan.ui.components.avatar

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Paint

internal fun renderAvatarImage(
    source: ImageBitmap,
    state: AvatarImageEditorState,
    cropSize: Float,
    outputSize: Int,
): ImageBitmap {
    require(outputSize > 0)
    val output = ImageBitmap(outputSize, outputSize)
    val canvas = Canvas(output)
    canvas.drawRect(
        left = 0f,
        top = 0f,
        right = outputSize.toFloat(),
        bottom = outputSize.toFloat(),
        paint = Paint().apply { color = Color.White },
    )
    drawAvatarImage(
        canvas = canvas,
        source = source,
        state = state.normalized(source.width, source.height, cropSize),
        cropSize = cropSize,
        destinationSize = outputSize.toFloat(),
    )
    return output
}

internal fun drawAvatarImage(
    canvas: Canvas,
    source: ImageBitmap,
    state: AvatarImageEditorState,
    cropSize: Float,
    destinationSize: Float,
) {
    val destinationScale = destinationSize / cropSize
    canvas.save()
    canvas.clipRect(0f, 0f, destinationSize, destinationSize)
    canvas.translate(
        dx = destinationSize / 2f + state.pan.x * destinationScale,
        dy = destinationSize / 2f + state.pan.y * destinationScale,
    )
    canvas.scale(
        sx = state.scale * destinationScale * if (state.flipHorizontal) -1f else 1f,
        sy = state.scale * destinationScale * if (state.flipVertical) -1f else 1f,
    )
    canvas.rotate(state.rotationQuarterTurns * 90f)
    canvas.drawImage(
        image = source,
        topLeftOffset = Offset(-source.width / 2f, -source.height / 2f),
        paint = Paint().apply { filterQuality = FilterQuality.None },
    )
    canvas.restore()
}
