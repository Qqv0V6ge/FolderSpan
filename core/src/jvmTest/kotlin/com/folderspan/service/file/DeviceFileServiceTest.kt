package com.folderspan.service.file

import app.cash.sqldelight.ColumnAdapter
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.folderspan.cleanup.resolveDesktopApplicationDataDirectory
import com.folderspan.createSettings
import com.folderspan.data.file.FileFilterSort
import com.folderspan.data.file.FileFilterType
import com.folderspan.data.file.FileProtocol
import com.folderspan.data.main.device.DeviceCategory
import com.folderspan.data.main.device.DeviceConnectType
import com.folderspan.data.main.device.DeviceType
import com.folderspan.db.*
import com.folderspan.exception.AuthorityException
import com.folderspan.service.data.*
import com.folderspan.service.http.client.HttpRouteClientManager
import com.folderspan.service.operation.OperationParallelismConfig
import com.folderspan.testing.grantTestAdministratorAccess
import com.folderspan.ui.state.device.DeviceCertificateState
import com.folderspan.ui.state.device.DeviceSharePathGrant
import com.folderspan.ui.state.device.DeviceSharePathScope
import com.folderspan.ui.state.file.DrawerBookmarkType
import com.folderspan.utils.DatabaseReady
import com.folderspan.utils.SettingsUtils
import strings.AppStrings
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import java.io.RandomAccessFile
import java.nio.file.Files
import java.nio.file.Path
import java.util.*
import java.util.concurrent.atomic.AtomicInteger
import kotlin.io.path.absolutePathString
import kotlin.test.*
import kotlin.time.Duration.Companion.milliseconds

class DeviceFileServiceTest {
    @Test
    fun deviceShareScopeAllowsReadsOnlyAndRejectsUnsharedOrSymbolicLinkPaths() = runBlocking {
        val database = createInMemoryDatabase()
        val certificateState = DeviceCertificateState(database)
        val service = DeviceFileService(certificateState) { "device-a" }
        val token = "share-file-scope-token"
        // macOS 临时目录可能经过 /var 链接，共享授权夹具必须使用真实路径。
        val parent = Files.createTempDirectory("device-share-file-scope").toRealPath()
        val sharedDirectory = Files.createDirectory(parent.resolve("shared"))
        val allowedFile = Files.writeString(sharedDirectory.resolve("allowed.txt"), "allowed")
        val privateFile = Files.writeString(parent.resolve("private.txt"), "private")
        val symbolicLink = sharedDirectory.resolve("private-link")
        try {
            runCatching { Files.createSymbolicLink(symbolicLink, privateFile) }
            certificateState.setTokenPermission(token, 1L)
            certificateState.setDeviceSharePathScope(
                token,
                DeviceSharePathScope(
                    listOf(DeviceSharePathGrant(sharedDirectory.absolutePathString(), isDirectory = true))
                ),
            )

            val allowed = service.getFileByPath(token, GetFileByPathRequest(allowedFile.absolutePathString()))
            val unshared = service.getFileByPath(token, GetFileByPathRequest(privateFile.absolutePathString()))
            val write = service.appendToFile(token, AppendToFileRequest(allowedFile.absolutePathString(), "changed"))

            assertTrue(allowed.isSuccess)
            assertIs<AuthorityException>(unshared.exceptionOrNull())
            assertIs<AuthorityException>(write.exceptionOrNull())
            if (Files.isSymbolicLink(symbolicLink)) {
                val linked = service.getFileByPath(token, GetFileByPathRequest(symbolicLink.absolutePathString()))
                assertIs<AuthorityException>(linked.exceptionOrNull())
            }
        } finally {
            Files.deleteIfExists(symbolicLink)
            parent.toFile().deleteRecursively()
        }
    }

