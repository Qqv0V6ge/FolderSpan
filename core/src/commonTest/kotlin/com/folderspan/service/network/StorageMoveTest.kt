package com.folderspan.service.network

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class StorageMoveTest {
    @Test fun unsupportedEmptyDirectoryDeletionNeverUsesRecursiveDelete() = runTest {
        var deletes = 0
        val provider = object : StorageProvider by UnsupportedNetworkClient("fixture") {
            override suspend fun delete(path: String, isDirectory: Boolean): Result<Boolean> {
                deletes++
                return Result.success(true)
            }
        }
        assertTrue(provider.deleteEmptyDirectory(StoragePath("/folder")).isFailure)
        assertEquals(0, deletes)
    }

    @Test fun sharedProtocolMoveChecksAbsenceAndNeverFallsBackAfterRenameFailure() = runTest {
        for (condition in listOf("success", "exists", "unreadable", "unsupported", "rename-failed", "forbidden")) {
            var renameCalls = 0
            val provider = object : StorageProvider by UnsupportedNetworkClient("fixture") {
                override suspend fun effectiveCapabilities() = Result.success(StorageCapabilities.ReadWrite.copy(rename = condition != "forbidden"))
                override suspend fun stat(path: StoragePath): Result<NetworkFileEntry> = when (condition) {
                    "exists" -> Result.success(NetworkFileEntry("target", path.path, false))
                    "unreadable" -> Result.failure(IllegalStateException("permission denied"))
                    else -> Result.failure(StorageEntryNotFoundException())
                }
                override suspend fun rename(path: StoragePath, newPath: String): Result<Boolean> {
                    renameCalls++
                    assertEquals("/source/file", path.path)
                    assertEquals("/archive/target", newPath)
                    return if (condition in listOf("unsupported", "rename-failed")) Result.failure(IllegalStateException(condition))
                        else Result.success(true)
                }
                override suspend fun move(from: StoragePath, to: StoragePath) = super<StorageProvider>.move(from, to)
            }
            assertEquals(condition == "success", provider.move(StoragePath("/source/file"), StoragePath("/archive/target")).isSuccess, condition)
            assertEquals(if (condition in listOf("success", "unsupported", "rename-failed")) 1 else 0, renameCalls, condition)
            assertTrue(provider.move(StoragePath("/source/file"), StoragePath("/source/file/child")).isFailure)
            assertTrue(provider.move(StoragePath("/source/file"), StoragePath("/archive/../escape")).isFailure)
        }
    }
}
