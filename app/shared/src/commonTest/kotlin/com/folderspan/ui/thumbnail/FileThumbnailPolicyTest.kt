package com.folderspan.ui.thumbnail

import com.folderspan.data.file.FileProtocol
import com.folderspan.data.file.FileSimpleInfo
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class FileThumbnailPolicyTest {
    @Test
    fun acceptsNativeLocalImageWithoutRemoteLimit() {
        assertTrue(imageFile(size = 64L * 1024L * 1024L).isThumbnailEligible(isWeb = false))
    }

    @Test
    fun rejectsOversizedRemoteAndWebImages() {
        assertFalse(
            imageFile(
                protocol = FileProtocol.Device,
                size = REMOTE_THUMBNAIL_MAX_BYTES + 1,
            ).isThumbnailEligible(isWeb = false)
        )
        assertFalse(
            imageFile(size = WEB_THUMBNAIL_MAX_BYTES + 1).isThumbnailEligible(isWeb = true)
        )
    }

    @Test
    fun rejectsDirectoriesAndNonImages() {
        assertFalse(imageFile(isDirectory = true).isThumbnailEligible(isWeb = false))
        assertFalse(imageFile(mimeType = "text/plain").isThumbnailEligible(isWeb = false))
    }

    @Test
    fun bucketsTargetSizeAndBuildsStableIdentity() {
        assertEquals(64, thumbnailTargetBucket(40))
        assertEquals(128, thumbnailTargetBucket(96))
        assertEquals(256, thumbnailTargetBucket(300))
        assertEquals(32, thumbnailPowerOfTwoSampleSize(width = 10_000, height = 100, targetSizePx = 256))

        val file = imageFile(protocol = FileProtocol.Share, protocolId = "room")
        assertEquals(file.thumbnailKey(52), file.thumbnailKey(64))
    }

    @Test
    fun byteLruEvictsLeastRecentlyUsedValue() {
        val cache = ByteSizeLruCache<String, String>(maxBytes = 6) { value -> value.length.toLong() }
        cache.put("a", "aa")
        cache.put("b", "bb")
        cache.put("c", "cc")
        assertEquals("aa", cache.get("a"))

        cache.put("d", "ddd")

        assertNull(cache.get("b"))
        assertNull(cache.get("c"))
        assertEquals("aa", cache.get("a"))
        assertEquals("ddd", cache.get("d"))
        assertTrue(cache.byteSize <= 6)
    }
}

private fun imageFile(
    protocol: FileProtocol = FileProtocol.Local,
    protocolId: String = "",
    size: Long = 1024,
    mimeType: String = "image/jpeg",
    isDirectory: Boolean = false,
): FileSimpleInfo = FileSimpleInfo(
    name = "photo.jpg",
    isDirectory = isDirectory,
    isHidden = false,
    path = "/photo.jpg",
    mineType = mimeType,
    size = size,
    createdDate = 1,
    updatedDate = 2,
    protocol = protocol,
    protocolId = protocolId,
)
