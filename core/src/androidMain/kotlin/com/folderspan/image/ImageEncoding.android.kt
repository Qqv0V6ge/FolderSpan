package com.folderspan.image

import android.graphics.Bitmap
import android.os.Build
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import java.io.ByteArrayOutputStream

internal actual fun encodeImageBytes(
    image: ImageBitmap,
    format: ImageFormat,
    quality: Int,
): ByteArray? {
    val compressFormat = when (format) {
        ImageFormat.Jpeg -> Bitmap.CompressFormat.JPEG
        ImageFormat.Png -> Bitmap.CompressFormat.PNG
        ImageFormat.WebP -> if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Bitmap.CompressFormat.WEBP_LOSSY
        } else {
            @Suppress("DEPRECATION")
            Bitmap.CompressFormat.WEBP
        }
    }
    return ByteArrayOutputStream().use { output ->
        if (image.asAndroidBitmap().compress(compressFormat, quality, output)) {
            output.toByteArray()
        } else {
            null
        }
    }
}
