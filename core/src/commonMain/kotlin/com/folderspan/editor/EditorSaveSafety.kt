package com.folderspan.editor

import strings.AppStrings

enum class EditorBackupMode {
    CompleteFile,
    ModifiedRanges,
    Disabled,
}

data class EditorEncodingReplacementSample(
    val pageIndex: Long,
    val characterIndex: Int,
    val character: Char,
)

data class EditorSavePreview(
    val changedRanges: List<EditorModification>,
    val originalSize: Long,
    val newSize: Long,
    val encoding: EditorTextEncoding,
    val newlineConversion: EditorNewlineKind?,
    val backupMode: EditorBackupMode,
    val estimatedBackupBytes: Long,
    val availableBackupBytes: Long? = null,
    val replacementCharacterCount: Int,
    val replacementCharacterSamples: List<EditorEncodingReplacementSample>,
) {
    val sizeDelta: Long
        get() = newSize - originalSize
    val hasEnoughBackupSpace: Boolean
        get() = availableBackupBytes == null || availableBackupBytes >= estimatedBackupBytes
}

data class EditorSaveConflict(
    val expected: FileEditorSourceSnapshot,
    val actual: FileEditorSourceSnapshot,
)

class EditorSaveRollbackException(
    cause: Throwable,
    val rollbackFailure: Throwable?,
) : IllegalStateException(
    if (rollbackFailure == null) {
        AppStrings.ui_range_write_failed_original_bytes_restored_arg0.format(arg0 = (cause.message ?: AppStrings.ui_unknown_error))
    } else {
        AppStrings.ui_rollback_not_completed_arg0.format(arg0 = (rollbackFailure.message ?: cause.message ?: AppStrings.ui_unknown_error))
    },
    cause,
)