    @Test
    fun defaultDeviceFileAdaptersAllowOrdinaryFileAccessForAdminToken() = runBlocking {
        val database = createInMemoryDatabase()
        val certificateState = DeviceCertificateState(database)
        val service = DeviceFileService(certificateState) { "device-a" }
        val token = "file-admin-denied"
        certificateState.setTokenPermission(token, 1L)
        val directory = Files.createTempDirectory("device-file-default-denied")
        val target = directory.resolve("blocked.txt")
        try {
            val createResult = service.createFiles(
                token,
                CreateFileRequest(listOf(target.absolutePathString())),
            ).getOrThrow().single()
            val metadataResult = service.getFileByPath(
                token,
                GetFileByPathRequest(target.absolutePathString()),
            )
            val folderTarget = directory.resolve("blocked-folder")
            val folderResult = service.createFolders(
                token,
                CreateFolderRequest(listOf(folderTarget.absolutePathString())),
            ).getOrThrow().single()
            val streamWriteResult = service.prepareStreamWrite(
                token,
                target.absolutePathString(),
                fileSize = 3L,
            )

            assertTrue(createResult.getOrThrow())
            assertEquals(target.absolutePathString(), metadataResult.getOrThrow().path)
            assertTrue(folderResult.getOrThrow())
            assertTrue(streamWriteResult.getOrThrow())
            assertTrue(Files.isRegularFile(target))
            assertTrue(Files.isDirectory(folderTarget))
        } finally {
            directory.toFile().deleteRecursively()
        }
    }

    @Test
    fun createFoldersRunsAuthorizedPathsConcurrentlyAndPreservesResultOrder() = runBlocking {
        val database = createInMemoryDatabase()
        val certificateState = DeviceCertificateState(database)
        val token = "file-admin"
        certificateState.setTokenPermission(token, 1L)
        val directory = Files.createTempDirectory("device-file-create-folders")
        val paths = listOf("a", "b", "c", "d").map { name ->
            directory.resolve(name).absolutePathString()
        }
        val activeCreates = AtomicInteger(0)
        val maxActiveCreates = AtomicInteger(0)
        val service = DeviceFileService(
            deviceCertificateState = certificateState,
            folderCreateParallelismProvider = {
                OperationParallelismConfig(
                    initialParallelism = 4,
                    maxParallelism = 4,
                    queueCapacity = 8,
                    hardMaxParallelism = 4,
                )
            },
            folderCreateRuntimeMaxParallelismProvider = { 4 },
            createFolder = { path ->
                val active = activeCreates.incrementAndGet()
                maxActiveCreates.updateAndGet { current -> maxOf(current, active) }
                delay(100.milliseconds)
                activeCreates.decrementAndGet()
                Result.success(path.endsWith("a") || path.endsWith("b") || path.endsWith("c") || path.endsWith("d"))
            },
        )

        val result = service.createFolders(token, CreateFolderRequest(paths))

        assertTrue(result.isSuccess)
        assertEquals(listOf(true, true, true, true), result.getOrThrow().map { item -> item.getOrThrow() })
        assertTrue(maxActiveCreates.get() > 1, "expected concurrent folder creation")
    }

    @Test
    fun createFoldersContinuesAfterIndividualFailure() = runBlocking {
        val database = createInMemoryDatabase()
        val certificateState = DeviceCertificateState(database)
        val token = "file-admin"
        certificateState.setTokenPermission(token, 1L)
        val directory = Files.createTempDirectory("device-file-create-folders-failure")
        val paths = listOf("first", "bad", "last").map { name ->
            directory.resolve(name).absolutePathString()
        }
        val attempted = Collections.synchronizedList(mutableListOf<String>())
        val service = DeviceFileService(
            deviceCertificateState = certificateState,
            folderCreateParallelismProvider = {
                OperationParallelismConfig(
                    initialParallelism = 3,
                    maxParallelism = 3,
                    queueCapacity = 6,
                    hardMaxParallelism = 3,
                )
            },
            folderCreateRuntimeMaxParallelismProvider = { 3 },
            createFolder = { path ->
                attempted += path.substringAfterLast("/")
                if (path.endsWith("bad")) {
                    Result.failure(IllegalStateException("cannot create bad"))
                } else {
                    Result.success(true)
                }
            },
        )

        val result = service.createFolders(token, CreateFolderRequest(paths))

        assertTrue(result.isSuccess)
        val results = result.getOrThrow()
        assertTrue(results[0].getOrThrow())
        assertTrue(results[1].isFailure)
        assertTrue(results[2].getOrThrow())
        assertEquals(setOf("first", "bad", "last"), attempted.toSet())
    }

