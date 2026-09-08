package com.folderspan.ui.components.buttons

import strings.AppStrings

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.folderspan.data.file.FileFilterType
import com.folderspan.data.file.FileSimpleInfo
import com.folderspan.data.file.GetFileFilterType
import com.folderspan.data.file.displayName
import com.folderspan.db.FileFilter

@Immutable
data class FileFilterChipUiState(
    val type: FileFilterType,
    val name: String,
    val count: Int,
    val selected: Boolean,
)

@Immutable
data class FileFilterButtonGroupUiState(
    val folderCount: Int,
    val folderSelected: Boolean,
    val fileCount: Int,
    val fileSelected: Boolean,
    val hiddenCount: Int,
    val hiddenSelected: Boolean,
    val hiddenFilterAvailable: Boolean,
    val chips: List<FileFilterChipUiState>,
) {
    val showFileChip: Boolean
        get() = fileCount > 0 || fileSelected

    val showHiddenChip: Boolean
        get() = hiddenFilterAvailable && (hiddenCount > 0 || hiddenSelected)
}

/**
 * 根据当前目录内容和已选过滤条件构建文件过滤器按钮组状态。
 *
 * @param fileAndFolder 当前目录完整文件和文件夹列表
 * @param filterFileTypes 可用于扩展名匹配的文件类型配置
 * @param filterFileExtensions 当前已选过滤类型；包含 Hidden 时隐藏 chip 会呈现选中状态
 * @param isHide 是否已开启显示隐藏文件；关闭时不显示隐藏 chip
 */
fun buildFileFilterButtonGroupUiState(
    fileAndFolder: List<FileSimpleInfo>,
    filterFileTypes: List<FileFilter>,
    filterFileExtensions: List<FileFilterType>,
    isHide: Boolean,
): FileFilterButtonGroupUiState {
    val visibleFiles = fileAndFolder
        .asSequence()
        .filterNot { item -> item.isDirectory }
        .filter { item -> isHide || !item.isHidden }
        .toList()
    val extensionCounts = visibleFiles
        .groupingBy { item -> item.mineType }
        .eachCount()
    val folderCount = fileAndFolder
        .count { item -> item.isDirectory && (isHide || !item.isHidden) }
    val fileCount = visibleFiles.size
    val hiddenCount = fileAndFolder.count { item -> item.isHidden }
    val selectedTypes = filterFileExtensions.toSet()
    val chips = filterFileTypes
        .mapNotNull { filterFileType ->
            val count = filterFileType.extensions.sumOf { extension ->
                extensionCounts[extension] ?: 0
            }
            if (count < 1) {
                null
            } else {
                FileFilterChipUiState(
                    type = filterFileType.type,
                    name = filterFileType.displayName(),
                    count = count,
                    selected = filterFileType.type in selectedTypes,
                )
            }
        }
    return FileFilterButtonGroupUiState(
        folderCount = folderCount,
        folderSelected = FileFilterType.Folder in selectedTypes,
        fileCount = fileCount,
        fileSelected = FileFilterType.File in selectedTypes,
        hiddenCount = hiddenCount,
        hiddenSelected = FileFilterType.Hidden in selectedTypes,
        hiddenFilterAvailable = isHide,
        chips = chips,
    )
}

/**
 * 文件过滤器按钮组组件
 *
 * 用于显示一组文件过滤器按钮，包括文件夹、文件、不同类型的文件过滤器和隐藏项目，每个过滤器显示对应类型的数量。
 * 隐藏项目过滤器位于末尾，仅在当前目录开启“显示隐藏文件”后显示，并作为额外条件与文件、文件夹或文件类型过滤叠加。
 *
 * @param uiState 文件过滤器按钮组的渲染状态，包含各过滤 chip 的数量和选中状态
 * @param onCheckedFileFilterTypeChange 文件过滤类型选择状态变化时的回调，参数依次为当前是否已选中和过滤类型
 * @param modifier 应用于组件的修饰符
 */
@Composable
fun FileFilterButtonGroup(
    uiState: FileFilterButtonGroupUiState,
    onCheckedFileFilterTypeChange: (Boolean, FileFilterType) -> Unit,
    modifier: Modifier = Modifier
) {
    // 使用LazyRow水平显示过滤器按钮
    LazyRow(modifier) {
        item { Spacer(Modifier.width(8.dp)) }

        item {
            // 添加文件夹过滤器按钮，如果没有文件夹则不显示
            if (uiState.folderCount < 1) return@item

            FilterChip(
                selected = uiState.folderSelected,
                label = { Text(AppStrings.ui_folder_arg0.format(arg0 = (uiState.folderCount).toString())) },
                leadingIcon = { GetFileFilterType(FileFilterType.Folder) },
                shape = RoundedCornerShape(25.dp),
                onClick = { onCheckedFileFilterTypeChange(uiState.folderSelected, FileFilterType.Folder) },
            )
            Spacer(Modifier.width(8.dp))
        }

        item {
            if (!uiState.showFileChip) return@item

            FilterChip(
                selected = uiState.fileSelected,
                label = { Text(AppStrings.ui_file_filter_arg0.format(arg0 = (uiState.fileCount).toString())) },
                leadingIcon = { GetFileFilterType(FileFilterType.File) },
                shape = RoundedCornerShape(25.dp),
                onClick = { onCheckedFileFilterTypeChange(uiState.fileSelected, FileFilterType.File) },
            )
            Spacer(Modifier.width(8.dp))
        }

        item {
            if (!uiState.showHiddenChip) return@item

            FilterChip(
                selected = uiState.hiddenSelected,
                label = { Text(AppStrings.ui_hide_arg0.format(arg0 = (uiState.hiddenCount).toString())) },
                leadingIcon = { GetFileFilterType(FileFilterType.Hidden) },
                shape = RoundedCornerShape(25.dp),
                onClick = { onCheckedFileFilterTypeChange(uiState.hiddenSelected, FileFilterType.Hidden) },
            )
            Spacer(Modifier.width(8.dp))
        }

        // 为每种文件类型添加过滤器按钮
        itemsIndexed(
            items = uiState.chips,
            key = { _, chip -> chip.type }
        ) { _, chip ->
            FilterChip(
                selected = chip.selected,
                label = { Text("${chip.name}(${chip.count})") },
                leadingIcon = { GetFileFilterType(chip.type) },
                shape = RoundedCornerShape(25.dp),
                onClick = { onCheckedFileFilterTypeChange(chip.selected, chip.type) },
            )
            Spacer(Modifier.width(8.dp))
        }
    }
}
