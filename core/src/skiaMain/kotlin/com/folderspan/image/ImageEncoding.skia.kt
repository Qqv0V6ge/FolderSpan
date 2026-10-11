package com.folderspan.image

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asSkiaBitmap
import org.jetbrains.skia.EncodedImageFormat
import org.jetbrains.skia.Image

internal actual fun encodeImageBytes(
    image: ImageBitmap,
    format: ImageFormat,
    quality: Int,
): ByteArray? {
    val encodedFormat = when (format) {
        ImageFormat.Jpeg -> EncodedImageFormat.JPEG
        ImageFormat.Png -> EncodedImageFormat.PNG
        ImageFormat.WebP -> EncodedImageFormat.WEBP
    }
    val skiaImage = Image.makeFromBitmap(image.asSkiaBitmap())
    return try {
        val data = skiaImage.encodeToData(encodedFormat, quality) ?: return null
        try {
            data.bytes
        } finally {
            data.close()
        }
    } finally {
        skiaImage.close()
    }
}