    @Test
    fun createFileAndGetFileByPathAreAllowedForAuthorizedToken() = runBlocking {
        val database = createInMemoryDatabase()
        val certificateState = DeviceCertificateState(database)
        val service = DeviceFileService(certificateState) { "device-a" }
        val token = "file-admin"
        certificateState.setTokenPermission(token, 1L)

        val directory = Files.createTempDirectory("device-file-service")
        val target = directory.resolve("hello.txt").absolutePathString()

        val createResult = service.createFiles(token, CreateFileRequest(listOf(target)))
        assertTrue(createResult.getOrThrow().single().getOrThrow())

        val fileResult = service.getFileByPath(token, GetFileByPathRequest(target))
        assertEquals(target, fileResult.getOrThrow().path)
        assertTrue(Files.isRegularFile(Path.of(target)))
    }

    @Test
    fun writeBytesAndReadBytesAreAllowedForAuthorizedOrdinaryPath() = runBlocking {
        val database = createInMemoryDatabase()
        val certificateState = DeviceCertificateState(database)
        val service = DeviceFileService(certificateState)
        val token = "file-admin"
        certificateState.setTokenPermission(token, 1L)

        val directory = Files.createTempDirectory("device-file-bytes")
        val target = directory.resolve("data.bin").absolutePathString()
        val data = "hello-webrtc".encodeToByteArray()

        val writeResult = service.writeBytes(
            token,
            WriteBytesRequest(
                fileSize = data.size.toLong(),
                blockIndex = 0,
                blockLength = data.size.toLong(),
                path = target,
                byteArray = data,
                actualFileSizeText = data.size.toString(),
                blockStartOffset = 0L,
            )
        )
        assertTrue(writeResult.getOrThrow())

        val readResult = service.readBytes(
            token,
            ReadBytesRequest(target, 0L, data.size.toLong())
        )
        assertContentEquals(data, readResult.getOrThrow())
        assertTrue(Files.isRegularFile(Path.of(target)))
    }

    @Test
    fun ignoredFileLookupAndReadRemainAvailableWhenPathIsAuthorized() = runBlocking {
        val database = createInMemoryDatabase()
        val certificateState = DeviceCertificateState(database)
        val service = DeviceFileService(certificateState)
        val token = "file-admin"
        certificateState.setTokenPermission(token, 1L)

        val directory = Files.createTempDirectory("device-file-ignore")
        val target = directory.resolve("secret.txt")
        Files.writeString(directory.resolve(".gitignore"), "secret.txt\n")
        Files.writeString(target, "secret")
        upsertPathPreference(database, directory.absolutePathString(), listOf(".gitignore"), timestamp = 1L)

        val lookup = service.getFileByPath(token, GetFileByPathRequest(target.absolutePathString()))
        val read = service.readBytes(token, ReadBytesRequest(target.absolutePathString(), 0L, 6L))

        assertEquals(target.absolutePathString(), lookup.getOrThrow().path)
        assertEquals("secret", read.getOrThrow().decodeToString())
        assertEquals("secret", Files.readString(target))
    }

    @Test
    fun writeBytesSupportsExplicitStartOffsets() = runBlocking {
        val database = createInMemoryDatabase()
        val certificateState = DeviceCertificateState(database)
        val service = DeviceFileService(certificateState)
        val token = "file-admin"
        certificateState.setTokenPermission(token, 1L)

        val directory = Files.createTempDirectory("device-file-offset")
        val target = directory.resolve("offset.bin").absolutePathString()
        val head = "hello-".encodeToByteArray()
        val tail = "webrtc".encodeToByteArray()
        val fileSize = (head.size + tail.size).toLong()

        val firstWrite = service.writeBytes(
            token,
            WriteBytesRequest(
                fileSize = fileSize,
                blockIndex = 0L,
                blockLength = head.size.toLong(),
                path = target,
                byteArray = head,
                actualFileSizeText = fileSize.toString(),
                blockStartOffset = 0L,
            )
        )
        assertTrue(firstWrite.getOrThrow())

        val secondWrite = service.writeBytes(
            token,
            WriteBytesRequest(
                fileSize = fileSize,
                blockIndex = 1L,
                blockLength = tail.size.toLong(),
                path = target,
                byteArray = tail,
                actualFileSizeText = fileSize.toString(),
                blockStartOffset = head.size.toLong(),
            )
        )
        assertTrue(secondWrite.getOrThrow())

        val readResult = service.readBytes(
            token,
            ReadBytesRequest(target, 0L, fileSize)
        )
        assertEquals("hello-webrtc", readResult.getOrThrow().decodeToString())
        assertTrue(Files.isRegularFile(Path.of(target)))
    }

