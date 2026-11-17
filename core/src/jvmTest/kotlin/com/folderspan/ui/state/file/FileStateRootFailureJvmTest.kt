package com.folderspan.ui.state.file

import com.folderspan.data.file.FileProtocol
import com.folderspan.data.file.FileSimpleInfo
import com.folderspan.data.file.PathInfo
import com.folderspan.data.main.DiskBase
import com.folderspan.data.main.Local
import com.folderspan.data.main.device.Device
import com.folderspan.data.main.device.DeviceType
import com.folderspan.exception.AuthenticationException
import com.folderspan.service.data.ListRequest
import com.folderspan.service.path.DevicePathClient
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.Flow
import sun.misc.Unsafe
import kotlin.test.*

class FileStateRootFailureJvmTest {
    @Test fun rootAuthenticationFailureIsPropagated() = runBlocking {
        val paths = object : DevicePathClient {
            override suspend fun getRootPaths(requestId: String?, batchId: String?): Result<List<PathInfo>> =
                Result.failure(AuthenticationException("expired session"))
            override suspend fun listPath(request: ListRequest, requestId: String?, batchId: String?): Result<List<FileSimpleInfo>> = error("unused")
            override fun traversePath(path: String, requestId: String?, batchId: String?): Flow<Result<List<FileSimpleInfo>>> = error("unused")
            override suspend fun exists(path: String): Result<Boolean> = error("unused")
            override suspend fun createDirectory(path: String): Result<Boolean> = error("unused")
            override suspend fun deleteDirectory(path: String): Result<Boolean> = error("unused")
        }
        val device = Device("peer", "Peer", "/", mutableMapOf(), DeviceType.JVM, "token", pathClient = paths)
        val reader = FileStateEntryReader({ device }, blank(FileStateDirectoryCollector::class.java))
        assertFailsWith<AuthenticationException> { reader.getRootPaths(device) }
        Unit
    }

    @Test fun failedRootLoadDoesNotContinueNavigationOrLoadBookmarks() = runBlocking {
        var desk: DiskBase = Local()
        val target = Device("peer", "Peer", "/", mutableMapOf(), DeviceType.JVM, "token")
        var pathUpdated = false
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val switcher = FileStateDeskSwitcher(
                mainScope = scope,
                fileBookmarkState = blank(FileBookmarkState::class.java),
                currentDesk = { desk },
                currentPath = { "/" },
                setDesk = { desk = it },
                setPathDirectly = { pathUpdated = true },
                setRootPath = { error("no root was loaded") },
                rememberPathForDesk = { _, _ -> },
                rememberedPathForDesk = { null },
                cancelListRequestsForDesk = {},
                getRootPaths = { Result.failure(AuthenticationException()) },
                updatePath = { pathUpdated = true },
                updateFileAndFolder = { pathUpdated = true },
            )
            switcher.updateDesk(FileProtocol.Device, target)
            withTimeout(5_000) { scope.coroutineContext.job.children.toList().joinAll() }
            assertSame(target, desk)
            assertFalse(pathUpdated)
        } finally { scope.cancel() }
    }
}

private fun <T> blank(type: Class<T>): T {
    val unsafe = Unsafe::class.java.getDeclaredField("theUnsafe").apply { isAccessible = true }.get(null) as Unsafe
    return type.cast(unsafe.allocateInstance(type))
}
