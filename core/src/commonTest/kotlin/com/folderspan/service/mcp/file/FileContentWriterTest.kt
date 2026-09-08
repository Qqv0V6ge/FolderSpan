package com.folderspan.service.mcp.file

import com.folderspan.data.file.FileProtocol
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

class FileContentWriterTest {
    @Test
    fun overwriteAppendAndEmptyContentWorkAcrossEveryEndpointProtocol() = runTest {
        val endpoints = listOf(
            FileEndpointRef(FileProtocol.Local),
            FileEndpointRef(FileProtocol.Device, "device"),
            FileEndpointRef(FileProtocol.Share, "share"),
            FileEndpointRef(FileProtocol.Network, "network"),
        )

        endpoints.forEach { endpoint ->
            val gateway = WriterMemoryGateway(endpoint).apply { putFile("/notes.txt", "old".encodeToByteArray()) }
            val writer = FileContentWriter(resolverFor(gateway))
            val locator = FileLocator(endpoint, "/notes.txt")

            val replaced = writer.write(locator, "hello").getOrThrow()
            val appended = writer.write(
                locator = locator,
                data = "IQ==",
                encoding = FileContentEncoding.Base64,
                mode = FileWriteMode.Append,
            ).getOrThrow()
            val cleared = writer.write(locator, "").getOrThrow()
            val created = writer.write(FileLocator(endpoint, "/created.txt"), "new").getOrThrow()

            assertEquals(5, replaced.bytesWritten, endpoint.toString())
            assertEquals(FileWriteMode.Append, appended.mode, endpoint.toString())
            assertEquals(1, appended.bytesWritten, endpoint.toString())
            assertEquals(0, cleared.entry.size, endpoint.toString())
            assertEquals(0, cleared.bytesWritten, endpoint.toString())
            assertEquals("", gateway.fileBytes("/notes.txt")?.decodeToString(), endpoint.toString())
            assertEquals("new", gateway.fileBytes("/created.txt")?.decodeToString(), endpoint.toString())
            assertEquals(3, created.entry.size, endpoint.toString())
        }
    }

    @Test
    fun payloadAndPreconditionValidationHappensBeforeMutation() = runTest {
        val gateway = WriterMemoryGateway(FileEndpointRef(FileProtocol.Local)).apply {
            putFile("/notes.txt", "old".encodeToByteArray(), updatedAt = 42L)
        }
        var resolveCalls = 0
        val resolver = FileEndpointResolver().apply {
            register(FileProtocol.Local) {
                resolveCalls++
                gateway
            }
        }
        val writer = FileContentWriter(resolver)
        val locator = FileLocator(gateway.endpoint, "/notes.txt")

        assertEndpointError(
            writer.write(locator, "x".repeat(MAX_FILE_WRITE_BYTES + 1)),
            FileEndpointErrorCode.InvalidArgument,
        )
        assertEndpointError(
            writer.write(locator, "not base64!", FileContentEncoding.Base64),
            FileEndpointErrorCode.InvalidArgument,
        )
        assertEndpointError(
            writer.write(locator, "new", expectedSize = -1L),
            FileEndpointErrorCode.InvalidArgument,
        )
        assertEquals(0, resolveCalls)

        assertEndpointError(
            writer.write(locator, "new", expectedSize = 4L),
            FileEndpointErrorCode.Conflict,
        )
        assertEndpointError(
            writer.write(locator, "new", expectedSize = 3L, expectedUpdatedAt = 41L),
            FileEndpointErrorCode.Conflict,
        )
        assertContentEquals("old".encodeToByteArray(), gateway.fileBytes("/notes.txt"))
        assertEquals(0, gateway.writeCalls)

        writer.write(locator, "new", expectedSize = 3L, expectedUpdatedAt = 42L).getOrThrow()
        assertEquals("new", gateway.fileBytes("/notes.txt")?.decodeToString())
        assertEquals(1, gateway.writeCalls)
    }

    @Test
    fun targetKindsCapabilitiesAndDirectoryCreationAreEnforced() = runTest {
        val gateway = WriterMemoryGateway(FileEndpointRef(FileProtocol.Share, "share")).apply {
            putDirectory("/folder")
            putFile("/file.txt", "content".encodeToByteArray())
        }
        val writer = FileContentWriter(resolverFor(gateway))

        assertEndpointError(
            writer.write(FileLocator(gateway.endpoint, "/missing.txt"), "x", mode = FileWriteMode.Append),
            FileEndpointErrorCode.NotFound,
        )
        assertEndpointError(
            writer.write(FileLocator(gateway.endpoint, "/folder"), "x"),
            FileEndpointErrorCode.InvalidArgument,
        )

        val created = writer.createDirectory(FileLocator(gateway.endpoint, "/created")).getOrThrow()
        val repeated = writer.createDirectory(FileLocator(gateway.endpoint, "/created")).getOrThrow()
        assertTrue(created.isDirectory)
        assertEquals(created.path, repeated.path)
        assertEquals(1, gateway.createDirectoryCalls)
        assertEndpointError(
            writer.createDirectory(FileLocator(gateway.endpoint, "/file.txt")),
            FileEndpointErrorCode.Conflict,
        )

        val readOnlyGateway = WriterMemoryGateway(
            endpoint = FileEndpointRef(FileProtocol.Device, "read-only"),
            writable = false,
        )
        val readOnlyWriter = FileContentWriter(resolverFor(readOnlyGateway))
        assertEndpointError(
            readOnlyWriter.write(FileLocator(readOnlyGateway.endpoint, "/new.txt"), "x"),
            FileEndpointErrorCode.Unsupported,
        )
        assertFalse(readOnlyGateway.fileBytes("/new.txt") != null)
        assertEquals(0, readOnlyGateway.writeCalls)
    }