    @Test
    fun deviceDirectServiceAcceptsPreparedByteRangesWithinItsLargerLimit() = runBlocking {
        val database = createInMemoryDatabase()
        val certificateState = DeviceCertificateState(database)
        val token = "file-admin"
        certificateState.setTokenPermission(token, 1L)
        val directory = Files.createTempDirectory("device-file-direct-range")
        val target = directory.resolve("large.bin").absolutePathString()
        val largerThanGeneric = HttpRouteClientManager.MAX_LENGTH + 1L
        RandomAccessFile(target, "rw").use { file ->
            file.setLength(largerThanGeneric)
        }

        val defaultService = DeviceFileService(certificateState)
        val defaultRead = defaultService.prepareReadBytes(
            token,
            ReadBytesRequest(target, 0L, largerThanGeneric)
        )
        assertTrue(defaultRead.isFailure)

        val defaultResult = defaultService.prepareWriteBytes(
            token,
            WriteBytesRequest(
                fileSize = largerThanGeneric,
                blockIndex = 0L,
                blockLength = largerThanGeneric,
                path = target,
                byteArray = byteArrayOf(),
                actualFileSizeText = largerThanGeneric.toString(),
                blockStartOffset = 0L,
            ).toStreamRequest()
        )
        assertTrue(defaultResult.isFailure)

        val deviceDirectService = DeviceFileService(
            certificateState,
            maxByteRangeLength = HttpRouteClientManager.DEVICE_DIRECT_MAX_LENGTH,
        )
        val largerRead = deviceDirectService.prepareReadBytes(
            token,
            ReadBytesRequest(target, 0L, largerThanGeneric)
        )
        assertTrue(largerRead.isSuccess)

        val largerRange = deviceDirectService.prepareWriteBytes(
            token,
            WriteBytesRequest(
                fileSize = largerThanGeneric,
                blockIndex = 0L,
                blockLength = largerThanGeneric,
                path = target,
                byteArray = byteArrayOf(),
                actualFileSizeText = largerThanGeneric.toString(),
                blockStartOffset = 0L,
            ).toStreamRequest()
        )
        assertTrue(largerRange.isSuccess)

        val missingOffset = deviceDirectService.prepareWriteBytes(
            token,
            WriteBytesRequest(
                fileSize = largerThanGeneric,
                blockIndex = 1L,
                blockLength = 1L,
                path = target,
                byteArray = byteArrayOf(),
                actualFileSizeText = largerThanGeneric.toString(),
            ).toStreamRequest()
        )
        assertTrue(missingOffset.isFailure)
        assertEquals(AppStrings.error_write_block_offset_invalid, missingOffset.exceptionOrNull()?.message)
    }

    @Test
    fun deviceDirectStreamFileAcceptsRangesWithinStreamLimit() = runBlocking {
        val database = createInMemoryDatabase()
        val certificateState = DeviceCertificateState(database)
        val token = "file-admin"
        certificateState.setTokenPermission(token, 1L)
        val directory = Files.createTempDirectory("device-file-stream-range")
        val target = directory.resolve("stream.bin").absolutePathString()
        val streamRangeSize = HttpRouteClientManager.DEVICE_DIRECT_MAX_LENGTH + 1L
        RandomAccessFile(target, "rw").use { file ->
            file.setLength(streamRangeSize)
        }

        val service = DeviceFileService(
            certificateState,
            maxByteRangeLength = HttpRouteClientManager.DEVICE_DIRECT_MAX_LENGTH,
        )

        assertTrue(
            service.prepareReadBytes(
                token,
                ReadBytesRequest(target, 0L, streamRangeSize),
            ).isFailure
        )

        val stream = service.prepareStreamFile(
            authToken = token,
            request = DeviceStreamFileRequest(target, 0L, streamRangeSize),
            maxRangeLength = HttpRouteClientManager.DEVICE_DIRECT_STREAM_RANGE_BYTES.toLong(),
        )

        assertTrue(stream.isSuccess)
    }

