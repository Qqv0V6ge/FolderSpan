package com.folderspan.ui.thumbnail

import androidx.compose.ui.graphics.ImageBitmap
import strings.AppStrings

internal data class FileThumbnailDecodeSource(
    val localPath: String? = null,
    val bytes: ByteArray? = null,
) {
    init {
        require((localPath != null) xor (bytes != null)) {
            AppStrings.ui_image_decoding_source_must_and_only_contain_paths_or_bytes
        }
    }
}

internal expect suspend fun decodeFileThumbnail(
    source: FileThumbnailDecodeSource,
    targetSizePx: Int,
): ImageBitmap
