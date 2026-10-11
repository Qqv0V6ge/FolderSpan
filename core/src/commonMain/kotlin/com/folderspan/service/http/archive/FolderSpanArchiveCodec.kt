package com.folderspan.service.http.archive

import com.folderspan.utils.PathUtils
import com.folderspan.utils.ProtoBufCodec
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.protobuf.ProtoNumber
import strings.AppStrings

internal const val FOLDER_SPAN_ARCHIVE_SMALL_FILE_THRESHOLD_BYTES = 1024 * 1024L
internal const val FOLDER_SPAN_ARCHIVE_TARGET_BATCH_ENTRIES = 256
internal const val FOLDER_SPAN_ARCHIVE_TARGET_BATCH_PAYLOAD_BYTES = 32L * 1024L * 1024L
internal const val FOLDER_SPAN_ARCHIVE_MAX_ENTRIES = 512
internal const val FOLDER_SPAN_ARCHIVE_MAX_PAYLOAD_BYTES = 32L * 1024L * 1024L
internal const val FOLDER_SPAN_ARCHIVE_BUFFER_BYTES = 256 * 1024
internal const val FOLDER_SPAN_ARCHIVE_MAX_FRAME_BYTES =
    FOLDER_SPAN_ARCHIVE_MAX_PAYLOAD_BYTES +
        FOLDER_SPAN_ARCHIVE_MAX_ENTRIES.toLong() * (FOLDER_SPAN_ARCHIVE_BUFFER_BYTES + 4L) +
        4L
internal const val FOLDER_SPAN_ARCHIVE_VERSION = 2

internal object FolderSpanArchiveEntryKind {
    const val File: Int = 1
    const val Directory: Int = 2
}

@OptIn(ExperimentalSerializationApi::class)
@Serializable
data class FolderSpanArchiveEntryRequest(
    @ProtoNumber(1) val sourcePath: String,
    @ProtoNumber(2) val relativePath: String,
    @ProtoNumber(3) val size: Long = 0L,
    @ProtoNumber(4) val directory: Boolean = false,
    @ProtoNumber(5) val hidden: Boolean = false,
    @ProtoNumber(6) val mimeType: String = "",
    @ProtoNumber(7) val modifiedTimeMillis: Long = 0L,
)

@OptIn(ExperimentalSerializationApi::class)
@Serializable
internal data class FolderSpanArchiveEntryHeader(
    @ProtoNumber(1) val relativePath: String,
    @ProtoNumber(2) val kind: Int,
    @ProtoNumber(3) val size: Long,
    @ProtoNumber(4) val modifiedTimeMillis: Long = 0L,
    @ProtoNumber(5) val hidden: Boolean = false,
    @ProtoNumber(6) val mimeType: String = "",
)

internal object FolderSpanArchiveCodec {
    val MAGIC_PREFIX: ByteArray = byteArrayOf('F'.code.toByte(), 'S'.code.toByte(), 'A'.code.toByte(), 'R'.code.toByte())
    val MAGIC: ByteArray = MAGIC_PREFIX + FOLDER_SPAN_ARCHIVE_VERSION.toByte()

    fun normalizeRelativePath(path: String): String {
        require(!path.contains('\\')) { AppStrings.ui_archive_relative_path_cannot_contain_backslash }
        val normalized = path.trim().trim('/')
        require(normalized.isNotBlank()) { AppStrings.ui_archive_relative_path_cannot_be_empty }
        require(!path.startsWith("/") && !path.startsWith("\\")) { AppStrings.ui_archive_relative_path_cannot_be_absolute_path }
        val parts = normalized.split('/').filter { item -> item.isNotBlank() }
        require(parts.none { item -> item == "." || item == ".." }) { AppStrings.ui_archive_relative_path_cannot_contain_parent_directory }
        require(parts.joinToString("/") == normalized) { AppStrings.ui_archive_invalid_relative_path }
        return normalized
    }

    fun buildTargetPath(
        rootPath: String,
        relativePath: String,
        separator: String = PathUtils.getPathSeparator().ifBlank { "/" },
    ): String {
        val normalizedRelative = normalizeRelativePath(relativePath)
        val trimmedRoot = rootPath.trim()
        val normalizedRoot = trimmedRoot.trimEnd('/', '\\')
        val platformRelativePath = normalizedRelative.replace("/", separator)
        return when {
            normalizedRoot.isNotBlank() -> "$normalizedRoot$separator$platformRelativePath"
            trimmedRoot.any { char -> char == '/' || char == '\\' } -> "$separator$platformRelativePath"
            else -> platformRelativePath
        }
    }

