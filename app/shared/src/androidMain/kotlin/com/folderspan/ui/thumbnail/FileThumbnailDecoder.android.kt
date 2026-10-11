package com.folderspan.ui.thumbnail

import android.graphics.BitmapFactory
import android.net.Uri
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import com.folderspan.androidContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.InputStream
import strings.AppStrings

internal actual suspend fun decodeFileThumbnail(
    source: FileThumbnailDecodeSource,
    targetSizePx: Int,
): ImageBitmap = withContext(Dispatchers.IO) {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    source.decode(bounds)
    require(bounds.outWidth > 0 && bounds.outHeight > 0) { AppStrings.ui_the_image_size_cannot_be_read }

    val options = BitmapFactory.Options().apply {
        inSampleSize = thumbnailPowerOfTwoSampleSize(bounds.outWidth, bounds.outHeight, targetSizePx)
        inPreferredConfig = android.graphics.Bitmap.Config.ARGB_8888
    }
    val bitmap = source.decode(options) ?: throw IllegalArgumentException(AppStrings.ui_no_image_decoding_is_possible)
    bitmap.asImageBitmap()
}

private fun FileThumbnailDecodeSource.decode(options: BitmapFactory.Options) = when {
    bytes != null -> BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
    localPath != null && localPath.startsWith("content://") ->
        openContentInputStream(localPath).use { input -> BitmapFactory.decodeStream(input, null, options) }
    localPath != null -> BitmapFactory.decodeFile(localPath, options)
    else -> null
}

private fun openContentInputStream(path: String): InputStream =
    androidContext().contentResolver.openInputStream(Uri.parse(path))
        ?: throw IllegalArgumentException(AppStrings.ui_cannot_open_the_image)
