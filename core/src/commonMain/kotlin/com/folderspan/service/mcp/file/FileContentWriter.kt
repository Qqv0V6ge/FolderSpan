package com.folderspan.service.mcp.file

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlin.io.encoding.Base64
import strings.AppStrings

@Serializable
enum class FileWriteMode {
    @SerialName("overwrite")
    Overwrite,

    @SerialName("append")
    Append,
}

@Serializable
data class FileWriteResult(
    val entry: FileEndpointEntry,
    val bytesWritten: Int,
    val mode: FileWriteMode,
)

class FileContentWriter(
    private val resolver: FileEndpointResolver,
) {
    suspend fun write(
        locator: FileLocator,
        data: String,
        encoding: FileContentEncoding = FileContentEncoding.Utf8,
        mode: FileWriteMode = FileWriteMode.Overwrite,
        expectedSize: Long? = null,
        expectedUpdatedAt: Long? = null,
    ): Result<FileWriteResult> = runCatching {
        validatePreconditions(expectedSize, expectedUpdatedAt)
        val bytes = decode(data, encoding)
        val (gateway, path) = resolver.resolve(locator)
        requireWriteCapability(gateway)
        // Validation is intentional: the root path has no parent/leaf pair and must not be mutated.
        parentAndName(path, gateway.pathSeparator)

        val probe = probeTarget(gateway, path)
        val current = probe.entry
        if (current != null) {
            requireWritableFile(current)
        }
        requireMatchingPreconditions(current, expectedSize, expectedUpdatedAt)

        when (mode) {
            FileWriteMode.Overwrite -> overwrite(gateway, path, bytes, probe)
            FileWriteMode.Append -> append(gateway, path, bytes, current)
        }

        // Gateway writes do not return metadata. Remote backends may be eventually consistent, so a delayed
        // metadata refresh can report an operation error after the content mutation has completed.
        FileWriteResult(
            entry = gateway.info(path).getOrThrow(),
            bytesWritten = bytes.size,
            mode = mode,
        )
    }

    suspend fun createDirectory(locator: FileLocator): Result<FileEndpointEntry> = runCatching {
        val (gateway, path) = resolver.resolve(locator)
        requireWriteCapability(gateway)
        // Validation is intentional: the root path has no parent/leaf pair and must not be mutated.
        parentAndName(path, gateway.pathSeparator)

        val current = probeTarget(gateway, path).entry
        if (current != null) {
            if (current.isDirectory) {
                requireWritableEntry(current)
                return@runCatching current
            }
            throw FileEndpointException(FileEndpointErrorCode.Conflict, "target already exists as a file")
        }

        gateway.createDirectory(path).getOrElse { createError ->
            val raced = gateway.info(path).getOrNull()
            when {
                raced?.isDirectory == true -> raced
                raced != null -> throw FileEndpointException(FileEndpointErrorCode.Conflict, "target already exists as a file")
                else -> throw createError
            }
        }
    }

    private fun decode(data: String, encoding: FileContentEncoding): ByteArray {
        val bytes = when (encoding) {
            FileContentEncoding.Utf8 -> data.encodeToByteArray()
            FileContentEncoding.Base64 -> runCatching { Base64.decode(data) }.getOrElse { error ->
                throw FileEndpointException(FileEndpointErrorCode.InvalidArgument, "data is not valid base64", error)
            }
        }
        if (bytes.size > MAX_FILE_WRITE_BYTES) {
            throw FileEndpointException(
                FileEndpointErrorCode.InvalidArgument,
                "decoded data exceeds the $MAX_FILE_WRITE_BYTES byte limit",
            )
        }
        return bytes
    }

    private fun validatePreconditions(expectedSize: Long?, expectedUpdatedAt: Long?) {
        if (expectedSize != null && expectedSize < 0L) {
            throw FileEndpointException(FileEndpointErrorCode.InvalidArgument, "expectedSize must not be negative")
        }
        if (expectedUpdatedAt != null && expectedUpdatedAt < 0L) {
            throw FileEndpointException(FileEndpointErrorCode.InvalidArgument, "expectedUpdatedAt must not be negative")
        }
    }

    private fun requireMatchingPreconditions(
        current: FileEndpointEntry?,
        expectedSize: Long?,
        expectedUpdatedAt: Long?,
    ) {
        if (expectedSize == null && expectedUpdatedAt == null) return
        if (current == null || expectedSize != null && current.size != expectedSize ||
            expectedUpdatedAt != null && current.updatedAt != expectedUpdatedAt
        ) {
            throw FileEndpointException(FileEndpointErrorCode.Conflict, "file metadata no longer matches the write preconditions")
        }
    }

    private suspend fun overwrite(
        gateway: FileEndpointGateway,
        path: String,
        bytes: ByteArray,
        probe: TargetProbe,
    ) {
        if (bytes.isEmpty() && probe.entry == null) {
            gateway.createFile(path).getOrThrow()
            return
        }
        writeRange(
            gateway = gateway,
            path = path,
            fileSize = bytes.size.toLong(),
            offset = 0L,
            bytes = bytes,
            abortOnFailure = probe.definitelyMissing,
        )
    }

    private suspend fun append(
        gateway: FileEndpointGateway,
        path: String,
        bytes: ByteArray,
        current: FileEndpointEntry?,
    ) {
        val existing = current
            ?: throw FileEndpointException(FileEndpointErrorCode.NotFound, "append target was not found")
        if (bytes.isEmpty()) return
        if (existing.size > Long.MAX_VALUE - bytes.size) {
            throw FileEndpointException(FileEndpointErrorCode.InvalidArgument, "resulting file size is too large")
        }
        writeRange(
            gateway = gateway,
            path = path,
            fileSize = existing.size + bytes.size,
            offset = existing.size,
            bytes = bytes,
            abortOnFailure = false,
        )
    }

    private suspend fun writeRange(
        gateway: FileEndpointGateway,
        path: String,
        fileSize: Long,
        offset: Long,
        bytes: ByteArray,
        abortOnFailure: Boolean,
    ) {
        try {
            if (!gateway.writeRange(path, fileSize, offset, bytes).getOrThrow()) {
                throw FileEndpointException(FileEndpointErrorCode.IoError, "endpoint did not write the file content")
            }
        } catch (error: Throwable) {
            if (abortOnFailure) {
                runCatching { gateway.abortWrite(path).getOrThrow() }
            }
            throw error
        }
    }

    private suspend fun probeTarget(gateway: FileEndpointGateway, path: String): TargetProbe {
        val result = gateway.info(path)
        result.getOrNull()?.let { entry -> return TargetProbe(entry, definitelyMissing = false) }
        val error = result.exceptionOrNull()
        return when {
            error is FileEndpointException && error.code == FileEndpointErrorCode.NotFound ->
                TargetProbe(entry = null, definitelyMissing = true)
            error is NoSuchElementException -> TargetProbe(entry = null, definitelyMissing = true)
            error is FileEndpointException -> throw error
            else -> TargetProbe(entry = null, definitelyMissing = false)
        }
    }

    private fun requireWriteCapability(gateway: FileEndpointGateway) {
        if (!gateway.permissions.write) {
            throw FileEndpointException(FileEndpointErrorCode.Unsupported, "endpoint does not allow writing")
        }
    }

    private fun requireWritableFile(entry: FileEndpointEntry) {
        if (entry.isDirectory) {
            throw FileEndpointException(FileEndpointErrorCode.InvalidArgument, "directory content cannot be written")
        }
        requireWritableEntry(entry)
    }

    private fun requireWritableEntry(entry: FileEndpointEntry) {
        if (!entry.permissions.write) {
            throw FileEndpointException(FileEndpointErrorCode.PermissionDenied, "file is not writable")
        }
        if (!entry.isSymbolicLinkKnown || entry.isSymbolicLink) {
            throw FileEndpointException(FileEndpointErrorCode.PermissionDenied, AppStrings.error_symbolic_links_not_accessible)
        }
    }

    private data class TargetProbe(
        val entry: FileEndpointEntry?,
        val definitelyMissing: Boolean,
    )
}

const val MAX_FILE_WRITE_BYTES = 512 * 1024
