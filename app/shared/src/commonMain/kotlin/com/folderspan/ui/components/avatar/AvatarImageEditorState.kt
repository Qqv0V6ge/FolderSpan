package com.folderspan.ui.components.avatar

import androidx.compose.runtime.Immutable
import androidx.compose.ui.geometry.Offset
import kotlin.math.max

@Immutable
internal data class AvatarImageEditorState(
    val rotationQuarterTurns: Int = 0,
    val flipHorizontal: Boolean = false,
    val flipVertical: Boolean = false,
    val scale: Float = 1f,
    val pan: Offset = Offset.Zero,
) {
    fun rotateClockwise(): AvatarImageEditorState = copy(
        rotationQuarterTurns = (rotationQuarterTurns + 1).floorMod(4),
    )

    fun rotateCounterClockwise(): AvatarImageEditorState = copy(
        rotationQuarterTurns = (rotationQuarterTurns - 1).floorMod(4),
    )

    fun flipHorizontally(): AvatarImageEditorState = copy(flipHorizontal = !flipHorizontal)

    fun flipVertically(): AvatarImageEditorState = copy(flipVertical = !flipVertical)

    fun transformed(
        zoomChange: Float,
        panChange: Offset,
        sourceWidth: Int,
        sourceHeight: Int,
        cropSize: Float,
    ): AvatarImageEditorState = copy(
        scale = (scale * zoomChange).coerceAtMost(MAX_AVATAR_EDITOR_SCALE),
        pan = pan + panChange,
    ).normalized(sourceWidth, sourceHeight, cropSize)

    fun normalized(
        sourceWidth: Int,
        sourceHeight: Int,
        cropSize: Float,
    ): AvatarImageEditorState {
        require(sourceWidth > 0 && sourceHeight > 0)
        require(cropSize > 0f)
        val hasQuarterTurn = rotationQuarterTurns.floorMod(2) == 1
        val rotatedWidth = if (hasQuarterTurn) sourceHeight.toFloat() else sourceWidth.toFloat()
        val rotatedHeight = if (hasQuarterTurn) sourceWidth.toFloat() else sourceHeight.toFloat()
        val minimumScale = max(cropSize / rotatedWidth, cropSize / rotatedHeight)
        val normalizedScale = scale.coerceIn(minimumScale, max(minimumScale, MAX_AVATAR_EDITOR_SCALE))
        val maxPanX = ((rotatedWidth * normalizedScale - cropSize) / 2f).coerceAtLeast(0f)
        val maxPanY = ((rotatedHeight * normalizedScale - cropSize) / 2f).coerceAtLeast(0f)
        return copy(
            rotationQuarterTurns = rotationQuarterTurns.floorMod(4),
            scale = normalizedScale,
            pan = Offset(
                x = if (maxPanX == 0f) 0f else pan.x.coerceIn(-maxPanX, maxPanX),
                y = if (maxPanY == 0f) 0f else pan.y.coerceIn(-maxPanY, maxPanY),
            ),
        )
    }
}

private fun Int.floorMod(divisor: Int): Int = ((this % divisor) + divisor) % divisor

private const val MAX_AVATAR_EDITOR_SCALE = 8f
