package com.folderspan.service.session

import com.folderspan.service.data.CopyPathProgress
import com.folderspan.service.data.DeviceConnectResponse
import com.folderspan.service.data.SerializableResult
import com.folderspan.service.data.toResult
import com.folderspan.service.data.toSerializableResult
import com.folderspan.service.http.archive.FolderSpanArchiveCodec
import com.folderspan.service.http.archive.FolderSpanArchiveReadRequest
import com.folderspan.service.http.archive.FolderSpanArchiveStreamCapabilities
import com.folderspan.service.http.archive.FolderSpanArchiveTransferResult
import com.folderspan.service.message.DeviceMessageReceipt
import com.folderspan.ui.state.device.DeviceSharePathScope
import com.folderspan.utils.ProtoBufCodec
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.protobuf.ProtoNumber

internal const val DEVICE_SESSION_RPC_CONNECT = "Connect"
internal const val DEVICE_SESSION_RPC_IDENTIFY = "Identify"
internal const val DEVICE_SESSION_RPC_THEME = "Theme"
internal const val DEVICE_SESSION_RPC_LIST_PATH = "ListPath"
internal const val DEVICE_SESSION_RPC_ROOT_PATHS = "RootPaths"
internal const val DEVICE_SESSION_RPC_PATH_EXISTS = "PathExists"
internal const val DEVICE_SESSION_RPC_CREATE_DIRECTORY = "CreateDirectory"
internal const val DEVICE_SESSION_RPC_DELETE_DIRECTORY = "DeleteDirectory"
internal const val DEVICE_SESSION_RPC_CREATE_FOLDERS = "CreateFolders"
internal const val DEVICE_SESSION_RPC_CREATE_FILES = "CreateFiles"
internal const val DEVICE_SESSION_RPC_RENAME = "Rename"
internal const val DEVICE_SESSION_RPC_DELETE = "Delete"
internal const val DEVICE_SESSION_RPC_COPY_PATH = "CopyPath"
internal const val DEVICE_SESSION_RPC_COPY_CONTROL = "CopyControl"
internal const val DEVICE_SESSION_RPC_COPY_PROGRESS = "CopyProgress"
internal const val DEVICE_SESSION_RPC_GET_FILE_BY_PATH = "GetFileByPath"
internal const val DEVICE_SESSION_RPC_GET_FILE_INFO_BY_PATH = "GetFileInfoByPath"
internal const val DEVICE_SESSION_RPC_GET_FILE_BY_PATH_AND_NAME = "GetFileByPathAndName"
internal const val DEVICE_SESSION_RPC_GET_FILE_INFO_BY_PATH_AND_NAME = "GetFileInfoByPathAndName"
internal const val DEVICE_SESSION_RPC_READ_FILE_LINES = "ReadFileLines"
internal const val DEVICE_SESSION_RPC_APPEND_TO_FILE = "AppendToFile"
internal const val DEVICE_SESSION_RPC_BOOKMARK_LIST = "BookmarkList"
internal const val DEVICE_SESSION_RPC_BOOKMARK_CREATE = "BookmarkCreate"
internal const val DEVICE_SESSION_RPC_BOOKMARK_UPDATE = "BookmarkUpdate"
internal const val DEVICE_SESSION_RPC_BOOKMARK_DELETE = "BookmarkDelete"
internal const val DEVICE_SESSION_RPC_BOOKMARK_REORDER = "BookmarkReorder"
internal const val DEVICE_SESSION_RPC_CREATE_EMPTY_DIRECTORIES = "CreateEmptyDirectories"
internal const val DEVICE_SESSION_RPC_BEGIN_MESSAGE = "BeginMessage"
internal const val DEVICE_SESSION_RPC_COMMIT_MESSAGE = "CommitMessage"

internal const val DEVICE_SESSION_STREAM_WRITE = "Write"
internal const val DEVICE_SESSION_STREAM_READ = "Read"
internal const val DEVICE_SESSION_STREAM_MANIFEST = "Manifest"
internal const val DEVICE_SESSION_STREAM_MESSAGE_BODY = "MessageBody"
internal const val DEVICE_SESSION_STREAM_ARCHIVE_READ = "ArchiveRead"
internal const val DEVICE_SESSION_STREAM_ARCHIVE_WRITE = "ArchiveWrite"

