package com.folderspan.ui.components.avatar

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.dp
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AvatarImageEditorStateTest {
    @Test
    fun quarterTurnsWrapInBothDirections() {
        var state = AvatarImageEditorState()
        repeat(5) { state = state.rotateClockwise() }
        assertEquals(1, state.rotationQuarterTurns)

        repeat(2) { state = state.rotateCounterClockwise() }
        assertEquals(3, state.rotationQuarterTurns)
    }

    @Test
    fun flipsAreIndependentInvolutions() {
        val original = AvatarImageEditorState()
        val horizontal = original.flipHorizontally()
        assertTrue(horizontal.flipHorizontal)
        assertFalse(horizontal.flipVertical)
        assertEquals(original, horizontal.flipHorizontally())

        val vertical = horizontal.flipVertically()
        assertTrue(vertical.flipHorizontal)
        assertTrue(vertical.flipVertical)
        assertEquals(horizontal, vertical.flipVertically())
    }

    @Test
    fun normalizationKeepsCropCoveredAndClampsPan() {
        val normalized = AvatarImageEditorState(
            scale = 0.1f,
            pan = Offset(9_000f, -9_000f),
        ).normalized(
            sourceWidth = 400,
            sourceHeight = 200,
            cropSize = 300f,
        )

        assertEquals(1.5f, normalized.scale)
        assertEquals(Offset(150f, 0f), normalized.pan)
    }

    @Test
    fun quarterTurnUsesRotatedDimensionsWhenClamping() {
        val normalized = AvatarImageEditorState(
            rotationQuarterTurns = 1,
            scale = 0.1f,
            pan = Offset(1_000f, 1_000f),
        ).normalized(
            sourceWidth = 400,
            sourceHeight = 200,
            cropSize = 300f,
        )

        assertEquals(1.5f, normalized.scale)
        assertEquals(Offset(0f, 150f), normalized.pan)
    }

    @Test
    fun layoutSwitchesAtExpandedBreakpoint() {
        assertEquals(AvatarEditorLayout.Compact, resolveAvatarEditorLayout(839.dp))
        assertEquals(AvatarEditorLayout.Expanded, resolveAvatarEditorLayout(840.dp))
    }

    @Test
    fun cropEdgeUsesTheTighterConstraintInALandscapeCompactWindow() {
        assertEquals(260.dp, resolveAvatarCropEdge(648.dp, 260.dp))
    }

    @Test
    fun wheelScrollConvertsToDirectionalBoundedZoom() {
        assertTrue(avatarEditorWheelZoom(scrollY = -1f) > 1f)
        assertTrue(avatarEditorWheelZoom(scrollY = 1f) < 1f)
        assertEquals(2f, avatarEditorWheelZoom(scrollY = -100f))
        assertEquals(0.5f, avatarEditorWheelZoom(scrollY = 100f))
        assertEquals(1f, avatarEditorWheelZoom(scrollY = 0f))
    }
}