    @Test
    fun appendAndReadLinesAreAllowedForAuthorizedOrdinaryPath() = runBlocking {
        val database = createInMemoryDatabase()
        val certificateState = DeviceCertificateState(database)
        val service = DeviceFileService(certificateState)
        val token = "file-admin"
        certificateState.setTokenPermission(token, 1L)

        val directory = Files.createTempDirectory("device-file-lines")
        val target = directory.resolve("log.txt").absolutePathString()

        val firstAppend = service.appendToFile(token, AppendToFileRequest(target, "a\n"))
        val secondAppend = service.appendToFile(token, AppendToFileRequest(target, "b\n"))

        val result = service.readFileLines(token, ReadFileLinesRequest(target))
        assertTrue(firstAppend.getOrThrow())
        assertTrue(secondAppend.getOrThrow())
        assertEquals(listOf("a", "b"), result.getOrThrow())
        assertTrue(Files.isRegularFile(Path.of(target)))
    }

    @Test
    fun readLinesReturnsFailureForMissingFile() = runBlocking {
        val database = createInMemoryDatabase()
        val certificateState = DeviceCertificateState(database)
        val service = DeviceFileService(certificateState)
        val token = "file-admin"
        certificateState.setTokenPermission(token, 1L)
        val missingPath = Files.createTempDirectory("device-file-lines-missing")
            .resolve("missing.txt")
            .absolutePathString()

        val result = service.readFileLines(token, ReadFileLinesRequest(missingPath))

        assertTrue(result.isFailure)
    }

    @Test
    fun readLinesReturnsFailureForDirectory() = runBlocking {
        val database = createInMemoryDatabase()
        val certificateState = DeviceCertificateState(database)
        val service = DeviceFileService(certificateState)
        val token = "file-admin"
        certificateState.setTokenPermission(token, 1L)
        val directory = Files.createTempDirectory("device-file-lines-directory")

        val result = service.readFileLines(token, ReadFileLinesRequest(directory.absolutePathString()))

        assertTrue(result.isFailure)
    }

    @Test
    fun guestTokenCannotWriteFile() = runBlocking {
        val database = createInMemoryDatabase()
        val certificateState = DeviceCertificateState(database)
        val service = DeviceFileService(certificateState)
        val token = "file-guest"
        certificateState.setTokenPermission(token, 999L)

        val directory = Files.createTempDirectory("device-file-guest")
        val target = directory.resolve("deny.txt").absolutePathString()
        val result = service.appendToFile(token, AppendToFileRequest(target, "x"))

        assertTrue(result.isFailure)
        assertIs<AuthorityException>(result.exceptionOrNull())
        assertTrue(Files.notExists(directory.resolve("deny.txt")))
    }

    @Test
    fun copyPathCopiesAuthorizedOrdinaryFileAndReportsProgress() = runBlocking {
        val database = createInMemoryDatabase()
        val certificateState = DeviceCertificateState(database)
        val service = DeviceFileCopyService(certificateState)
        val token = "file-admin"
        certificateState.setTokenPermission(token, 1L)

        val directory = Files.createTempDirectory("device-file-copy")
        val source = directory.resolve("src.txt")
        val dest = directory.resolve("dest.txt")
        Files.writeString(source, "copy-me")
        val progressEvents = mutableListOf<CopyPathProgress>()

        val result = service.copyPath(
            authToken = token,
            request = CopyPathRequest(
                srcPath = source.absolutePathString(),
                destPath = dest.absolutePathString(),
                requestId = "copy-test"
            ),
            onProgress = { progressEvents += it }
        )

        assertTrue(result.getOrThrow())
        assertEquals("copy-me", Files.readString(dest))
        assertTrue(progressEvents.last().done)
        assertTrue(progressEvents.last().success)
        assertFalse(listFileNames(directory).any { it.startsWith(".dest.txt.copying-") })
    }

