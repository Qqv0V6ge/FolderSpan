package com.folderspan.ui.thumbnail

import kotlinx.coroutines.runBlocking
import java.awt.Color
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertTrue

class FileThumbnailDecoderJvmTest {
    @Test
    fun samplesLargeImageToRequestedBound() = runBlocking {
        val source = BufferedImage(2400, 1600, BufferedImage.TYPE_INT_RGB).apply {
            createGraphics().use { graphics ->
                graphics.color = Color(20, 120, 220)
                graphics.fillRect(0, 0, width, height)
            }
        }
        val encoded = ByteArrayOutputStream().use { output ->
            assertTrue(ImageIO.write(source, "jpg", output))
            output.toByteArray()
        }
        source.flush()

        val thumbnail = decodeFileThumbnail(
            source = FileThumbnailDecodeSource(bytes = encoded),
            targetSizePx = 128,
        )

        assertTrue(thumbnail.width <= 128)
        assertTrue(thumbnail.height <= 128)
    }
}

private inline fun <T : java.awt.Graphics2D, R> T.use(block: (T) -> R): R = try {
    block(this)
} finally {
    dispose()
}
