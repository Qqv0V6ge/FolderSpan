package com.folderspan.ui.components.avatar

import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.toPixelMap
import com.folderspan.image.decodeImage
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class AvatarImageRendererTest {
    @Test
    fun outputIsSquareAndUsesTheSameCenteredCrop() {
        val source = twoByOneImage()
        val output = renderAvatarImage(
            source = source,
            state = AvatarImageEditorState(scale = 2f),
            cropSize = 2f,
            outputSize = 4,
        )

        assertEquals(4, output.width)
        assertEquals(4, output.height)
        assertEquals(Color.Red, output.toPixelMap()[1, 1])
        assertEquals(Color.Blue, output.toPixelMap()[3, 1])
    }

    @Test
    fun rotationAndMirroringAreAppliedToTheRenderedPixels() {
        val source = twoByOneImage()
        val output = renderAvatarImage(
            source = source,
            state = AvatarImageEditorState(
                rotationQuarterTurns = 2,
                flipHorizontal = true,
                scale = 2f,
            ),
            cropSize = 2f,
            outputSize = 4,
        )

        assertEquals(Color.Red, output.toPixelMap()[1, 1])
        assertEquals(Color.Blue, output.toPixelMap()[3, 1])
    }

    @Test
    fun preparedPictureIsSquareWithinLimitAndHasMatchingContentType() {
        val prepared = prepareAvatarPicture(
            source = twoByOneImage(),
            state = AvatarImageEditorState(scale = 2f),
            cropSize = 2f,
            maxOutputBytes = 2L * 1024L * 1024L,
        ).getOrThrow()

        assertEquals("image/jpeg", prepared.contentType)
        assertTrue(prepared.bytes.size <= 2 * 1024 * 1024)
        val decoded = decodeImage(prepared.bytes).getOrThrow()
        assertEquals(AVATAR_OUTPUT_EDGE_PIXELS, decoded.width)
        assertEquals(AVATAR_OUTPUT_EDGE_PIXELS, decoded.height)
    }

    @Test
    fun preparedPictureReportsTypedFailureWhenOutputLimitIsExceeded() {
        val failure = prepareAvatarPicture(
            source = twoByOneImage(),
            state = AvatarImageEditorState(scale = 2f),
            cropSize = 2f,
            maxOutputBytes = 1,
        ).exceptionOrNull()

        assertIs<AvatarOutputTooLargeException>(failure)
    }

    @Test
    fun cameraSizedPictureProducesBoundedSquareOutput() {
        val source = ImageBitmap(4032, 3024)
        Canvas(source).drawRect(
            0f,
            0f,
            source.width.toFloat(),
            source.height.toFloat(),
            Paint().apply { color = Color.Cyan },
        )

        val prepared = prepareAvatarPicture(
            source = source,
            state = AvatarImageEditorState(),
            cropSize = 720f,
            maxOutputBytes = 2L * 1024L * 1024L,
        ).getOrThrow()
        val decoded = decodeImage(prepared.bytes).getOrThrow()

        assertEquals(AVATAR_OUTPUT_EDGE_PIXELS, decoded.width)
        assertEquals(AVATAR_OUTPUT_EDGE_PIXELS, decoded.height)
        assertTrue(prepared.bytes.size <= 2 * 1024 * 1024)
    }

    private fun twoByOneImage(): ImageBitmap {
        val image = ImageBitmap(2, 1)
        val canvas = Canvas(image)
        canvas.drawRect(0f, 0f, 1f, 1f, Paint().apply { color = Color.Red })
        canvas.drawRect(1f, 0f, 2f, 1f, Paint().apply { color = Color.Blue })
        return image
    }
}