    @Test
    fun copyPathOverwritesExistingFileWhenAuthorized() = runBlocking {
        val database = createInMemoryDatabase()
        val certificateState = DeviceCertificateState(database)
        val service = DeviceFileCopyService(certificateState)
        val token = "file-admin"
        certificateState.setTokenPermission(token, 1L)

        val directory = Files.createTempDirectory("device-file-copy-overwrite")
        val source = directory.resolve("src.txt")
        val dest = directory.resolve("dest.txt")
        Files.writeString(source, "new")
        Files.writeString(dest, "old-content")

        val result = service.copyPath(
            authToken = token,
            request = CopyPathRequest(
                srcPath = source.absolutePathString(),
                destPath = dest.absolutePathString(),
                requestId = "copy-overwrite-test"
            ),
            onProgress = {}
        )

        assertTrue(result.getOrThrow())
        assertEquals("new", Files.readString(dest))
        val names = listFileNames(directory)
        assertFalse(names.any { it.startsWith(".dest.txt.copying-") })
        assertFalse(names.any { it.startsWith(".dest.txt.backup-") })
    }

    @Test
    fun copyPathCopiesAuthorizedDirectoryTree() = runBlocking {
        val database = createInMemoryDatabase()
        val certificateState = DeviceCertificateState(database)
        val service = DeviceFileCopyService(certificateState)
        val token = "file-admin"
        certificateState.setTokenPermission(token, 1L)

        val directory = Files.createTempDirectory("device-file-copy-dir")
        val source = directory.resolve("src-dir")
        val nested = source.resolve("nested")
        val dest = directory.resolve("dest-dir")
        Files.createDirectories(nested)
        Files.writeString(nested.resolve("file.txt"), "copy-dir")
        val progressEvents = mutableListOf<CopyPathProgress>()

        val result = service.copyPath(
            authToken = token,
            request = CopyPathRequest(
                srcPath = source.absolutePathString(),
                destPath = dest.absolutePathString(),
                requestId = "copy-dir-test"
            ),
            onProgress = { progressEvents += it }
        )

        assertTrue(result.getOrThrow())
        assertEquals("copy-dir", Files.readString(dest.resolve("nested/file.txt")))
        assertTrue(progressEvents.last().done)
        assertTrue(progressEvents.last().success)
        val names = listFileNames(directory)
        assertFalse(names.any { it.startsWith(".dest-dir.copying-") })
        assertFalse(names.any { it.startsWith(".dest-dir.backup-") })
    }

    @Test
    fun deviceFileServiceRejectsProtectedApplicationDataBeforeFileSystemAccess() = runBlocking {
        val database = createInMemoryDatabase()
        val certificateState = DeviceCertificateState(database)
        val service = DeviceFileService(certificateState)
        val token = "file-admin-protected"
        certificateState.setTokenPermission(token, 1L)
        val protectedPath = resolveDesktopApplicationDataDirectory().resolve("credentials.bin").toString()

        val lookup = service.getFileByPath(token, GetFileByPathRequest(protectedPath))
        val create = service.createFiles(token, CreateFileRequest(listOf(protectedPath)))
            .getOrThrow()
            .single()

        assertIs<AuthorityException>(lookup.exceptionOrNull())
        assertIs<AuthorityException>(create.exceptionOrNull())
        assertTrue(lookup.exceptionOrNull()?.message.orEmpty().contains("application_private_data"))
        assertFalse(lookup.exceptionOrNull()?.message.orEmpty().contains(protectedPath))
    }

    @Test
    fun prepareWriteBytesRejectsOverflowingAndPastEndRanges() = runBlocking {
        val database = createInMemoryDatabase()
        val certificateState = DeviceCertificateState(database)
        val service = DeviceFileService(certificateState)
        val token = "file-admin"
        certificateState.setTokenPermission(token, 1L)
        val directory = Files.createTempDirectory("device-file-write-bounds")
        val target = directory.resolve("data.bin").absolutePathString()

        val overflowing = service.prepareWriteBytes(
            token,
            WriteBytesRequest(
                fileSize = 1024L,
                blockIndex = 0L,
                blockLength = 8L,
                path = target,
                byteArray = ByteArray(8),
                actualFileSizeText = "1024",
                blockStartOffset = Long.MAX_VALUE - 4L,
            ).toStreamRequest(),
        )
        val pastEnd = service.prepareWriteBytes(
            token,
            WriteBytesRequest(
                fileSize = 1024L,
                blockIndex = 0L,
                blockLength = 16L,
                path = target,
                byteArray = ByteArray(16),
                actualFileSizeText = "1024",
                blockStartOffset = 1020L,
            ).toStreamRequest(),
        )

        assertTrue(overflowing.isFailure)
        assertTrue(pastEnd.isFailure)
        assertEquals(AppStrings.error_write_block_range_exceeds_file, overflowing.exceptionOrNull()?.message)
        assertEquals(AppStrings.error_write_block_range_exceeds_file, pastEnd.exceptionOrNull()?.message)
    }

