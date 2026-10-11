package com.folderspan.service.mcp.file

import com.folderspan.data.file.FileInfo
import com.folderspan.data.file.FileProtocol
import com.folderspan.data.file.FileSimpleInfo
import com.folderspan.data.file.PathInfo
import com.folderspan.data.main.DiskMenuPermission
import com.folderspan.utils.FileSensitivity
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.serialization.Serializable

@Serializable
data class FileEndpointRef(
    val protocol: FileProtocol,
    val sourceId: String = "",
) {
    init {
        require(protocol in SUPPORTED_PROTOCOLS) { "unsupported file endpoint protocol: $protocol" }
        require((protocol == FileProtocol.Local) == sourceId.isBlank()) {
            if (protocol == FileProtocol.Local) "Local sourceId must be empty" else "$protocol sourceId must not be empty"
        }
    }

    companion object {
        val SUPPORTED_PROTOCOLS = setOf(
            FileProtocol.Local,
            FileProtocol.Share,
            FileProtocol.Device,
            FileProtocol.Network,
        )
    }
}

@Serializable
data class FileLocator(
    val endpoint: FileEndpointRef,
    val path: String,
)

@Serializable
data class FileEndpointPermissions(
    val read: Boolean,
    val write: Boolean,
    val rename: Boolean,
    val delete: Boolean,
    val share: Boolean,
) {
    companion object {
        val None = FileEndpointPermissions(
            read = false,
            write = false,
            rename = false,
            delete = false,
            share = false,
        )

        fun from(permission: DiskMenuPermission?): FileEndpointPermissions = FileEndpointPermissions(
            read = permission?.read == true,
            write = permission?.write == true || permission?.paste == true,
            rename = permission?.rename == true,
            delete = permission?.delete == true,
            share = permission?.share == true,
        )
    }
}

@Serializable
data class FileEndpointEntry(
    val name: String,
    val path: String,
    val isDirectory: Boolean,
    val mimeType: String,
    val size: Long,
    val createdAt: Long,
    val updatedAt: Long,
    val endpoint: FileEndpointRef,
    val permissions: FileEndpointPermissions,
    val isHidden: Boolean,
    val isSymbolicLink: Boolean,
    val isSymbolicLinkKnown: Boolean,
    val sensitivity: FileSensitivity = FileSensitivity.None,
    val sensitivityCategory: String = "",
)

@Serializable
data class FileEndpointScanIssue(
    val path: String,
    val message: String,
)

@Serializable
data class FileEndpointTreeSummary(
    val totalSize: Long = 0L,
    val fileCount: Int = 0,
    val folderCount: Int = 0,
    val skippedItemCount: Int = 0,
    val scanIssues: List<FileEndpointScanIssue> = emptyList(),
)

fun FileSimpleInfo.toEndpointEntry(
    endpoint: FileEndpointRef,
    permissions: FileEndpointPermissions,
): FileEndpointEntry = FileEndpointEntry(
    name = name,
    path = path,
    isDirectory = isDirectory,
    mimeType = mineType,
    size = size,
    createdAt = createdDate,
    updatedAt = updatedDate,
    endpoint = endpoint,
    permissions = if (sensitivity == FileSensitivity.None) permissions else FileEndpointPermissions.None,
    isHidden = isHidden,
    isSymbolicLink = isSymbolicLink,
    isSymbolicLinkKnown = isSymbolicLinkKnown,
    sensitivity = sensitivity,
    sensitivityCategory = sensitivityCategory,
)

enum class FileEndpointErrorCode(val wireValue: String) {
    InvalidArgument("invalid_argument"),
    NotFound("not_found"),
    NotConnected("not_connected"),
    PermissionDenied("permission_denied"),
    Unsupported("unsupported"),
    Conflict("conflict"),
    IoError("io_error"),
    Cancelled("cancelled"),
}

class FileEndpointException(
    val code: FileEndpointErrorCode,
    message: String,
    cause: Throwable? = null,
) : Exception(message, cause)

data class FileRangeResult(
    val bytes: ByteArray,
    val totalSize: Long,
    val offset: Long,
) {
    val hasMore: Boolean get() = offset + bytes.size < totalSize
}

interface FileEndpointGateway {
    val endpoint: FileEndpointRef
    val pathSeparator: String
    val permissions: FileEndpointPermissions

    suspend fun roots(): Result<List<PathInfo>> = Result.success(listOf(PathInfo(pathSeparator, 0L, 0L)))
    suspend fun list(path: String): Result<List<FileEndpointEntry>>
    // ponytail: legacy remote APIs return a whole directory; override when the protocol offers a cursor.
    suspend fun openDirectory(path: String): FileEndpointDirectory =
        list(path).getOrThrow().asEndpointDirectory()
    suspend fun info(path: String): Result<FileEndpointEntry>
    suspend fun details(path: String): Result<FileInfo> = info(path).map { entry ->
        FileInfo.pathFileInfo(entry.path).copy(
            name = entry.name, isDirectory = entry.isDirectory, isHidden = entry.isHidden,
            mineType = entry.mimeType, size = entry.size, createdDate = entry.createdAt,
            updatedDate = entry.updatedAt, protocol = entry.endpoint.protocol, protocolId = entry.endpoint.sourceId,
        )
    }
    suspend fun readRange(path: String, offset: Long, length: Int): Result<FileRangeResult>
    suspend fun rename(path: String, newName: String): Result<FileEndpointEntry>
    suspend fun createDirectory(path: String): Result<FileEndpointEntry>
    suspend fun createFile(path: String): Result<FileEndpointEntry>
    suspend fun delete(path: String): Result<Boolean>
    suspend fun writeRange(path: String, fileSize: Long, offset: Long, bytes: ByteArray): Result<Boolean>
    suspend fun abortWrite(path: String): Result<Boolean> = delete(path)
    suspend fun availableBytes(path: String): Long? = null
}

