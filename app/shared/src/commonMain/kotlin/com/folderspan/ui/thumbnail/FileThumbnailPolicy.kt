package com.folderspan.ui.thumbnail

import com.folderspan.PlatformType
import com.folderspan.data.file.FileFilterType
import com.folderspan.data.file.FileProtocol
import com.folderspan.data.file.FileSimpleInfo
import com.folderspan.data.main.device.DeviceType

internal const val REMOTE_THUMBNAIL_MAX_BYTES: Long = 20L * 1024L * 1024L
internal const val WEB_THUMBNAIL_MAX_BYTES: Long = 8L * 1024L * 1024L
internal const val THUMBNAIL_READ_CHUNK_BYTES: Int = 1024 * 1024
internal const val THUMBNAIL_REMOTE_DEBOUNCE_MILLIS: Long = 120L

internal data class FileThumbnailKey(
    val protocol: FileProtocol,
    val protocolId: String,
    val path: String,
    val size: Long,
    val updatedDate: Long,
    val targetSizePx: Int,
)

internal fun FileSimpleInfo.thumbnailKey(targetSizePx: Int): FileThumbnailKey = FileThumbnailKey(
    protocol = protocol,
    protocolId = protocolId,
    path = path,
    size = size,
    updatedDate = updatedDate,
    targetSizePx = thumbnailTargetBucket(targetSizePx),
)

internal fun thumbnailTargetBucket(targetSizePx: Int): Int = when {
    targetSizePx <= 64 -> 64
    targetSizePx <= 128 -> 128
    else -> 256
}

internal fun thumbnailPowerOfTwoSampleSize(width: Int, height: Int, targetSizePx: Int): Int {
    var sample = 1
    val maxDimension = maxOf(width, height)
    while (maxDimension / (sample * 2) >= targetSizePx) sample *= 2
    return sample
}

internal fun FileSimpleInfo.isThumbnailEligible(
    isWeb: Boolean = PlatformType == DeviceType.JS,
): Boolean {
    if (isDirectory || size <= 0L || isSymbolicLink) return false
    val isImage = mineType.startsWith("image/", ignoreCase = true) ||
        fileFilterType == FileFilterType.Image ||
        fileFilterType == FileFilterType.ImageRaw ||
        fileFilterType == FileFilterType.ImageVector
    return isImage && when {
        isWeb -> size <= WEB_THUMBNAIL_MAX_BYTES
        protocol != FileProtocol.Local -> size <= REMOTE_THUMBNAIL_MAX_BYTES
        else -> true
    }
}

internal fun thumbnailBitmapCacheMaxBytes(
    isWeb: Boolean = PlatformType == DeviceType.JS,
): Long = if (isWeb) 8L * 1024L * 1024L else 16L * 1024L * 1024L

internal class ByteSizeLruCache<K, V>(
    private val maxBytes: Long,
    private val sizeOf: (V) -> Long,
) {
    private val values = LinkedHashMap<K, V>()
    private var currentBytes = 0L

    val size: Int
        get() = values.size

    val byteSize: Long
        get() = currentBytes

    fun get(key: K): V? {
        val value = values.remove(key) ?: return null
        values[key] = value
        return value
    }

    fun put(key: K, value: V) {
        values.remove(key)?.let { previous -> currentBytes -= sizeOf(previous) }
        val valueBytes = sizeOf(value).coerceAtLeast(0L)
        if (valueBytes > maxBytes) return
        values[key] = value
        currentBytes += valueBytes
        trimToSize()
    }

    fun clear() {
        values.clear()
        currentBytes = 0L
    }

    private fun trimToSize() {
        while (currentBytes > maxBytes && values.isNotEmpty()) {
            val eldest = values.entries.first()
            values.remove(eldest.key)
            currentBytes -= sizeOf(eldest.value)
        }
    }
}
