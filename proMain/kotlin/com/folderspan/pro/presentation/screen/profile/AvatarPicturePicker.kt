package com.folderspan.pro.presentation.screen.profile

import androidx.compose.foundation.layout.RowScope
import androidx.compose.material3.Button
import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier

data class DeliveredAvatarPicture(
    val bytes: ByteArray,
    val contentType: String,
)

sealed interface AvatarPictureOutcome {
    data class Delivered(val picture: DeliveredAvatarPicture) : AvatarPictureOutcome

    data object Cancelled : AvatarPictureOutcome

    data class Failed(val message: String) : AvatarPictureOutcome
}

fun interface AvatarPicturePicker {
    fun request(
        maxOutputBytes: Long,
        onOutcome: (AvatarPictureOutcome) -> Unit,
    )
}

val LocalAvatarPicturePicker = staticCompositionLocalOf<AvatarPicturePicker?> { null }

@Composable
internal fun AvatarPictureAction(
    maxOutputBytes: Long,
    onOutcome: (AvatarPictureOutcome) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    content: @Composable RowScope.() -> Unit,
) {
    val picker = LocalAvatarPicturePicker.current
    Button(
        onClick = {
            picker?.request(
                maxOutputBytes = maxOutputBytes,
                onOutcome = onOutcome,
            )
        },
        modifier = modifier,
        enabled = enabled && picker != null,
        content = content,
    )
}