internal const val DEVICE_SESSION_CONTROL_REQUEST_PREFIX: Byte = 1
internal const val DEVICE_SESSION_CONTROL_RESPONSE_PREFIX: Byte = 2

internal const val DEVICE_SESSION_RPC_OK = 0
internal const val DEVICE_SESSION_RPC_UNAUTHORIZED = 1
internal const val DEVICE_SESSION_RPC_FORBIDDEN = 2
internal const val DEVICE_SESSION_RPC_BAD_REQUEST = 3
internal const val DEVICE_SESSION_RPC_NOT_FOUND = 4
internal const val DEVICE_SESSION_RPC_INTERNAL = 5
internal const val DEVICE_SESSION_RPC_RATE_LIMITED = 6
internal const val DEVICE_SESSION_RPC_CONFLICT = 7

internal fun isDeviceSessionRpcAllowedBeforeAuthorization(method: String): Boolean {
    return method == DEVICE_SESSION_RPC_CONNECT || method == DEVICE_SESSION_RPC_IDENTIFY
}

internal fun isDeviceShareSessionRpcAllowed(method: String): Boolean {
    return method == DEVICE_SESSION_RPC_ROOT_PATHS || method == DEVICE_SESSION_RPC_LIST_PATH
}

internal fun isDeviceShareSessionStreamAllowed(kind: String): Boolean {
    return kind == DEVICE_SESSION_STREAM_READ || kind == DEVICE_SESSION_STREAM_ARCHIVE_READ
}

internal fun resolveDeviceShareSessionStreamOpen(
    open: DeviceSessionStreamOpen,
    shareScope: DeviceSharePathScope,
): DeviceSessionStreamOpen? {
    if (!isDeviceShareSessionStreamAllowed(open.kind)) return null
    return when (open.kind) {
        DEVICE_SESSION_STREAM_READ -> {
            shareScope.resolveVirtualContentPath(open.path)?.let { physicalPath ->
                open.copy(path = physicalPath)
            }
        }
        DEVICE_SESSION_STREAM_ARCHIVE_READ -> {
            val request = runCatching {
                ProtoBufCodec.decode<FolderSpanArchiveReadRequest>(open.payload).also { decoded ->
                    decoded.options.validated()
                    FolderSpanArchiveCodec.validateEntryRequests(decoded.entries)
                }
            }.getOrNull() ?: return null
            val resolvedEntries = request.entries.map { entry ->
                val physicalPath = shareScope.resolveVirtualContentPath(entry.sourcePath) ?: return null
                entry.copy(sourcePath = physicalPath)
            }
            val resolvedPayload = runCatching {
                ProtoBufCodec.encode(request.copy(entries = resolvedEntries))
            }.getOrNull() ?: return null
            open.copy(payload = resolvedPayload)
        }
        else -> null
    }
}

internal fun Result<List<Result<Boolean>>>.toSerializableBooleanBatchResult(): Result<List<SerializableResult>> {
    return mapCatching { results ->
        results.map { result -> result.toSerializableResult() }
    }
}

internal fun Result<List<SerializableResult>>.toBooleanBatchResult(): Result<List<Result<Boolean>>> {
    return mapCatching { results ->
        results.map { result -> result.toResult<Boolean>() }
    }
}

@OptIn(ExperimentalSerializationApi::class)
@Serializable
internal data class DeviceSessionConnectResponse(
    @ProtoNumber(1) val connection: DeviceConnectResponse,
    @ProtoNumber(2) val maxFileStreams: Int,
    @ProtoNumber(3) val recommendedChunkBytes: Int,
    @ProtoNumber(4) val archiveCapabilities: FolderSpanArchiveStreamCapabilities,
) {
    init {
        require(maxFileStreams > 0) { "maxFileStreams must be positive" }
        require(recommendedChunkBytes >= DEVICE_SESSION_MAX_PAYLOAD_BYTES) {
            "recommendedChunkBytes must fit one session frame"
        }
    }
}

