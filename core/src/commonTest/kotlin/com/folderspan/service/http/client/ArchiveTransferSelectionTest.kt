package com.folderspan.service.http.client

import com.folderspan.service.operation.OperationParallelismConfig
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ArchiveTransferSelectionTest {
    @Test
    fun disablesShareArchiveWhenHttpEnvelopeEncryptionIsActive() {
        assertFalse(shouldUseShareArchiveTransfer(encryptedHttpTransport = true))
    }

    @Test
    fun enablesShareArchiveForNativeRawTransport() {
        assertTrue(shouldUseShareArchiveTransfer(encryptedHttpTransport = false))
    }

    @Test
    fun capsArchiveBatchParallelismWithoutSerializingMultipleBatches() {
        val config = archiveTransferBatchOperationConfig(
            baseConfig = OperationParallelismConfig(
                initialParallelism = 8,
                maxParallelism = 24,
                queueCapacity = 96,
                hardMaxParallelism = 24,
            ),
            batchCount = 9,
        )

        assertEquals(ARCHIVE_TRANSFER_MAX_CONCURRENT_BATCHES, config.initialParallelism)
        assertEquals(ARCHIVE_TRANSFER_MAX_CONCURRENT_BATCHES, config.maxParallelism)
        assertEquals(ARCHIVE_TRANSFER_MAX_CONCURRENT_BATCHES, config.hardMaxParallelism)
        assertTrue(config.queueCapacity >= ARCHIVE_TRANSFER_MAX_CONCURRENT_BATCHES * 2)
    }

    @Test
    fun archiveBatchRuntimeMaxRespectsRuntimeAndBatchCount() {
        assertEquals(2, archiveTransferBatchRuntimeMax(runtimeMaxParallelism = 2, batchCount = 9))
        assertEquals(1, archiveTransferBatchRuntimeMax(runtimeMaxParallelism = 8, batchCount = 1))
        assertEquals(ARCHIVE_TRANSFER_MAX_CONCURRENT_BATCHES, archiveTransferBatchRuntimeMax(runtimeMaxParallelism = 24, batchCount = 9))
    }
}
