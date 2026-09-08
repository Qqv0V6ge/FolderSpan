package com.folderspan.service.data

import com.folderspan.service.account.AccountDeviceProof
import com.folderspan.service.http.tls.DeviceIdentityProof

import com.folderspan.data.main.device.DeviceConnectType
import com.folderspan.ui.state.file.DrawerBookmarkType
import com.folderspan.ui.state.file.FileShareStatus
import strings.AppStrings
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.protobuf.ProtoNumber

const val WRITE_BYTES_STREAM_REQUEST_HEADER = "X-FolderSpan-Write-Bytes-Request"
const val DISCOVERY_PING_HEADER = "X-FolderSpan-Discovery"

@OptIn(ExperimentalSerializationApi::class)
@Serializable
data class EmptyRequest(
    @ProtoNumber(1) val nonce: String = ""
)

@OptIn(ExperimentalSerializationApi::class)
@Serializable
data class RenameRequest(
    @ProtoNumber(1) val renameInfos: List<RenameInfo>
)

@OptIn(ExperimentalSerializationApi::class)
@Serializable
data class CreateFolderRequest(
    @ProtoNumber(1) val paths: List<String>
)

@OptIn(ExperimentalSerializationApi::class)
@Serializable
data class DeleteRequest(
    @ProtoNumber(1) val paths: List<String>
)

@OptIn(ExperimentalSerializationApi::class)
@Serializable
data class WriteBytesRequest(
    @ProtoNumber(1) val fileSize: Long,
    @ProtoNumber(2) val blockIndex: Long,
    @ProtoNumber(3) val blockLength: Long,
    @ProtoNumber(4) val path: String,
    @ProtoNumber(5) val byteArray: ByteArray,
    @ProtoNumber(6) val actualFileSizeText: String = "",
    @ProtoNumber(7) val blockStartOffset: Long = -1L,
) {
    fun toStreamRequest(): WriteBytesStreamRequest {
        return WriteBytesStreamRequest(
            fileSize = fileSize,
            blockIndex = blockIndex,
            blockLength = blockLength,
            path = path,
            actualFileSizeText = actualFileSizeText,
            blockStartOffset = blockStartOffset,
        )
    }

    fun resolveFileSize(): Result<Long> {
        val parsedSize = actualFileSizeText
            .takeIf { it.isNotBlank() }
            ?.toLongOrNull()
            ?: fileSize
        return if (parsedSize >= 0L) {
            Result.success(parsedSize)
        } else {
            Result.failure(IllegalArgumentException(AppStrings.ui_file_size_invalid))
        }
    }

    fun resolveBlockStartOffset(): Result<Long> {
        if (blockStartOffset < 0L) {
            return Result.failure(IllegalArgumentException(AppStrings.error_write_block_offset_invalid))
        }
        return Result.success(blockStartOffset)
    }
}

@OptIn(ExperimentalSerializationApi::class)
@Serializable
data class WriteBytesStreamRequest(
    @ProtoNumber(1) val fileSize: Long,
    @ProtoNumber(2) val blockIndex: Long,
    @ProtoNumber(3) val blockLength: Long,
    @ProtoNumber(4) val path: String,
    @ProtoNumber(6) val actualFileSizeText: String = "",
    @ProtoNumber(7) val blockStartOffset: Long = -1L,
) {
    fun resolveFileSize(): Result<Long> {
        val parsedSize = actualFileSizeText
            .takeIf { it.isNotBlank() }
            ?.toLongOrNull()
            ?: fileSize
        return if (parsedSize >= 0L) {
            Result.success(parsedSize)
        } else {
            Result.failure(IllegalArgumentException(AppStrings.ui_file_size_invalid))
        }
    }

    fun resolveBlockStartOffset(): Result<Long> {
        if (blockStartOffset < 0L) {
            return Result.failure(IllegalArgumentException(AppStrings.error_write_block_offset_invalid))
        }
        return Result.success(blockStartOffset)
    }
}

@OptIn(ExperimentalSerializationApi::class)
@Serializable
data class ReadBytesRequest(
    @ProtoNumber(1) val path: String,
    @ProtoNumber(2) val startOffset: Long,
    @ProtoNumber(3) val endOffset: Long
)

@OptIn(ExperimentalSerializationApi::class)
@Serializable
data class GetFileByPathRequest(
    @ProtoNumber(1) val path: String
)

@OptIn(ExperimentalSerializationApi::class)
@Serializable
data class ShareStreamFileRequest(
    @ProtoNumber(1) val path: String,
    @ProtoNumber(2) val startOffset: Long,
    @ProtoNumber(3) val endOffset: Long,
)

