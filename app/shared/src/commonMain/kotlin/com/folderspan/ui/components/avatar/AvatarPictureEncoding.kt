package com.folderspan.ui.components.avatar

import androidx.compose.ui.graphics.ImageBitmap
import com.folderspan.image.ImageFormat
import com.folderspan.image.encodeImage
import com.folderspan.pro.presentation.screen.profile.DeliveredAvatarPicture

internal class AvatarOutputTooLargeException(
    val actualBytes: Long,
    val maximumBytes: Long,
) : IllegalArgumentException("The encoded avatar exceeds its output limit.")

internal fun prepareAvatarPicture(
    source: ImageBitmap,
    state: AvatarImageEditorState,
    cropSize: Float,
    maxOutputBytes: Long,
): Result<DeliveredAvatarPicture> = runCatching {
    require(maxOutputBytes > 0)
    val rendered = renderAvatarImage(
        source = source,
        state = state,
        cropSize = cropSize,
        outputSize = AVATAR_OUTPUT_EDGE_PIXELS,
    )
    val encoded = encodeImage(
        image = rendered,
        format = ImageFormat.Jpeg,
        quality = AVATAR_JPEG_QUALITY,
    ).getOrThrow()
    if (encoded.bytes.size.toLong() > maxOutputBytes) {
        throw AvatarOutputTooLargeException(
            actualBytes = encoded.bytes.size.toLong(),
            maximumBytes = maxOutputBytes,
        )
    }
    DeliveredAvatarPicture(
        bytes = encoded.bytes,
        contentType = encoded.contentType,
    )
}
