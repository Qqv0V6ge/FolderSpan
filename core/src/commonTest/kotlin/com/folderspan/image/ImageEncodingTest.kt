package com.folderspan.image

import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.geometry.Rect
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class ImageEncodingTest {
    @Test
    fun supportedFormatsReportMatchingContentTypes() {
        assertEquals("image/jpeg", ImageFormat.Jpeg.contentType)
        assertEquals("image/png", ImageFormat.Png.contentType)
        assertEquals("image/webp", ImageFormat.WebP.contentType)
    }

    @Test
    fun pngRoundTripPreservesDimensions() {
        val source = patternedImage(width = 17, height = 11)

        val encoded = encodeImage(source, ImageFormat.Png).getOrThrow()
        val decoded = decodeImage(encoded.bytes).getOrThrow()

        assertEquals(ImageFormat.Png, encoded.format)
        assertEquals("image/png", encoded.contentType)
        assertEquals(source.width, decoded.width)
        assertEquals(source.height, decoded.height)
    }

    @Test
    fun jpegQualityOrderingDoesNotGrowAtLowerQuality() {
        val source = patternedImage(width = 96, height = 96)

        val lowQuality = encodeImage(source, ImageFormat.Jpeg, quality = 35).getOrThrow()
        val highQuality = encodeImage(source, ImageFormat.Jpeg, quality = 90).getOrThrow()

        assertTrue(lowQuality.bytes.size <= highQuality.bytes.size)
        assertEquals(source.width, decodeImage(lowQuality.bytes).getOrThrow().width)
        assertEquals(source.height, decodeImage(highQuality.bytes).getOrThrow().height)
    }

    @Test
    fun undecodableAndEmptyInputReturnFailures() {
        val invalid = decodeImage("not-an-image".encodeToByteArray()).exceptionOrNull()
        val empty = decodeImage(byteArrayOf()).exceptionOrNull()

        assertTrue(invalid is ImageDecodingException)
        assertTrue(empty is ImageDecodingException)
    }

    @Test
    fun unavailableFormatReturnsTypedFailureWithoutSubstitution() {
        val failure = encodeImageWith(
            image = patternedImage(width = 4, height = 4),
            format = ImageFormat.WebP,
            quality = 80,
            encoder = { _, _, _ -> null },
        ).exceptionOrNull()

        val unavailable = failure as? ImageEncodingException.FormatUnavailable
        assertNotNull(unavailable)
        assertEquals(ImageFormat.WebP, unavailable.format)
    }
}

private fun patternedImage(width: Int, height: Int): ImageBitmap {
    val image = ImageBitmap(width, height)
    val canvas = Canvas(image)
    val paint = Paint()
    repeat(height) { y ->
        paint.color = Color(
            red = (y % 7) / 6f,
            green = (y % 11) / 10f,
            blue = (y % 13) / 12f,
        )
        canvas.drawRect(
            rect = Rect(0f, y.toFloat(), width.toFloat(), y + 1f),
            paint = paint,
        )
    }
    return image
}