@OptIn(ExperimentalSerializationApi::class)
@Serializable
data class DeviceStreamFileRequest(
    @ProtoNumber(1) val path: String,
    @ProtoNumber(2) val startOffset: Long,
    @ProtoNumber(3) val endOffset: Long,
)

@OptIn(ExperimentalSerializationApi::class)
@Serializable
data class GetFileByPathAndNameRequest(
    @ProtoNumber(1) val path: String,
    @ProtoNumber(2) val name: String
)

@OptIn(ExperimentalSerializationApi::class)
@Serializable
data class CreateFileRequest(
    @ProtoNumber(1) val paths: List<String>
)

@OptIn(ExperimentalSerializationApi::class)
@Serializable
enum class DeviceConnectAuthorizationMode {
    UNSPECIFIED,
    STANDARD,
    ACCOUNT_DEVICE,
}

@OptIn(ExperimentalSerializationApi::class)
@Serializable
enum class DeviceSessionBootstrapAuthorizationType {
    UNSPECIFIED,
    WEB_RTC_PREAPPROVED,
}

@OptIn(ExperimentalSerializationApi::class)
@Serializable
data class DeviceSessionBootstrapAuthorization(
    @ProtoNumber(1) val type: DeviceSessionBootstrapAuthorizationType,
    @ProtoNumber(2) val opaqueAuthorization: String,
    @ProtoNumber(3) val connectionAttemptId: String,
)

@OptIn(ExperimentalSerializationApi::class)
@Serializable
data class DeviceConnectRequest(
    @ProtoNumber(1) val device: SocketDevice,
    @ProtoNumber(2) val shareNonce: String = "",
    @ProtoNumber(3) val authorizationMode: DeviceConnectAuthorizationMode,
    @ProtoNumber(4) val accountDeviceProof: AccountDeviceProof? = null,
    @ProtoNumber(5) val identityProof: DeviceIdentityProof? = null,
    @ProtoNumber(6) val bootstrapAuthorization: DeviceSessionBootstrapAuthorization? = null,
)

@OptIn(ExperimentalSerializationApi::class)
@Serializable
data class DeviceConnectResponse(
    @ProtoNumber(1) val connectType: DeviceConnectType,
    @ProtoNumber(2) val token: String,
    @ProtoNumber(3) val authorizationMode: DeviceConnectAuthorizationMode,
)

@OptIn(ExperimentalSerializationApi::class)
@Serializable
data class ShareRequestPollRequest(
    @ProtoNumber(1) val device: SocketDevice,
    @ProtoNumber(2) val checkOnly: Boolean = false,
    @ProtoNumber(3) val connectNonce: String = "",
)

@OptIn(ExperimentalSerializationApi::class)
@Serializable
data class ShareRequestPollResponse(
    @ProtoNumber(1) val status: FileShareStatus,
    @ProtoNumber(2) val message: String = ""
)

@OptIn(ExperimentalSerializationApi::class)
@Serializable
data class ListRequest(
    @ProtoNumber(1) val path: String
)

@OptIn(ExperimentalSerializationApi::class)
@Serializable
data class CopyPathRequest(
    @ProtoNumber(1) val srcPath: String,
    @ProtoNumber(2) val destPath: String,
    @ProtoNumber(3) val requestId: String = "",
)

fun resolveRequiredCopyRequestId(requestId: String?): String {
    val trimmed = requestId?.trim().orEmpty()
    if (trimmed.isNotEmpty()) return trimmed
    return "copy:${kotlin.random.Random.nextLong().toULong().toString(16)}"
}

@Serializable
enum class CopyPathControlAction {
    Pause,
    Resume,
    Cancel,
}

@OptIn(ExperimentalSerializationApi::class)
@Serializable
data class CopyPathControlRequest(
    @ProtoNumber(1) val requestId: String,
    @ProtoNumber(2) val action: CopyPathControlAction,
)

@OptIn(ExperimentalSerializationApi::class)
@Serializable
data class CopyPathProgress(
    @ProtoNumber(1) val progressCur: Long = 0,
    @ProtoNumber(2) val progressMax: Long = 0,
    @ProtoNumber(3) val path: String = "",
    @ProtoNumber(4) val message: String = "",
    @ProtoNumber(5) val done: Boolean = false,
    @ProtoNumber(6) val success: Boolean = false,
)

@OptIn(ExperimentalSerializationApi::class)
@Serializable
data class CreateBookmarkRequest(
    @ProtoNumber(1) val name: String,
    @ProtoNumber(2) val path: String,
    @ProtoNumber(3) val iconType: DrawerBookmarkType,
    @ProtoNumber(4) val iconPath: String = ""
)