/** A request-owned directory cursor. null means EOF; an entry failure can be skipped. */
interface FileEndpointDirectory : AutoCloseable {
    suspend fun next(): Result<FileEndpointEntry>?
}

internal suspend fun FileEndpointDirectory.nextEntry(): FileEndpointEntry? {
    while (true) {
        currentCoroutineContext().ensureActive()
        val result = next() ?: return null
        result.exceptionOrNull()?.let { if (it is CancellationException) throw it }
        result.getOrNull()?.let { return it }
    }
}

internal fun List<FileEndpointEntry>.asEndpointDirectory(): FileEndpointDirectory {
    val entries = iterator()
    return object : FileEndpointDirectory {
        override suspend fun next() = if (entries.hasNext()) Result.success(entries.next()) else null
        override fun close() = Unit
    }
}

fun FileEndpointEntry.toFileSimpleInfo(): FileSimpleInfo = FileSimpleInfo(
    name = name, path = path, isDirectory = isDirectory, isHidden = isHidden,
    mineType = mimeType, size = size, createdDate = createdAt, updatedDate = updatedAt,
    protocol = endpoint.protocol, protocolId = endpoint.sourceId,
    isSymbolicLink = isSymbolicLink, isSymbolicLinkKnown = isSymbolicLinkKnown,
    sensitivity = sensitivity, sensitivityCategory = sensitivityCategory,
)

class FileEndpointResolver(
    providers: Map<FileProtocol, (String) -> FileEndpointGateway?> = emptyMap(),
) {
    private val providers = providers.toMutableMap()

    fun register(protocol: FileProtocol, provider: (String) -> FileEndpointGateway?) {
        require(protocol in FileEndpointRef.SUPPORTED_PROTOCOLS) { "unsupported endpoint protocol: $protocol" }
        providers[protocol] = provider
    }

    fun resolve(endpoint: FileEndpointRef): FileEndpointGateway {
        val gateway = providers[endpoint.protocol]?.invoke(endpoint.sourceId)
            ?: throw FileEndpointException(
                if (endpoint.protocol == FileProtocol.Local) FileEndpointErrorCode.NotFound else FileEndpointErrorCode.NotConnected,
                "file endpoint is unavailable",
            )
        if (gateway.endpoint != endpoint) {
            throw FileEndpointException(FileEndpointErrorCode.NotFound, "file endpoint identity mismatch")
        }
        return gateway
    }

    fun resolve(locator: FileLocator): Pair<FileEndpointGateway, String> {
        val gateway = resolve(locator.endpoint)
        return gateway to normalizeEndpointPath(locator.path, gateway.pathSeparator)
    }
}

fun normalizeEndpointPath(path: String, separator: String): String {
    // Whitespace can be part of a filename. Never retarget an operation by trimming it.
    val value = path
    if (value.isEmpty() || value.contains('\u0000')) {
        throw FileEndpointException(FileEndpointErrorCode.InvalidArgument, "path is invalid")
    }
    val normalizedSeparator = separator.ifBlank { "/" }
    val isAbsolute = value.startsWith(normalizedSeparator) ||
        value.startsWith("content://") ||
        (value.length >= 3 && value[1] == ':' && (value[2] == '/' || value[2] == '\\'))
    if (!isAbsolute) throw FileEndpointException(FileEndpointErrorCode.InvalidArgument, "path must be absolute")
    val comparable = value.replace('\\', '/')
    if (comparable.startsWith("//?/") || comparable.startsWith("//./") || comparable.startsWith("/??/")) {
        throw FileEndpointException(FileEndpointErrorCode.InvalidArgument, "verbatim path prefixes are not allowed")
    }
    if (comparable.split('/').any { it == "." || it == ".." }) {
        throw FileEndpointException(FileEndpointErrorCode.InvalidArgument, "path traversal is not allowed")
    }
    if (value == normalizedSeparator || value.endsWith(":$normalizedSeparator")) return value
    return value.trimEnd('/', '\\')
}

fun validateEndpointLeafName(name: String) {
    if (name.isBlank() || name == "." || name == ".." || name.contains('/') || name.contains('\\') || name.contains('\u0000')) {
        throw FileEndpointException(FileEndpointErrorCode.InvalidArgument, "file name is invalid")
    }
}

internal fun parentAndName(path: String, separator: String): Pair<String, String> {
    val normalized = normalizeEndpointPath(path, separator)
    val index = normalized.lastIndexOfAny(charArrayOf('/', '\\'))
    if (index < 0 || index == normalized.lastIndex) {
        throw FileEndpointException(FileEndpointErrorCode.InvalidArgument, "endpoint root cannot be mutated")
    }
    val parent = normalized.substring(0, index).ifBlank { separator }
    return parent to normalized.substring(index + 1)
}
