package com.folderspan.ui.components.image

import androidx.compose.ui.geometry.Offset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ImagePreviewTest {
    @Test
    fun captionPrefersTitleAndFallsBackToAlt() {
        assertEquals("Release notes", imagePreviewCaption("Release notes", "logo"))
        assertEquals("logo", imagePreviewCaption("", "logo"))
        assertEquals("", imagePreviewCaption("", ""))
    }

    @Test
    fun scaleStaysWithinZoomBounds() {
        assertEquals(IMAGE_PREVIEW_MIN_SCALE, coerceImagePreviewScale(0.2f))
        assertEquals(IMAGE_PREVIEW_MAX_SCALE, coerceImagePreviewScale(12f))
        assertEquals(2.5f, coerceImagePreviewScale(2.5f))
    }

    @Test
    fun panResetsWhenZoomedOutAndDoubleTapTogglesZoom() {
        assertEquals(Offset.Zero, imagePreviewOffsetAfterTransform(1f, Offset(8f, 4f), Offset(1f, 1f)))
        assertEquals(Offset(11f, 6f), imagePreviewOffsetAfterTransform(2f, Offset(8f, 4f), Offset(3f, 2f)))
        assertEquals(IMAGE_PREVIEW_DOUBLE_TAP_SCALE, imagePreviewScaleAfterDoubleTap(1f))
        assertEquals(IMAGE_PREVIEW_MIN_SCALE, imagePreviewScaleAfterDoubleTap(2.5f))
    }

    @Test
    fun wheelScrollZoomsInAndOut() {
        assertEquals(1f, imagePreviewWheelZoom(0f))
        assertTrue(imagePreviewWheelZoom(-1f) > 1f)
        assertTrue(imagePreviewWheelZoom(1f) < 1f)
    }
}