@OptIn(ExperimentalSerializationApi::class)
@Serializable
internal data class DeviceSessionCopyProgressEvent(
    @ProtoNumber(1) val requestId: String,
    @ProtoNumber(2) val progress: CopyPathProgress,
)

@OptIn(ExperimentalSerializationApi::class)
@Serializable
internal data class DeviceSessionArchiveTrailer(
    @ProtoNumber(1) val result: FolderSpanArchiveTransferResult,
    @ProtoNumber(2) val errorMessage: String = "",
)

@OptIn(ExperimentalSerializationApi::class)
@Serializable
internal data class DeviceSessionControlRequest(
    @ProtoNumber(1) val requestId: Int,
    @ProtoNumber(2) val method: String,
    @ProtoNumber(3) val payload: ByteArray = ByteArray(0),
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is DeviceSessionControlRequest) return false
        return requestId == other.requestId && method == other.method && payload.contentEquals(other.payload)
    }

    override fun hashCode(): Int {
        var result = requestId
        result = 31 * result + method.hashCode()
        result = 31 * result + payload.contentHashCode()
        return result
    }
}

@OptIn(ExperimentalSerializationApi::class)
@Serializable
internal data class DeviceSessionControlResponse(
    @ProtoNumber(1) val requestId: Int,
    @ProtoNumber(2) val status: Int,
    @ProtoNumber(3) val payload: ByteArray = ByteArray(0),
    @ProtoNumber(4) val errorMessage: String = "",
) {
    val isSuccess: Boolean
        get() = status == DEVICE_SESSION_RPC_OK

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is DeviceSessionControlResponse) return false
        return requestId == other.requestId &&
            status == other.status &&
            payload.contentEquals(other.payload) &&
            errorMessage == other.errorMessage
    }

    override fun hashCode(): Int {
        var result = requestId
        result = 31 * result + status
        result = 31 * result + payload.contentHashCode()
        result = 31 * result + errorMessage.hashCode()
        return result
    }
}

@OptIn(ExperimentalSerializationApi::class)
@Serializable
internal data class DeviceSessionStreamOpen(
    @ProtoNumber(1) val kind: String,
    @ProtoNumber(2) val path: String = "",
    @ProtoNumber(3) val fileSize: Long = 0L,
    @ProtoNumber(4) val startOffset: Long = 0L,
    @ProtoNumber(5) val endOffset: Long = 0L,
    @ProtoNumber(6) val payload: ByteArray = ByteArray(0),
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is DeviceSessionStreamOpen) return false
        return kind == other.kind &&
            path == other.path &&
            fileSize == other.fileSize &&
            startOffset == other.startOffset &&
            endOffset == other.endOffset &&
            payload.contentEquals(other.payload)
    }

    override fun hashCode(): Int {
        var result = kind.hashCode()
        result = 31 * result + path.hashCode()
        result = 31 * result + fileSize.hashCode()
        result = 31 * result + startOffset.hashCode()
        result = 31 * result + endOffset.hashCode()
        result = 31 * result + payload.contentHashCode()
        return result
    }
}

@OptIn(ExperimentalSerializationApi::class)
@Serializable
internal data class DeviceSessionManifestEntry(
    @ProtoNumber(1) val relativePath: String,
    @ProtoNumber(2) val size: Long = 0L,
    @ProtoNumber(3) val directory: Boolean = false,
    @ProtoNumber(4) val symbolicLink: Boolean = false,
)

@OptIn(ExperimentalSerializationApi::class)
@Serializable
internal data class DeviceSessionDirectoryManifest(
    @ProtoNumber(1) val entries: List<DeviceSessionManifestEntry> = emptyList(),
)

@OptIn(ExperimentalSerializationApi::class)
@Serializable
internal data class DeviceSessionMessageBeginResponse(
    @ProtoNumber(1) val ready: Boolean,
    @ProtoNumber(2) val receipt: DeviceMessageReceipt? = null,
)

