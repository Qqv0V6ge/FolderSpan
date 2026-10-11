package com.folderspan.ui.thumbnail

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.File
import javax.imageio.ImageIO
import javax.imageio.stream.ImageInputStream
import kotlin.math.max
import kotlin.math.roundToInt
import strings.AppStrings

internal actual suspend fun decodeFileThumbnail(
    source: FileThumbnailDecodeSource,
    targetSizePx: Int,
): ImageBitmap = withContext(Dispatchers.IO) {
    val input = source.openImageInputStream()
        ?: throw IllegalArgumentException(AppStrings.ui_cannot_open_the_image)
    input.use {
        val reader = ImageIO.getImageReaders(input).asSequence().firstOrNull()
            ?: throw IllegalArgumentException(AppStrings.ui_unsupported_image_format)
        try {
            reader.setInput(input, true, true)
            val width = reader.getWidth(0)
            val height = reader.getHeight(0)
            val sample = max(1, max(width, height) / targetSizePx)
            val parameters = reader.defaultReadParam.apply {
                setSourceSubsampling(sample, sample, 0, 0)
            }
            val sampled = reader.read(0, parameters)
            sampled.scaleDownTo(targetSizePx).toComposeImageBitmap()
        } finally {
            reader.dispose()
        }
    }
}

private fun FileThumbnailDecodeSource.openImageInputStream(): ImageInputStream? = when {
    bytes != null -> ImageIO.createImageInputStream(ByteArrayInputStream(bytes))
    localPath != null -> ImageIO.createImageInputStream(File(localPath))
    else -> null
}

private fun BufferedImage.scaleDownTo(targetSizePx: Int): BufferedImage {
    val maxDimension = max(width, height)
    if (maxDimension <= targetSizePx) return this
    val scale = targetSizePx.toDouble() / maxDimension.toDouble()
    val targetWidth = (width * scale).roundToInt().coerceAtLeast(1)
    val targetHeight = (height * scale).roundToInt().coerceAtLeast(1)
    val output = BufferedImage(targetWidth, targetHeight, BufferedImage.TYPE_INT_ARGB)
    output.createGraphics().use { graphics ->
        graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR)
        graphics.drawImage(this, 0, 0, targetWidth, targetHeight, null)
    }
    flush()
    return output
}

private inline fun <T : java.awt.Graphics2D, R> T.use(block: (T) -> R): R = try {
    block(this)
} finally {
    dispose()
}
