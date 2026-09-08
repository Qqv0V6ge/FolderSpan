package com.folderspan.ui.thumbnail

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jetbrains.skia.Bitmap
import org.jetbrains.skia.Canvas
import org.jetbrains.skia.Image
import org.jetbrains.skia.Rect
import org.jetbrains.skia.SamplingMode
import kotlin.math.max
import kotlin.math.roundToInt
import strings.AppStrings

internal actual suspend fun decodeFileThumbnail(
    source: FileThumbnailDecodeSource,
    targetSizePx: Int,
): ImageBitmap = withContext(Dispatchers.Default) {
    val encoded = source.bytes ?: throw IllegalArgumentException(AppStrings.ui_web_thumbnail_requires_byte_source)
    val image = Image.makeFromEncoded(encoded)
    try {
        val scale = minOf(1.0, targetSizePx.toDouble() / max(image.width, image.height).toDouble())
        val width = (image.width * scale).roundToInt().coerceAtLeast(1)
        val height = (image.height * scale).roundToInt().coerceAtLeast(1)
        val bitmap = Bitmap()
        check(bitmap.allocN32Pixels(width, height)) { AppStrings.ui_unable_to_allocate_simplified_image_pixels }
        val canvas = Canvas(bitmap)
        try {
            canvas.drawImageRect(
                image = image,
                src = Rect.makeWH(image.width.toFloat(), image.height.toFloat()),
                dst = Rect.makeWH(width.toFloat(), height.toFloat()),
                samplingMode = SamplingMode.LINEAR,
                paint = null,
                strict = true,
            )
        } finally {
            canvas.close()
        }
        Image.makeFromBitmap(bitmap).toComposeImageBitmap().also { bitmap.close() }
    } finally {
        image.close()
    }
}