    fun validateHeader(header: FolderSpanArchiveEntryHeader) {
        normalizeRelativePath(header.relativePath)
        when (header.kind) {
            FolderSpanArchiveEntryKind.File -> {
                require(header.size >= 0L) { AppStrings.ui_archive_file_length_invalid }
                require(header.size <= FOLDER_SPAN_ARCHIVE_MAX_PAYLOAD_BYTES) { AppStrings.ui_archive_file_length_exceeds_the_limit }
            }

            FolderSpanArchiveEntryKind.Directory -> {
                require(header.size == 0L) { AppStrings.ui_archive_directory_length_must_be_0 }
            }

            else -> throw IllegalArgumentException(AppStrings.ui_archive_entry_type_invalid)
        }
    }

    fun encodeHeader(header: FolderSpanArchiveEntryHeader): ByteArray {
        validateHeader(header)
        return ProtoBufCodec.encode(header.copy(relativePath = normalizeRelativePath(header.relativePath)))
    }

    fun decodeHeader(bytes: ByteArray): FolderSpanArchiveEntryHeader {
        val header = ProtoBufCodec.decode<FolderSpanArchiveEntryHeader>(bytes)
        validateHeader(header)
        return header.copy(relativePath = normalizeRelativePath(header.relativePath))
    }

    fun encodeStreamHeader(header: FolderSpanArchiveStreamHeader): ByteArray {
        validateStreamHeader(header)
        return ProtoBufCodec.encode(header)
    }

    fun decodeStreamHeader(bytes: ByteArray): FolderSpanArchiveStreamHeader {
        val header = ProtoBufCodec.decode<FolderSpanArchiveStreamHeader>(bytes)
        validateStreamHeader(header)
        return header
    }

    fun encodeStreamPrelude(header: FolderSpanArchiveStreamHeader): ByteArray {
        val headerBytes = encodeStreamHeader(header)
        return MAGIC + headerLengthBytes(headerBytes.size) + headerBytes
    }

    fun validateStreamHeader(header: FolderSpanArchiveStreamHeader) {
        FolderSpanArchiveCompressionCodec.requireKnown(header.codec)
        require(header.entryCount in 1..FOLDER_SPAN_ARCHIVE_MAX_ENTRIES) {
            AppStrings.ui_number_of_entries_exceeds_the_limit
        }
        require(header.declaredFileBytes in 0..FOLDER_SPAN_ARCHIVE_MAX_PAYLOAD_BYTES) {
            AppStrings.ui_archive_total_exceeds_limit
        }
        require(header.declaredFrameBytes in 4..FOLDER_SPAN_ARCHIVE_MAX_FRAME_BYTES) {
            AppStrings.ui_archive_total_exceeds_limit
        }
    }

    fun headerLengthBytes(length: Int): ByteArray {
        require(length >= 0) { AppStrings.ui_archive_header_length_invalid }
        return length.toIntBytes()
    }

    fun readHeaderLength(bytes: ByteArray, offset: Int = 0): Int {
        require(offset >= 0 && offset + 4 <= bytes.size) { AppStrings.ui_archive_the_flow_before_it_ends }
        return bytes.readInt(offset)
    }

    fun validateEntryRequests(entries: List<FolderSpanArchiveEntryRequest>) {
        require(entries.isNotEmpty()) { AppStrings.ui_archive_and_download_entries_cannot_be_empty }
        require(entries.size <= FOLDER_SPAN_ARCHIVE_MAX_ENTRIES) { AppStrings.ui_number_of_entries_exceeds_the_limit }
        var totalBytes = 0L
        val relativePaths = mutableSetOf<String>()
        entries.forEach { entry ->
            require(entry.sourcePath.isNotBlank()) { AppStrings.ui_archive_source_path_cannot_be_empty }
            val normalizedPath = normalizeRelativePath(entry.relativePath)
            if (!entry.directory) {
                require(relativePaths.add(normalizedPath)) { AppStrings.ui_archive_file_path_duplication_arg0.format(arg0 = (normalizedPath)) }
                require(entry.size >= 0L) { AppStrings.ui_archive_file_length_invalid }
                require(entry.size <= FOLDER_SPAN_ARCHIVE_SMALL_FILE_THRESHOLD_BYTES) { AppStrings.ui_archive_file_length_exceeds_the_threshold }
                totalBytes += entry.size
                require(totalBytes <= FOLDER_SPAN_ARCHIVE_MAX_PAYLOAD_BYTES) { AppStrings.ui_archive_total_exceeds_limit }
            }
        }
    }

}

private fun Int.toIntBytes(): ByteArray = byteArrayOf(
    ((this ushr 24) and 0xFF).toByte(),
    ((this ushr 16) and 0xFF).toByte(),
    ((this ushr 8) and 0xFF).toByte(),
    (this and 0xFF).toByte(),
)

private fun ByteArray.readInt(offset: Int): Int {
    return ((this[offset].toInt() and 0xFF) shl 24) or
        ((this[offset + 1].toInt() and 0xFF) shl 16) or
        ((this[offset + 2].toInt() and 0xFF) shl 8) or
        (this[offset + 3].toInt() and 0xFF)
}