@OptIn(ExperimentalSerializationApi::class)
@Serializable
internal data class DeviceSessionMessageCommitRequest(
    @ProtoNumber(1) val messageId: String,
)

@OptIn(ExperimentalSerializationApi::class)
@Serializable
internal data class DeviceSessionMessageBodyOpen(
    @ProtoNumber(1) val messageId: String,
)

internal const val DEVICE_SESSION_CONTROL_MAX_MESSAGE_BYTES = 16 * 1024 * 1024

internal object DeviceSessionProtocol {
    fun encodeControlRequest(request: DeviceSessionControlRequest): ByteArray {
        return prefixed(DEVICE_SESSION_CONTROL_REQUEST_PREFIX, ProtoBufCodec.encode(request))
    }

    fun encodeControlResponse(response: DeviceSessionControlResponse): ByteArray {
        return prefixed(DEVICE_SESSION_CONTROL_RESPONSE_PREFIX, ProtoBufCodec.encode(response))
    }

    fun envelopeControl(payload: ByteArray): ByteArray {
        require(payload.size <= DEVICE_SESSION_CONTROL_MAX_MESSAGE_BYTES) {
            "control payload exceeds $DEVICE_SESSION_CONTROL_MAX_MESSAGE_BYTES bytes"
        }
        val encoded = ByteArray(4 + payload.size)
        DeviceSessionFrames.writeIntLe(encoded, 0, payload.size)
        payload.copyInto(encoded, destinationOffset = 4)
        return encoded
    }

    fun decodeControlMessage(payload: ByteArray): Any? {
        if (payload.isEmpty()) return null
        val body = payload.copyOfRange(1, payload.size)
        return when (payload[0]) {
            DEVICE_SESSION_CONTROL_REQUEST_PREFIX -> ProtoBufCodec.decode<DeviceSessionControlRequest>(body)
            DEVICE_SESSION_CONTROL_RESPONSE_PREFIX -> ProtoBufCodec.decode<DeviceSessionControlResponse>(body)
            else -> null
        }
    }

    fun encodeStreamOpen(open: DeviceSessionStreamOpen): ByteArray = ProtoBufCodec.encode(open)

    fun decodeStreamOpen(payload: ByteArray): DeviceSessionStreamOpen = ProtoBufCodec.decode(payload)

    private fun prefixed(prefix: Byte, body: ByteArray): ByteArray {
        val encoded = ByteArray(body.size + 1)
        encoded[0] = prefix
        body.copyInto(encoded, destinationOffset = 1)
        return encoded
    }
}

internal class DeviceSessionControlAssembler(
    private val maxMessageBytes: Int = DEVICE_SESSION_CONTROL_MAX_MESSAGE_BYTES,
) {
    private var buffer = ByteArray(0)

    fun reset() {
        buffer = ByteArray(0)
    }

    fun push(chunk: ByteArray): List<ByteArray> {
        if (chunk.isEmpty()) return emptyList()
        if (buffer.size + chunk.size > maxMessageBytes + 4) {
            reset()
            throw DeviceSessionIoException("control assembler overflow")
        }
        val merged = ByteArray(buffer.size + chunk.size)
        buffer.copyInto(merged)
        chunk.copyInto(merged, destinationOffset = buffer.size)
        buffer = merged
        val messages = ArrayList<ByteArray>()
        var offset = 0
        while (buffer.size - offset >= 4) {
            val length = DeviceSessionFrames.readIntLe(buffer, offset)
            if (length < 0 || length > maxMessageBytes) {
                reset()
                throw DeviceSessionIoException("invalid control payload length: $length")
            }
            if (buffer.size - offset < 4 + length) break
            messages += buffer.copyOfRange(offset + 4, offset + 4 + length)
            offset += 4 + length
        }
        buffer = if (offset == 0) {
            buffer
        } else if (offset == buffer.size) {
            ByteArray(0)
        } else {
            buffer.copyOfRange(offset, buffer.size)
        }
        return messages
    }
}
