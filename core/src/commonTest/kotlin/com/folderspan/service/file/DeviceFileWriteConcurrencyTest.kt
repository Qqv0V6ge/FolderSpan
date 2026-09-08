package com.folderspan.service.file

import kotlin.test.Test
import kotlin.test.assertEquals

class DeviceFileWriteConcurrencyTest {
    @Test
    fun sameTargetFileWritesAreSerialized() {
        assertEquals(1, sameTargetFileWriteParallelism(12))
        assertEquals(1, sameTargetFileWriteParallelism(4))
        assertEquals(1, sameTargetFileWriteParallelism(1))
    }
}