    @Test
    fun controlCopyRejectsNonOwnerTokenAndBlankRequestId() = runBlocking {
        val database = createInMemoryDatabase()
        val certificateState = DeviceCertificateState(database)
        val service = DeviceFileCopyService(certificateState)
        val ownerToken = "file-copy-owner"
        val otherToken = "file-copy-other"
        certificateState.setTokenPermission(ownerToken, 1L)
        certificateState.setTokenPermission(otherToken, 1L)
        val requestId = "copy-ownership-test"

        assertTrue(RemoteCopyControlRegistry.register(requestId, ownerToken))
        try {
            val blankId = service.controlCopy(
                ownerToken,
                CopyPathControlRequest("", CopyPathControlAction.Cancel),
            )
            val stolen = service.controlCopy(
                otherToken,
                CopyPathControlRequest(requestId, CopyPathControlAction.Cancel),
            )
            val owned = service.controlCopy(
                ownerToken,
                CopyPathControlRequest(requestId, CopyPathControlAction.Cancel),
            )

            assertTrue(blankId.isFailure)
            assertEquals(AppStrings.error_request_id_required, blankId.exceptionOrNull()?.message)
            assertFalse(stolen.getOrThrow())
            assertTrue(owned.getOrThrow())
        } finally {
            RemoteCopyControlRegistry.remove(requestId)
        }
    }

    @Test
    fun copyPathRejectsOccupiedRequestId() = runBlocking {
        val database = createInMemoryDatabase()
        val certificateState = DeviceCertificateState(database)
        val service = DeviceFileCopyService(certificateState)
        val token = "file-admin"
        certificateState.setTokenPermission(token, 1L)
        val directory = Files.createTempDirectory("device-file-copy-occupied")
        val source = directory.resolve("src.txt")
        val dest = directory.resolve("dest.txt")
        Files.writeString(source, "copy-me")
        val requestId = "copy-occupied-test"

        assertTrue(RemoteCopyControlRegistry.register(requestId, token))
        try {
            val result = service.copyPath(
                authToken = token,
                request = CopyPathRequest(
                    srcPath = source.absolutePathString(),
                    destPath = dest.absolutePathString(),
                    requestId = requestId,
                ),
                onProgress = {},
            )

            assertTrue(result.isFailure)
            assertEquals(AppStrings.error_copy_request_id_invalid_or_occupied, result.exceptionOrNull()?.message)
            assertTrue(Files.notExists(dest))
        } finally {
            RemoteCopyControlRegistry.remove(requestId)
        }
    }

    @Test
    fun copyPathRejectsBlankRequestId() = runBlocking {
        val database = createInMemoryDatabase()
        val certificateState = DeviceCertificateState(database)
        val service = DeviceFileCopyService(certificateState)
        val token = "file-admin"
        certificateState.setTokenPermission(token, 1L)
        val directory = Files.createTempDirectory("device-file-copy-blank-id")
        val source = directory.resolve("src.txt")
        val dest = directory.resolve("dest.txt")
        Files.writeString(source, "copy-me")

        val result = service.copyPath(
            authToken = token,
            request = CopyPathRequest(
                srcPath = source.absolutePathString(),
                destPath = dest.absolutePathString(),
                requestId = "   ",
            ),
            onProgress = {},
        )

        assertTrue(result.isFailure)
        assertEquals(AppStrings.error_request_id_required, result.exceptionOrNull()?.message)
        assertTrue(Files.notExists(dest))
    }
}

private fun listFileNames(directory: Path): List<String> {
    val stream = Files.list(directory)
    return stream.use { stream ->
        stream.map { it.fileName.toString() }.toList()
    }
}

