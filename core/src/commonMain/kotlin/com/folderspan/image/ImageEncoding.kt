package com.folderspan.image

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.decodeToImageBitmap

enum class ImageFormat(val contentType: String) {
    Jpeg("image/jpeg"),
    Png("image/png"),
    WebP("image/webp"),
}

data class EncodedImage(
    val bytes: ByteArray,
    val format: ImageFormat,
) {
    val contentType: String
        get() = format.contentType
}

class ImageDecodingException(cause: Throwable? = null) :
    IllegalArgumentException("The supplied bytes are not a decodable image.", cause)

sealed class ImageEncodingException(message: String) : IllegalStateException(message) {
    class FormatUnavailable(val format: ImageFormat) :
        ImageEncodingException("The ${format.name} image format is unavailable on this platform.")
}

fun decodeImage(bytes: ByteArray): Result<ImageBitmap> = runCatching {
    if (bytes.isEmpty()) throw ImageDecodingException()
    try {
        bytes.decodeToImageBitmap()
    } catch (error: Throwable) {
        throw ImageDecodingException(error)
    }
}

fun encodeImage(
    image: ImageBitmap,
    format: ImageFormat,
    quality: Int = DEFAULT_IMAGE_QUALITY,
): Result<EncodedImage> = encodeImageWith(
    image = image,
    format = format,
    quality = quality,
    encoder = ::encodeImageBytes,
)

internal fun encodeImageWith(
    image: ImageBitmap,
    format: ImageFormat,
    quality: Int,
    encoder: (ImageBitmap, ImageFormat, Int) -> ByteArray?,
): Result<EncodedImage> = runCatching {
    require(quality in MIN_IMAGE_QUALITY..MAX_IMAGE_QUALITY) {
        "Image quality must be between $MIN_IMAGE_QUALITY and $MAX_IMAGE_QUALITY."
    }
    val bytes = encoder(image, format, quality)
        ?: throw ImageEncodingException.FormatUnavailable(format)
    EncodedImage(bytes = bytes, format = format)
}

internal expect fun encodeImageBytes(
    image: ImageBitmap,
    format: ImageFormat,
    quality: Int,
): ByteArray?

const val DEFAULT_IMAGE_QUALITY: Int = 85
private const val MIN_IMAGE_QUALITY: Int = 0
private const val MAX_IMAGE_QUALITY: Int = 100
