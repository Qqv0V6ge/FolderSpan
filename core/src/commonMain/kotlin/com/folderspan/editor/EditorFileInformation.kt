package com.folderspan.editor

import com.folderspan.data.file.FileProtocol
import com.folderspan.data.file.FileSimpleInfo

data class EditorFileInformation(
    val protocol: FileProtocol,
    val protocolId: String?,
    val path: String,
    val size: Long,
    val createdAt: Long,
    val updatedAt: Long,
    val encoding: EditorTextEncoding,
    val newline: EditorNewlineKind,
    val currentOffset: Long?,
    val currentLine: Long?,
    val selectionSize: Long,
) {
    val protocolQualifiedPath: String
        get() = buildString {
            append(protocol.name)
            protocolId?.takeIf(String::isNotBlank)?.let { append('[').append(it).append(']') }
            append(':').append(path)
        }
}

fun editorFileInformation(
    file: FileSimpleInfo,
    state: FileEditorDocumentState,
): EditorFileInformation = EditorFileInformation(
    protocol = file.protocol,
    protocolId = file.protocolId.takeIf(String::isNotBlank),
    path = file.path,
    size = state.fileSize,
    createdAt = file.createdDate,
    updatedAt = file.updatedDate,
    encoding = state.encoding,
    newline = state.newlineKind,
    currentOffset = state.selectionStartOffset ?: state.pageStartOffset,
    currentLine = state.currentLineNumber,
    selectionSize = ((state.selectionEndOffsetExclusive ?: 0L) -
        (state.selectionStartOffset ?: 0L)).coerceAtLeast(0L),
)