private fun upsertPathPreference(
    database: FolderSpanDatabase,
    path: String,
    ignoreFiles: List<String>,
    timestamp: Long,
) {
    database.filePathPreferenceQueries.upsert(
        protocol = FileProtocol.Local,
        protocolId = "",
        path = path,
        sort = FileFilterSort.NameAsc,
        isHideFile = false,
        ignoreFiles = ignoreFiles,
        createdAt = timestamp,
        updatedAt = timestamp,
        lastAccessed = timestamp,
    )
}

private fun createInMemoryDatabase(): FolderSpanDatabase {
    SettingsUtils.init(createSettings())
    val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
    FolderSpanDatabase.Schema.create(driver)
    DatabaseReady.markReady()

    return FolderSpanDatabase(
        driver = driver,
        DeviceAdapter = Device.Adapter(
            typeAdapter = deviceTypeAdapter,
        ),
        DeviceConnectAdapter = DeviceConnect.Adapter(
            connectionTypeAdapter = deviceConnectTypeAdapter,
            categoryAdapter = deviceCategoryAdapter,
        ),
        FileBookmarkAdapter = FileBookmark.Adapter(
            typeAdapter = drawerBookmarkTypeAdapter,
            protocolAdapter = fileProtocolAdapter,
        ),
        FileFavoriteAdapter = FileFavorite.Adapter(
            protocolAdapter = fileProtocolAdapter,
        ),
        FileRecentAdapter = FileRecent.Adapter(
            protocolAdapter = fileProtocolAdapter,
        ),
        FileFilterAdapter = FileFilter.Adapter(
            typeAdapter = fileFilterTypeAdapter,
            extensionsAdapter = listOfStringsAdapter
        ),
        DeviceReceiveShareAdapter = DeviceReceiveShare.Adapter(
            connectionTypeAdapter = deviceConnectTypeAdapter,
        ),
        FilePathPreferenceAdapter = FilePathPreference.Adapter(
            protocolAdapter = fileProtocolAdapter,
            sortAdapter = fileFilterSortAdapter,
            ignoreFilesAdapter = listOfStringsAdapter,
        )
    ).also { database -> database.grantTestAdministratorAccess() }
}

private val deviceTypeAdapter = object : ColumnAdapter<DeviceType, String> {
    override fun decode(databaseValue: String): DeviceType = DeviceType.valueOf(databaseValue)
    override fun encode(value: DeviceType): String = value.name
}

private val deviceConnectTypeAdapter = object : ColumnAdapter<DeviceConnectType, String> {
    override fun decode(databaseValue: String): DeviceConnectType = DeviceConnectType.valueOf(databaseValue)
    override fun encode(value: DeviceConnectType): String = value.name
}

private val deviceCategoryAdapter = object : ColumnAdapter<DeviceCategory, String> {
    override fun decode(databaseValue: String): DeviceCategory = DeviceCategory.valueOf(databaseValue)
    override fun encode(value: DeviceCategory): String = value.name
}

private val drawerBookmarkTypeAdapter = object : ColumnAdapter<DrawerBookmarkType, String> {
    override fun decode(databaseValue: String): DrawerBookmarkType = DrawerBookmarkType.valueOf(databaseValue)
    override fun encode(value: DrawerBookmarkType): String = value.name
}

private val fileProtocolAdapter = object : ColumnAdapter<FileProtocol, String> {
    override fun decode(databaseValue: String): FileProtocol = FileProtocol.valueOf(databaseValue)
    override fun encode(value: FileProtocol): String = value.name
}

private val fileFilterTypeAdapter = object : ColumnAdapter<FileFilterType, String> {
    override fun decode(databaseValue: String): FileFilterType = FileFilterType.valueOf(databaseValue)
    override fun encode(value: FileFilterType): String = value.name
}

private val listOfStringsAdapter = object : ColumnAdapter<List<String>, String> {
    override fun decode(databaseValue: String): List<String> =
        databaseValue.split(",").map { item -> item.trim() }.filter { item -> item.isNotEmpty() }

    override fun encode(value: List<String>): String = value.joinToString(",")
}

private val fileFilterSortAdapter = object : ColumnAdapter<FileFilterSort, String> {
    override fun decode(databaseValue: String): FileFilterSort = FileFilterSort.valueOf(databaseValue)
    override fun encode(value: FileFilterSort): String = value.name
}
