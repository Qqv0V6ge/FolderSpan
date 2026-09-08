package com.folderspan.service.mcp.file

import com.folderspan.cleanup.resolveDesktopApplicationDataDirectory
import com.folderspan.data.file.FileProtocol
import com.folderspan.data.file.FileSimpleInfo
import com.folderspan.data.main.network.Network
import com.folderspan.data.main.network.NetworkProtocol
import com.folderspan.exception.AuthorityException
import com.folderspan.service.mcp.automation.toMcpAutomationException
import com.folderspan.utils.FileSensitivity
import strings.AppStrings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class FileEndpointGatewayTest {
    @Test
    fun localAndStagingGatewaysAllowOrdinaryAuthorizedFileAccess() = runBlocking(Dispatchers.IO) {
        val directory = Files.createTempDirectory("mcp-file-authorized-").toFile()
        try {
            val localGateway = LocalFileEndpointGateway()
            val target = File(directory, "allowed.bin")
            val localResult = localGateway.createFile(target.absolutePath)

            val network = RecordingNetwork()
            val networkGateway = NetworkFileEndpointGateway(network)
            val firstChunk = networkGateway.writeRange(
                path = "/target.bin",
                fileSize = 6L,
                offset = 0L,
                bytes = "abc".encodeToByteArray(),
            )
            val secondChunk = networkGateway.writeRange(
                path = "/target.bin",
                fileSize = 6L,
                offset = 3L,
                bytes = "def".encodeToByteArray(),
            )

            assertTrue(localResult.isSuccess)
            assertTrue(firstChunk.getOrThrow())
            assertTrue(secondChunk.getOrThrow())
            assertTrue(target.exists())
            assertEquals(0, network.downloadCalls)
            assertEquals("abcdef", network.uploaded?.decodeToString())
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun endpointIdentityAndPathValidationAreStrict() {
        assertFailsWith<IllegalArgumentException> { FileEndpointRef(FileProtocol.Local, "device") }
        assertFailsWith<IllegalArgumentException> { FileEndpointRef(FileProtocol.Device, "") }
        assertFailsWith<FileEndpointException> { normalizeEndpointPath("../secret", "/") }
        assertFailsWith<FileEndpointException> { normalizeEndpointPath("relative/file", "/") }
        assertFailsWith<FileEndpointException> { normalizeEndpointPath("\\\\?\\C:\\data\\file.txt", "\\") }
        assertFailsWith<FileEndpointException> { normalizeEndpointPath("\\\\.\\C:\\data\\file.txt", "\\") }
        assertFailsWith<FileEndpointException> { normalizeEndpointPath("\\??\\C:\\data\\file.txt", "\\") }
        assertFailsWith<FileEndpointException> { normalizeEndpointPath("//?/C:/data/file.txt", "\\") }

        val local = LocalFileEndpointGateway()
        val resolver = FileEndpointResolver(
            mapOf(FileProtocol.Local to { sourceId -> local.takeIf { sourceId.isBlank() } })
        )
        assertEquals(local, resolver.resolve(FileEndpointRef(FileProtocol.Local)))
        assertFailsWith<FileEndpointException> {
            resolver.resolve(FileEndpointRef(FileProtocol.Device, "offline-device"))
        }
    }

    @Test
    fun deniedUtilityFailureMapsToMcpPermissionDenied() {
        val mapped = AuthorityException(AppStrings.ui_test_file_endpoint_gateway_no_permission_to_access_the_file_system).toMcpAutomationException()

        assertEquals("permission_denied", mapped.code)
        assertFalse(mapped.message.orEmpty().contains("/"))
    }

    @Test
    fun protectedEntryExposesLowercaseSensitivityAndNoCapabilities() {
        val entry = FileSimpleInfo(
            name = "folderspan.db",
            isDirectory = false,
            isHidden = false,
            path = "/application/folderspan.db",
            mineType = "application/octet-stream",
            size = 10L,
            createdDate = 1L,
            updatedDate = 2L,
            isSymbolicLink = false,
            isSymbolicLinkKnown = true,
            sensitivity = FileSensitivity.Critical,
            sensitivityCategory = "database",
        ).toEndpointEntry(
            endpoint = FileEndpointRef(FileProtocol.Local),
            permissions = FileEndpointPermissions(true, true, true, true, true),
        )

        assertEquals(FileEndpointPermissions.None, entry.permissions)
        assertEquals(FileSensitivity.Critical, entry.sensitivity)
        assertEquals("database", entry.sensitivityCategory)
        assertTrue(Json.encodeToString(entry).contains("\"sensitivity\":\"critical\""))
    }

    @Test
    fun localGatewaySupportsMetadataRangesAndMutationsForOrdinaryPaths() = runBlocking(Dispatchers.IO) {
        val directory = Files.createTempDirectory("mcp-local-gateway-").toRealPath().toFile()
        try {
            val gateway = LocalFileEndpointGateway()
            val source = File(directory, "hello.txt").apply { writeText("hello world") }
            val renamed = File(directory, "renamed.txt")
            val nested = File(directory, "nested")
            val empty = File(directory, "empty.bin")
            val upload = File(directory, "upload.bin")

            val listed = gateway.list(directory.absolutePath).getOrThrow()
            val info = gateway.info(source.absolutePath).getOrThrow()
            val range = gateway.readRange(source.absolutePath, 6L, 32).getOrThrow()
            gateway.rename(source.absolutePath, renamed.name).getOrThrow()
            gateway.createDirectory(nested.absolutePath).getOrThrow()
            gateway.createFile(empty.absolutePath).getOrThrow()
            gateway.writeRange(upload.absolutePath, 7L, 0L, "changed".encodeToByteArray()).getOrThrow()

            assertTrue(listed.any { entry -> entry.path == source.absolutePath })
            assertEquals(source.absolutePath, info.path)
            assertEquals("world", range.bytes.decodeToString())
            assertEquals(11L, range.totalSize)
            assertNotNull(gateway.availableBytes(directory.absolutePath))
            assertTrue(renamed.exists())
            assertTrue(nested.isDirectory)
            assertTrue(empty.isFile)
            assertEquals("changed", upload.readText())

            assertTrue(gateway.abortWrite(upload.absolutePath).getOrThrow())
            assertTrue(gateway.delete(renamed.absolutePath).getOrThrow())
            assertFalse(upload.exists())
            assertFalse(renamed.exists())
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun contentReaderAllowsOrdinaryLocalContentButStillEnforcesBounds() = runBlocking(Dispatchers.IO) {
        val directory = Files.createTempDirectory("mcp-file-reader-").toFile()
        try {
            val gateway = LocalFileEndpointGateway()
            val resolver = FileEndpointResolver(mapOf(FileProtocol.Local to { gateway }))
            val reader = FileContentReader(resolver)
            val text = File(directory, "text.txt").apply { writeText("abcdef") }
            val content = reader.read(
                FileLocator(gateway.endpoint, text.absolutePath),
                length = 3,
            ).getOrThrow()

            val oversized = reader.read(
                FileLocator(gateway.endpoint, text.absolutePath),
                length = MAX_FILE_RANGE_BYTES + 1,
            )
            assertEquals("abc", content.data)
            assertIs<FileEndpointException>(oversized.exceptionOrNull())
            Unit
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun contentWriterOverwritesAppendsAndClearsRealLocalFiles() = runBlocking(Dispatchers.IO) {
        val directory = Files.createTempDirectory("mcp-file-writer-").toFile()
        try {
            val gateway = LocalFileEndpointGateway()
            val resolver = FileEndpointResolver(mapOf(FileProtocol.Local to { gateway }))
            val writer = FileContentWriter(resolver)
            val target = File(directory, "notes.txt").apply { writeText("old content") }
            val locator = FileLocator(gateway.endpoint, target.absolutePath)

            val overwritten = writer.write(locator, "hello").getOrThrow()
            val appended = writer.write(locator, "!", mode = FileWriteMode.Append).getOrThrow()
            val cleared = writer.write(locator, "").getOrThrow()

            assertEquals(5L, overwritten.entry.size)
            assertEquals(6L, appended.entry.size)
            assertEquals(0L, cleared.entry.size)
            assertEquals(0L, target.length())
            assertEquals("", target.readText())
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun localGatewayRejectsProcAliasAndIntermediateSymlinkToApplicationData() = runBlocking(Dispatchers.IO) {
        val gateway = LocalFileEndpointGateway()
        val protectedFile = resolveDesktopApplicationDataDirectory().resolve("folderspan.db")
        val procRoot = java.nio.file.Path.of("/proc/self/root")
        if (Files.exists(procRoot)) {
            val viaProc = procRoot.resolve(protectedFile.toAbsolutePath().toString().removePrefix("/")).toString()
            val procResult = gateway.readRange(viaProc, 0, 64)
            val procError = assertIs<FileEndpointException>(procResult.exceptionOrNull())
            assertEquals(FileEndpointErrorCode.PermissionDenied, procError.code)
            assertFalse(procError.message.orEmpty().contains(protectedFile.toString()))
        }

        val directory = Files.createTempDirectory("mcp-sensitive-link-")
        val link = directory.resolve("app-data")
        try {
            runCatching { Files.createSymbolicLink(link, resolveDesktopApplicationDataDirectory()) }.getOrNull() ?: return@runBlocking
            val viaLink = link.resolve("folderspan.db").toString()
            val linkResult = gateway.readRange(viaLink, 0, 64)
            val linkError = assertIs<FileEndpointException>(linkResult.exceptionOrNull())
            assertEquals(FileEndpointErrorCode.PermissionDenied, linkError.code)
            assertFalse(linkError.message.orEmpty().contains("folderspan.db"))
        } finally {
            Files.deleteIfExists(link)
            directory.toFile().deleteRecursively()
        }
    }

    @Test
    fun localGatewayReadsOrdinaryFileThroughSymlinkAfterCanonicalization() = runBlocking(Dispatchers.IO) {
        val directory = Files.createTempDirectory("mcp-ordinary-link-")
        try {
            val target = Files.writeString(directory.resolve("notes.txt"), "hello")
            val link = directory.resolve("notes-link.txt")
            runCatching { Files.createSymbolicLink(link, target) }.getOrNull() ?: return@runBlocking
            val gateway = LocalFileEndpointGateway()
            val range = gateway.readRange(link.toString(), 0, 16).getOrThrow()
            assertEquals("hello", range.bytes.decodeToString())
        } finally {
            directory.toFile().deleteRecursively()
        }
    }

    @Test
    fun abortWriteRejectsSymlinkWithoutDeletingTarget() = runBlocking(Dispatchers.IO) {
        val directory = Files.createTempDirectory("mcp-abort-link-")
        try {
            val target = Files.writeString(directory.resolve("notes.txt"), "keep-me")
            val link = directory.resolve("notes-link.txt")
            runCatching { Files.createSymbolicLink(link, target) }.getOrNull() ?: return@runBlocking
            val gateway = LocalFileEndpointGateway()

            val aborted = gateway.abortWrite(link.toString())
            val error = assertIs<FileEndpointException>(aborted.exceptionOrNull())

            assertEquals(FileEndpointErrorCode.PermissionDenied, error.code)
            assertEquals(AppStrings.error_symbolic_links_not_accessible, error.message)
            assertEquals("keep-me", Files.readString(target))
            assertTrue(Files.exists(link))
        } finally {
            directory.toFile().deleteRecursively()
        }
    }

    @Test
    fun writeRangeRejectsOverflowingOffset() = runBlocking(Dispatchers.IO) {
        val directory = Files.createTempDirectory("mcp-write-overflow-").toFile()
        try {
            val gateway = LocalFileEndpointGateway()
            val target = File(directory, "data.bin")
            val overflow = gateway.writeRange(
                path = target.absolutePath,
                fileSize = 8L,
                offset = Long.MAX_VALUE - 4L,
                bytes = "xx".encodeToByteArray(),
            )
            val error = assertIs<FileEndpointException>(overflow.exceptionOrNull())
            assertEquals(FileEndpointErrorCode.InvalidArgument, error.code)
            assertEquals(AppStrings.error_write_range_invalid, error.message)
            assertFalse(target.exists())
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun localGatewayRejectsProtectedApplicationDataBeforeFileSystemAccess() = runBlocking(Dispatchers.IO) {
        val gateway = LocalFileEndpointGateway()
        val protectedPath = resolveDesktopApplicationDataDirectory().resolve("secret.db").toString()

        val result = gateway.info(protectedPath)
        val error = assertIs<FileEndpointException>(result.exceptionOrNull())

        assertEquals(FileEndpointErrorCode.PermissionDenied, error.code)
        assertTrue(error.message.orEmpty().contains("application_private_data"))
        assertFalse(error.message.orEmpty().contains(protectedPath))
        assertNull(gateway.availableBytes(protectedPath))
    }

    @Test
    fun contentWriterRejectsProtectedApplicationDataBeforeMutation() = runBlocking(Dispatchers.IO) {
        val gateway = LocalFileEndpointGateway()
        val resolver = FileEndpointResolver(mapOf(FileProtocol.Local to { gateway }))
        val writer = FileContentWriter(resolver)
        val protectedFile = resolveDesktopApplicationDataDirectory().resolve("mcp-direct-write-test.txt").toFile()

        val write = writer.write(FileLocator(gateway.endpoint, protectedFile.absolutePath), "secret")
        val directory = writer.createDirectory(FileLocator(gateway.endpoint, protectedFile.absolutePath + ".dir"))

        assertEquals(
            FileEndpointErrorCode.PermissionDenied,
            assertIs<FileEndpointException>(write.exceptionOrNull()).code,
        )
        assertEquals(
            FileEndpointErrorCode.PermissionDenied,
            assertIs<FileEndpointException>(directory.exceptionOrNull()).code,
        )
        assertFalse(protectedFile.exists())
        assertFalse(File(protectedFile.absolutePath + ".dir").exists())
    }
}

private class RecordingNetwork : Network(
    name = "recording",
    pathSeparator = "/",
    protocol = NetworkProtocol.SFTP.name,
    host = "example.test",
    username = "user",
    password = "password",
) {
    var downloadCalls = 0
    var uploaded: ByteArray? = null

    override suspend fun downloadFileToLocal(
        file: FileSimpleInfo,
        localPath: String,
        onProgress: (completedBytes: Long, totalBytes: Long) -> Unit,
    ): Result<Boolean> {
        downloadCalls++
        return Result.success(true)
    }

    override suspend fun uploadFileFromLocal(
        localPath: String,
        remotePath: String,
        size: Long,
        onProgress: (completedBytes: Long, totalBytes: Long) -> Unit,
    ): Result<Boolean> = runCatching {
        uploaded = File(localPath).readBytes()
        true
    }
}
