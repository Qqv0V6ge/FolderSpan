package com.folderspan.service.session

import kotlinx.coroutines.test.runTest
import java.nio.file.Files
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DeviceSessionIncompleteTargetCleanupJvmTest {
    @Test
    fun failedTransferRemovesTargetCreatedByThatTransfer() = runTest {
        val directory = Files.createTempDirectory("folderspan-session-cleanup-")
        val target = directory.resolve("partial.bin")
        try {
            assertFailsWith<IllegalStateException> {
                withIncompleteDeviceSessionTargetCleanup(
                    path = target.toString(),
                    targetExistedBefore = false,
                ) {
                    target.writeText("partial")
                    error("connection closed")
                }
            }

            assertFalse(target.exists())
        } finally {
            Files.deleteIfExists(target)
            Files.deleteIfExists(directory)
        }
    }

    @Test
    fun failedTransferPreservesTargetThatAlreadyExisted() = runTest {
        val directory = Files.createTempDirectory("folderspan-session-cleanup-")
        val target = directory.resolve("existing.bin")
        try {
            target.writeText("original")
            assertFailsWith<IllegalStateException> {
                withIncompleteDeviceSessionTargetCleanup(
                    path = target.toString(),
                    targetExistedBefore = true,
                ) {
                    error("connection closed")
                }
            }

            assertTrue(target.exists())
            assertEquals("original", target.readText())
        } finally {
            Files.deleteIfExists(target)
            Files.deleteIfExists(directory)
        }
    }
}
