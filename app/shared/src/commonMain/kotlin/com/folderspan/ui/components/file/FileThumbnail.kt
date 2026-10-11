package com.folderspan.ui.components.file

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import com.folderspan.data.file.FileSimpleInfo
import com.folderspan.ui.thumbnail.FileThumbnailLoader
import com.folderspan.ui.thumbnail.isThumbnailEligible
import com.folderspan.ui.thumbnail.thumbnailKey

@Composable
internal fun FileThumbnail(
    file: FileSimpleInfo,
    loader: FileThumbnailLoader?,
    enabled: Boolean,
    targetSize: Dp,
    modifier: Modifier = Modifier,
    fallback: @Composable () -> Unit,
) {
    val targetSizePx = with(LocalDensity.current) { targetSize.roundToPx() }
    val key = remember(
        file.protocol,
        file.protocolId,
        file.path,
        file.size,
        file.updatedDate,
        targetSizePx,
    ) {
        file.thumbnailKey(targetSizePx)
    }
    var bitmap by remember(loader, key) { mutableStateOf<ImageBitmap?>(null) }
    val shouldLoad = enabled && loader != null && file.isThumbnailEligible()

    LaunchedEffect(loader, key, shouldLoad) {
        bitmap = if (shouldLoad) loader.load(file, targetSizePx) else null
    }

    val image = bitmap
    if (image == null) {
        fallback()
    } else {
        Image(
            bitmap = image,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = modifier
                .fillMaxSize()
                .clip(CircleShape),
        )
    }
}