@OptIn(ExperimentalSerializationApi::class)
@Serializable
data class UpdateBookmarkRequest(
    @ProtoNumber(1) val id: Long,
    @ProtoNumber(2) val name: String,
    @ProtoNumber(3) val path: String,
    @ProtoNumber(4) val iconType: DrawerBookmarkType,
    @ProtoNumber(5) val iconPath: String = ""
)

@OptIn(ExperimentalSerializationApi::class)
@Serializable
data class DeleteBookmarkRequest(
    @ProtoNumber(1) val id: Long
)

@OptIn(ExperimentalSerializationApi::class)
@Serializable
data class ReorderBookmarksRequest(
    @ProtoNumber(1) val orderedIds: List<Long>
)

@OptIn(ExperimentalSerializationApi::class)
@Serializable
data class ReadFileLinesRequest(
    @ProtoNumber(1) val path: String
)

@OptIn(ExperimentalSerializationApi::class)
@Serializable
data class AppendToFileRequest(
    @ProtoNumber(1) val path: String,
    @ProtoNumber(2) val content: String
)

@OptIn(ExperimentalSerializationApi::class)
@Serializable
data class PathExistsRequest(
    @ProtoNumber(1) val path: String
)

@OptIn(ExperimentalSerializationApi::class)
@Serializable
data class CreateDirectoryRequest(
    @ProtoNumber(1) val path: String
)

@OptIn(ExperimentalSerializationApi::class)
@Serializable
data class DeleteDirectoryRequest(
    @ProtoNumber(1) val path: String
)

@OptIn(ExperimentalSerializationApi::class)
@Serializable
data class SerializableColorScheme(
    @ProtoNumber(1) val primary: Int,
    @ProtoNumber(2) val onPrimary: Int,
    @ProtoNumber(3) val primaryContainer: Int,
    @ProtoNumber(4) val onPrimaryContainer: Int,
    @ProtoNumber(5) val secondary: Int,
    @ProtoNumber(6) val onSecondary: Int,
    @ProtoNumber(7) val secondaryContainer: Int,
    @ProtoNumber(8) val onSecondaryContainer: Int,
    @ProtoNumber(9) val tertiary: Int,
    @ProtoNumber(10) val onTertiary: Int,
    @ProtoNumber(11) val tertiaryContainer: Int,
    @ProtoNumber(12) val onTertiaryContainer: Int,
    @ProtoNumber(13) val error: Int,
    @ProtoNumber(14) val onError: Int,
    @ProtoNumber(15) val errorContainer: Int,
    @ProtoNumber(16) val onErrorContainer: Int,
    @ProtoNumber(17) val background: Int,
    @ProtoNumber(18) val onBackground: Int,
    @ProtoNumber(19) val surface: Int,
    @ProtoNumber(20) val onSurface: Int,
    @ProtoNumber(21) val surfaceVariant: Int,
    @ProtoNumber(22) val onSurfaceVariant: Int,
    @ProtoNumber(23) val outline: Int,
    @ProtoNumber(24) val outlineVariant: Int,
    @ProtoNumber(25) val scrim: Int,
    @ProtoNumber(26) val inverseSurface: Int,
    @ProtoNumber(27) val inverseOnSurface: Int,
    @ProtoNumber(28) val inversePrimary: Int,
    @ProtoNumber(29) val surfaceDim: Int,
    @ProtoNumber(30) val surfaceBright: Int,
    @ProtoNumber(31) val surfaceContainerLowest: Int,
    @ProtoNumber(32) val surfaceContainerLow: Int,
    @ProtoNumber(33) val surfaceContainer: Int,
    @ProtoNumber(34) val surfaceContainerHigh: Int,
    @ProtoNumber(35) val surfaceContainerHighest: Int
)

@OptIn(ExperimentalSerializationApi::class)
@Serializable
data class DeviceThemeRequest(
    @ProtoNumber(1) val isDark: Boolean
)

@OptIn(ExperimentalSerializationApi::class)
@Serializable
data class DeviceThemeResponse(
    @ProtoNumber(1) val colorScheme: SerializableColorScheme
)

@OptIn(ExperimentalSerializationApi::class)
@Serializable
data class DeviceHeartbeatRequest(
    @ProtoNumber(1) val clientTimeMillis: Long
)

@OptIn(ExperimentalSerializationApi::class)
@Serializable
data class DeviceHeartbeatResponse(
    @ProtoNumber(1) val serverTimeMillis: Long
)