    @Test
    fun cleanupFailureDoesNotReplaceTheOriginalWriteFailure() = runTest {
        val writeFailure = FileEndpointException(FileEndpointErrorCode.IoError, "write failed")
        val gateway = WriterMemoryGateway(FileEndpointRef(FileProtocol.Local)).apply {
            failWritesWith = writeFailure
            failAbortWith = IllegalStateException("cleanup failed")
        }
        val writer = FileContentWriter(resolverFor(gateway))

        val result = writer.write(FileLocator(gateway.endpoint, "/new.txt"), "new")

        assertSame(writeFailure, result.exceptionOrNull())
        assertEquals(1, gateway.abortCalls)
    }

    private fun resolverFor(vararg gateways: WriterMemoryGateway): FileEndpointResolver = FileEndpointResolver().apply {
        gateways.groupBy { gateway -> gateway.endpoint.protocol }.forEach { (protocol, candidates) ->
            register(protocol) { sourceId -> candidates.firstOrNull { gateway -> gateway.endpoint.sourceId == sourceId } }
        }
    }

    private fun <T> assertEndpointError(result: Result<T>, code: FileEndpointErrorCode) {
        val error = result.exceptionOrNull() as? FileEndpointException
        assertEquals(code, error?.code, error?.message)
    }
}

private class WriterMemoryGateway(
    override val endpoint: FileEndpointRef,
    writable: Boolean = true,
) : FileEndpointGateway {
    override val pathSeparator: String = "/"
    override val permissions = FileEndpointPermissions(
        read = true,
        write = writable,
        rename = writable,
        delete = writable,
        share = true,
    )

    private val files = mutableMapOf<String, ByteArray>()
    private val directories = mutableSetOf("/")
    private val updatedAt = mutableMapOf<String, Long>()
    private var clock = 1L
    var writeCalls = 0
        private set
    var createDirectoryCalls = 0
        private set
    var abortCalls = 0
        private set
    var failWritesWith: Throwable? = null
    var failAbortWith: Throwable? = null

    fun putFile(path: String, bytes: ByteArray, updatedAt: Long = clock++) {
        files[path] = bytes
        this.updatedAt[path] = updatedAt
    }

    fun putDirectory(path: String) {
        directories += path
        updatedAt[path] = clock++
    }

    fun fileBytes(path: String): ByteArray? = files[path]

    override suspend fun list(path: String): Result<List<FileEndpointEntry>> = Result.failure(UnsupportedOperationException())

    override suspend fun info(path: String): Result<FileEndpointEntry> = runCatching {
        when {
            path in directories -> entry(path, isDirectory = true, size = 0L)
            path in files -> entry(path, isDirectory = false, size = files.getValue(path).size.toLong())
            else -> throw FileEndpointException(FileEndpointErrorCode.NotFound, "not found")
        }
    }

    override suspend fun readRange(path: String, offset: Long, length: Int): Result<FileRangeResult> =
        Result.failure(UnsupportedOperationException())

    override suspend fun rename(path: String, newName: String): Result<FileEndpointEntry> =
        Result.failure(UnsupportedOperationException())

    override suspend fun createDirectory(path: String): Result<FileEndpointEntry> = runCatching {
        createDirectoryCalls++
        if (path in files) throw FileEndpointException(FileEndpointErrorCode.Conflict, "file exists")
        directories += path
        updatedAt[path] = clock++
        entry(path, isDirectory = true, size = 0L)
    }

    override suspend fun createFile(path: String): Result<FileEndpointEntry> = runCatching {
        if (path in directories) throw FileEndpointException(FileEndpointErrorCode.Conflict, "directory exists")
        files[path] = byteArrayOf()
        updatedAt[path] = clock++
        entry(path, isDirectory = false, size = 0L)
    }

    override suspend fun delete(path: String): Result<Boolean> = runCatching {
        files.remove(path) != null || directories.remove(path)
    }

    override suspend fun writeRange(
        path: String,
        fileSize: Long,
        offset: Long,
        bytes: ByteArray,
    ): Result<Boolean> = runCatching {
        writeCalls++
        failWritesWith?.let { error -> throw error }
        if (path in directories) throw FileEndpointException(FileEndpointErrorCode.InvalidArgument, "directory")
        val old = files[path] ?: byteArrayOf()
        val target = ByteArray(fileSize.toInt())
        old.copyInto(target, endIndex = minOf(old.size, target.size))
        bytes.copyInto(target, destinationOffset = offset.toInt())
        files[path] = target
        updatedAt[path] = clock++
        true
    }

    override suspend fun abortWrite(path: String): Result<Boolean> = runCatching {
        abortCalls++
        failAbortWith?.let { error -> throw error }
        delete(path).getOrThrow()
    }

    private fun entry(path: String, isDirectory: Boolean, size: Long): FileEndpointEntry = FileEndpointEntry(
        name = path.substringAfterLast('/'),
        path = path,
        isDirectory = isDirectory,
        mimeType = "",
        size = size,
        createdAt = 0L,
        updatedAt = updatedAt[path] ?: 0L,
        endpoint = endpoint,
        permissions = permissions,
        isHidden = false,
        isSymbolicLink = false,
        isSymbolicLinkKnown = true,
    )
}
