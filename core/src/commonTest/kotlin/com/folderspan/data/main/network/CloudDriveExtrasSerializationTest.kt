package com.folderspan.data.main.network

import com.folderspan.utils.ProtoBufCodec
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals

class CloudDriveExtrasSerializationTest {
    @Test
    fun remainingFieldsKeepTheirWireNumbersAfterRemovingDriveAndSite() {
        val beforeRemoval = byteArrayOf(
            0x0a, 1, 0x61, 0x12, 1, 0x72, 0x1a, 1, 0x64, 0x22, 1, 0x73,
            0x2a, 1, 0x6c, 0x32, 1, 0x63, 0x38, 1, 0x40, 1,
        )
        val expected = CloudDriveExtras(
            accountId = "a", rootId = "r", libraryId = "l", connectionId = "c",
            seafileRepoToken = true, readOnly = true,
        )
        assertEquals(expected, ProtoBufCodec.decode<CloudDriveExtras>(beforeRemoval))
        assertContentEquals(
            byteArrayOf(0x0a, 1, 0x61, 0x12, 1, 0x72, 0x2a, 1, 0x6c, 0x32, 1, 0x63, 0x38, 1, 0x40, 1),
            ProtoBufCodec.encode(expected),
        )
    }
}
