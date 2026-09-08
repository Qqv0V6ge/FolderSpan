package com.folderspan.editor

import strings.AppStrings

const val DEFAULT_EDITOR_EDIT_HISTORY_COMMANDS: Int = 100
const val DEFAULT_EDITOR_EDIT_HISTORY_BYTES: Long = 8L * 1024L * 1024L
const val DEFAULT_EDITOR_PENDING_EDIT_BYTES: Long = 32L * 1024L * 1024L

enum class EditorModificationKind {
    TextReplacement,
}

data class EditorModification(
    val pageIndex: Long,
    val kind: EditorModificationKind,
    val startOffset: Long,
    val endOffsetExclusive: Long,
    val originalByteCount: Int,
    val replacementByteCount: Int,
) {
    val sizeDelta: Long
        get() = replacementByteCount.toLong() - originalByteCount.toLong()
}

internal data class EditorEditCommand(
    val pageIndex: Long,
    val pageStartOffset: Long,
    val kind: EditorModificationKind,
    val originalBytes: ByteArray,
    val beforeBytes: ByteArray,
    val afterBytes: ByteArray,
    val beforeEncodingReplacementCount: Int = 0,
    val beforeEncodingReplacementSamples: List<EditorEncodingReplacementSample> = emptyList(),
    val afterEncodingReplacementCount: Int = 0,
    val afterEncodingReplacementSamples: List<EditorEncodingReplacementSample> = emptyList(),
) {
    val storedBytes: Long
        get() = originalBytes.size.toLong() + beforeBytes.size.toLong() + afterBytes.size.toLong()
}

internal data class EditorEditTransaction(
    val commands: List<EditorEditCommand>,
) {
    init {
        require(commands.isNotEmpty()) { AppStrings.ui_editing_tasks_cannot_be_empty }
    }

    val storedBytes: Long
        get() = commands.sumOf(EditorEditCommand::storedBytes)
}

internal class EditorEditCommandLog(
    private val maxCommands: Int = DEFAULT_EDITOR_EDIT_HISTORY_COMMANDS,
    private val maxStoredBytes: Long = DEFAULT_EDITOR_EDIT_HISTORY_BYTES,
) {
    init {
        require(maxCommands > 0) { AppStrings.ui_maxcommands_must_be_greater_than_0 }
        require(maxStoredBytes > 0L) { AppStrings.ui_maxstoredbytes_must_be_greater_than_0 }
    }

    private val undoCommands = mutableListOf<EditorEditTransaction>()
    private val redoCommands = mutableListOf<EditorEditTransaction>()

    val canUndo: Boolean
        get() = undoCommands.isNotEmpty()
    val canRedo: Boolean
        get() = redoCommands.isNotEmpty()
    val commandCount: Int
        get() = undoCommands.size + redoCommands.size
    val storedBytes: Long
        get() = undoCommands.sumOf(EditorEditTransaction::storedBytes) +
            redoCommands.sumOf(EditorEditTransaction::storedBytes)

    fun canRecord(commands: List<EditorEditCommand>): Boolean =
        commands.filterNot { it.beforeBytes.contentEquals(it.afterBytes) }
            .sumOf(EditorEditCommand::storedBytes) <= maxStoredBytes

    fun record(command: EditorEditCommand) {
        record(listOf(command))
    }

    fun record(commands: List<EditorEditCommand>) {
        val copied = commands.mapNotNull { command ->
            if (command.beforeBytes.contentEquals(command.afterBytes)) {
                null
            } else {
                command.copy(
                    originalBytes = command.originalBytes.copyOf(),
                    beforeBytes = command.beforeBytes.copyOf(),
                    afterBytes = command.afterBytes.copyOf(),
                    beforeEncodingReplacementSamples = command.beforeEncodingReplacementSamples.toList(),
                    afterEncodingReplacementSamples = command.afterEncodingReplacementSamples.toList(),
                )
            }
        }
        if (copied.isEmpty()) return

        redoCommands.clear()
        undoCommands += EditorEditTransaction(copied)
        trimOldestUndoCommands()
    }

    fun undo(): EditorEditTransaction? {
        val command = undoCommands.removeLastOrNull() ?: return null
        redoCommands += command
        return command
    }

    fun redo(): EditorEditTransaction? {
        val command = redoCommands.removeLastOrNull() ?: return null
        undoCommands += command
        return command
    }

    fun clear() {
        undoCommands.clear()
        redoCommands.clear()
    }

    private fun trimOldestUndoCommands() {
        while (undoCommands.size > maxCommands || storedBytes > maxStoredBytes) {
            if (undoCommands.isEmpty()) break
            undoCommands.removeAt(0)
        }
    }
}

internal fun buildEditorModification(
    pageIndex: Long,
    pageStartOffset: Long,
    kind: EditorModificationKind,
    original: ByteArray,
    replacement: ByteArray,
): EditorModification? {
    if (original.contentEquals(replacement)) return null

    var commonPrefix = 0
    val sharedSize = minOf(original.size, replacement.size)
    while (commonPrefix < sharedSize && original[commonPrefix] == replacement[commonPrefix]) {
        commonPrefix += 1
    }

    var commonSuffix = 0
    while (
        commonSuffix < original.size - commonPrefix &&
        commonSuffix < replacement.size - commonPrefix &&
        original[original.lastIndex - commonSuffix] == replacement[replacement.lastIndex - commonSuffix]
    ) {
        commonSuffix += 1
    }

    val originalChangedCount = original.size - commonPrefix - commonSuffix
    val replacementChangedCount = replacement.size - commonPrefix - commonSuffix
    return EditorModification(
        pageIndex = pageIndex,
        kind = kind,
        startOffset = pageStartOffset + commonPrefix,
        endOffsetExclusive = pageStartOffset + commonPrefix +
            maxOf(originalChangedCount, replacementChangedCount),
        originalByteCount = originalChangedCount,
        replacementByteCount = replacementChangedCount,
    )
}
