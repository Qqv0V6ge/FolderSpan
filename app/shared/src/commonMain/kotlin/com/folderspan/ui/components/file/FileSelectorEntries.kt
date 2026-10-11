package com.folderspan.ui.components.file

import androidx.compose.runtime.Immutable
import com.folderspan.data.file.FileFilterType
import com.folderspan.data.file.FileSimpleInfo
import com.folderspan.db.FileFilter
import com.folderspan.extensions.getExtensions
import strings.AppStrings

@Immutable
internal data class FileSelectorEntryUiModel(
    val file: FileSimpleInfo,
    val isSelected: Boolean,
    val selectionRejection: FileSelectorSelectionRejection?,
)

sealed interface FileSelectorSelectionRejection {
    data object CategoryNotAllowed : FileSelectorSelectionRejection

    @Immutable
    data class Constraint(
        val violation: FileSelectorConstraintViolation,
    ) : FileSelectorSelectionRejection
}

internal fun prepareFileSelectorEntries(
    files: List<FileSimpleInfo>,
    checkedFiles: List<FileSimpleInfo>,
    selectionFilterTypes: List<FileFilterType>,
    configuredFileFilters: List<FileFilter>,
    displayConstraints: FileSelectorConstraints = FileSelectorConstraints.Unrestricted,
    selectionConstraints: FileSelectorConstraints = FileSelectorConstraints.Unrestricted,
): List<FileSelectorEntryUiModel> {
    val selectedPaths = checkedFiles.mapTo(mutableSetOf()) { file -> file.path }
    return files.mapNotNull { file ->
        if (displayConstraints.evaluate(file) !== FileSelectorConstraintResult.Allowed) {
            return@mapNotNull null
        }
        FileSelectorEntryUiModel(
            file = file,
            isSelected = file.path in selectedPaths,
            selectionRejection = file.selectionRejection(
                selectionFilterTypes = selectionFilterTypes,
                configuredFileFilters = configuredFileFilters,
                selectionConstraints = selectionConstraints,
            ),
        )
    }
}

internal fun retainValidFileSelectorSelections(
    selectedFiles: List<FileSimpleInfo>,
    selectionFilterTypes: List<FileFilterType>,
    configuredFileFilters: List<FileFilter>,
    displayConstraints: FileSelectorConstraints,
    selectionConstraints: FileSelectorConstraints,
): List<FileSimpleInfo> = selectedFiles.filter { file ->
    displayConstraints.evaluate(file) === FileSelectorConstraintResult.Allowed &&
        file.selectionRejection(
            selectionFilterTypes = selectionFilterTypes,
            configuredFileFilters = configuredFileFilters,
            selectionConstraints = selectionConstraints,
        ) == null
}

internal fun FileSelectorSelectionRejection.localizedMessage(): String = when (this) {
    FileSelectorSelectionRejection.CategoryNotAllowed ->
        AppStrings.ui_file_selector_selection_not_allowed

    is FileSelectorSelectionRejection.Constraint -> violation.localizedMessage()
}

/**
 * 仅在“选择文件”模式下，确认必须依赖列表中的勾选结果。
 * 目录选择或未限制类型时，可以把当前浏览目录当作确认结果。
 */
internal fun requiresExplicitFileSelectorSelection(
    selectionFilterTypes: List<FileFilterType>,
    selectionConstraints: FileSelectorConstraints = FileSelectorConstraints.Unrestricted,
): Boolean {
    val allowedKinds = selectionConstraints.allowedKinds
    val directoryAllowed =
        allowedKinds.isEmpty() || FileSelectorEntryKind.Directory in allowedKinds
    if (!directoryAllowed) {
        return true
    }
    if (selectionFilterTypes.isEmpty()) {
        return false
    }
    return FileFilterType.Folder !in selectionFilterTypes
}

internal fun resolveFileSelectorConfirmSelection(
    selectedFiles: List<FileSimpleInfo>,
    currentPath: String,
    selectionFilterTypes: List<FileFilterType>,
    selectionConstraints: FileSelectorConstraints = FileSelectorConstraints.Unrestricted,
): List<FileSimpleInfo> {
    if (selectedFiles.isNotEmpty()) {
        return selectedFiles
    }
    if (requiresExplicitFileSelectorSelection(selectionFilterTypes, selectionConstraints)) {
        return emptyList()
    }
    if (currentPath.isBlank()) {
        return emptyList()
    }
    return listOf(currentDirectoryAsSelection(currentPath))
}

internal fun currentDirectoryAsSelection(path: String): FileSimpleInfo {
    val name = path
        .split('/', '\\')
        .map { segment -> segment.trim() }
        .lastOrNull { segment -> segment.isNotEmpty() }
        .orEmpty()
        .ifBlank { path }
    return FileSimpleInfo.pathFileSimpleInfo(path).withCopy(name = name)
}

private fun FileSimpleInfo.selectionRejection(
    selectionFilterTypes: List<FileFilterType>,
    configuredFileFilters: List<FileFilter>,
    selectionConstraints: FileSelectorConstraints,
): FileSelectorSelectionRejection? {
    if (!matchesSelectionCategory(selectionFilterTypes, configuredFileFilters)) {
        return FileSelectorSelectionRejection.CategoryNotAllowed
    }
    return when (val result = selectionConstraints.evaluate(this)) {
        FileSelectorConstraintResult.Allowed -> null
        is FileSelectorConstraintResult.Rejected ->
            FileSelectorSelectionRejection.Constraint(result.violation)
    }
}

private fun FileSimpleInfo.matchesSelectionCategory(
    selectionFilterTypes: List<FileFilterType>,
    configuredFileFilters: List<FileFilter>,
): Boolean = selectionFilterTypes.isEmpty() || selectionFilterTypes.any { type ->
    when (type) {
        FileFilterType.Folder -> isDirectory
        FileFilterType.File -> !isDirectory
        FileFilterType.Hidden -> isHidden
        else -> configuredFileFilters.getExtensions(type).contains(mineType)
    }
}
