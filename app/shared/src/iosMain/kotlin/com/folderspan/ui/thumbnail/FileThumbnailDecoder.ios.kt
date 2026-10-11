@file:Suppress("CAST_NEVER_SUCCEEDS")

package com.folderspan.ui.thumbnail

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.decodeToImageBitmap
import com.folderspan.utils.IosSecurityScopeStore
import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.autoreleasepool
import kotlinx.cinterop.usePinned
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import platform.CoreFoundation.CFDataRef
import platform.CoreFoundation.CFDictionaryRef
import platform.CoreFoundation.CFURLRef
import platform.CoreFoundation.kCFBooleanTrue
import platform.Foundation.NSData
import platform.Foundation.NSMutableDictionary
import platform.Foundation.NSCopyingProtocol
import platform.Foundation.NSNumber
import platform.Foundation.NSURL
import platform.Foundation.create
import platform.ImageIO.CGImageSourceCreateThumbnailAtIndex
import platform.ImageIO.CGImageSourceCreateWithData
import platform.ImageIO.CGImageSourceCreateWithURL
import platform.ImageIO.kCGImageSourceCreateThumbnailFromImageAlways
import platform.ImageIO.kCGImageSourceCreateThumbnailWithTransform
import platform.ImageIO.kCGImageSourceThumbnailMaxPixelSize
import platform.UIKit.UIImage
import platform.UIKit.UIImagePNGRepresentation
import platform.posix.memcpy
import strings.AppStrings

@OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)
internal actual suspend fun decodeFileThumbnail(
    source: FileThumbnailDecodeSource,
    targetSizePx: Int,
): ImageBitmap = withContext(Dispatchers.Default) {
    autoreleasepool {
        when {
            source.localPath != null -> IosSecurityScopeStore.withSecurityScopeIfNeeded(source.localPath) {
                val imageSource = CGImageSourceCreateWithURL(
                    NSURL.fileURLWithPath(source.localPath) as CFURLRef,
                    null,
                ) ?: throw IllegalArgumentException(AppStrings.ui_cannot_open_the_image)
                decodeThumbnailSource(imageSource, targetSizePx)
            }
            source.bytes != null -> {
                val imageSource = CGImageSourceCreateWithData(source.bytes.toNSData() as CFDataRef, null)
                    ?: throw IllegalArgumentException(AppStrings.ui_cannot_open_the_image)
                decodeThumbnailSource(imageSource, targetSizePx)
            }
            else -> throw IllegalArgumentException(AppStrings.ui_no_image_source_available)
        }
    }
}

@OptIn(ExperimentalForeignApi::class)
private fun decodeThumbnailSource(
    imageSource: platform.ImageIO.CGImageSourceRef,
    targetSizePx: Int,
): ImageBitmap {
    val options = NSMutableDictionary().apply {
        setObject(kCFBooleanTrue, forKey = kCGImageSourceCreateThumbnailFromImageAlways as NSCopyingProtocol)
        setObject(kCFBooleanTrue, forKey = kCGImageSourceCreateThumbnailWithTransform as NSCopyingProtocol)
        setObject(
            NSNumber(int = targetSizePx),
            forKey = kCGImageSourceThumbnailMaxPixelSize as NSCopyingProtocol,
        )
    }
    val thumbnail = CGImageSourceCreateThumbnailAtIndex(imageSource, 0uL, options as CFDictionaryRef)
        ?: throw IllegalArgumentException(AppStrings.ui_no_image_decoding_is_possible)
    val png = UIImagePNGRepresentation(UIImage.imageWithCGImage(thumbnail))
        ?: throw IllegalArgumentException(AppStrings.ui_unable_to_convert_a_screenshot)
    return png.toByteArray().decodeToImageBitmap()
}

@OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)
private fun ByteArray.toNSData(): NSData {
    if (isEmpty()) return NSData.create(bytes = null, length = 0uL)
    return usePinned { pinned ->
        NSData.create(bytes = pinned.addressOf(0), length = size.toULong())
    }
}

@OptIn(ExperimentalForeignApi::class)
private fun NSData.toByteArray(): ByteArray {
    val byteCount = length.toInt()
    val source = bytes ?: return ByteArray(0)
    return ByteArray(byteCount).also { destination ->
        destination.usePinned { pinned -> memcpy(pinned.addressOf(0), source, length) }
    }
}
