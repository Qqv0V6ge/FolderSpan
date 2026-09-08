package com.folderspan.ui.components.file

import com.folderspan.utils.FileAccessPermission
import strings.AppStrings

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.folderspan.data.file.FileFilterSort
import com.folderspan.data.file.FileFilterType
import com.folderspan.data.file.FileSimpleInfo
import com.folderspan.data.file.PathInfo
import com.folderspan.data.main.DiskBase
import com.folderspan.data.main.Local
import com.folderspan.data.main.device.Device
import com.folderspan.data.main.network.NetworkAccess
import com.folderspan.db.FileFilter
import com.folderspan.extensions.*
import com.folderspan.ui.components.appbar.PathSwitchContainer
import com.folderspan.ui.components.buttons.FileFilterButtonGroup
import com.folderspan.ui.components.buttons.SortButton
import com.folderspan.ui.components.buttons.buildFileFilterButtonGroupUiState
import com.folderspan.ui.components.pagestate.PageRefreshState
import com.folderspan.ui.components.pagestate.PageStateLayout
import com.folderspan.ui.components.pagestate.resolvePageViewState
import com.folderspan.ui.components.pagestate.toPageEmptyMessage
import com.folderspan.ui.components.pagestate.toPageErrorState
import com.folderspan.ui.components.model.FileFilterTypeListUiState
import com.folderspan.ui.components.model.FileSelectionUiState
import com.folderspan.ui.state.file.DrawerBookmark
import com.folderspan.utils.PathUtils
import com.folderspan.utils.scrollToItemAfterFrame
import kotlinx.coroutines.launch

@Immutable
data class FileSelectorDependencies(
    val configuredFileFilters: List<FileFilter> = emptyList(),
    val bookmarks: List<DrawerBookmark> = emptyList(),
)

val LocalFileSelectorDependencies = staticCompositionLocalOf { FileSelectorDependencies() }

private class FileSelectorDataSource(
    val sessionKey: String,
    val pathSeparator: String,
    val loadRootPaths: suspend () -> Result<List<PathInfo>>,
    val loadFiles: suspend (String) -> Result<List<FileSimpleInfo>>,
)

private fun createFileSelectorDataSource(deskType: DiskBase): FileSelectorDataSource {
    val pathSeparator = deskType.pathSeparator.ifBlank { PathUtils.getPathSeparator() }
    return when (deskType) {
        is Local -> FileSelectorDataSource(
            sessionKey = "local:${deskType.name}:$pathSeparator",
            pathSeparator = pathSeparator,
            loadRootPaths = { PathUtils.getRootPaths(FileAccessPermission.Allowed) },
            loadFiles = { path -> path.getFileAndFolder(FileAccessPermission.Allowed) },
        )
        is Device -> FileSelectorDataSource(
            sessionKey = "device:${deskType.id}:$pathSeparator",
            pathSeparator = pathSeparator,
            loadRootPaths = deskType.paths::getRootPaths,
            loadFiles = deskType.paths::getList,
        )
        is NetworkAccess -> FileSelectorDataSource(
            sessionKey = "network:${deskType.protocolId}:$pathSeparator",
            pathSeparator = pathSeparator,
            loadRootPaths = { Result.success(deskType.getRootPaths()) },
            loadFiles = deskType::getList,
        )
        else -> FileSelectorDataSource(
            sessionKey = "${deskType.name}:$pathSeparator",
            pathSeparator = pathSeparator,
            loadRootPaths = { Result.success(emptyList()) },
            loadFiles = { Result.success(emptyList()) },
        )
    }
}

private fun splitPathSegments(path: String): List<String> {
    return path
        .split('/', '\\')
        .map { item -> item.trim() }
        .filter { item -> item.isNotEmpty() }
}

private fun buildSelectorPath(base: String, segments: List<String>, separator: String): String {
    val normalizedBase = when {
        base.isBlank() -> separator
        base.endsWith(separator) -> base
        else -> base + separator
    }
    if (segments.isEmpty()) {
        return normalizedBase.removeSuffix(separator).ifBlank { separator }
    }
    return normalizedBase + segments.joinToString(separator)
}

private fun findMatchedRootPath(path: String, rootFileInfos: List<PathInfo>): PathInfo? {
    return rootFileInfos
        .sortedByDescending { it.path.pathLevel() }
        .firstOrNull { root ->
            path == root.path || when {
                root.path.endsWith('/') || root.path.endsWith('\\') -> path.startsWith(root.path)
                else -> path.startsWith("${root.path}/") || path.startsWith("${root.path}\\")
            }
        }
}

/**
 * 文件选择器组件，用于浏览和选择文件/文件夹
 *
 * @param deskType 磁盘类型，可以是本地磁盘(Local)、设备磁盘(Device)或网络磁盘(Network)
 * @param openPath 初始打开的路径
 * @param onFilesSelected 文件选择回调函数，返回当前可用于确认的文件列表
 *   - 勾选条目后返回勾选结果
 *   - 未勾选时：仅在选择文件模式下返回空列表；其他模式返回当前浏览目录
 * @param fileFilterTypesUiState 显示类型约束，隐藏不匹配的条目
 * @param selectionFilterTypesUiState 选择类型约束，不匹配的条目仍可见但不可勾选
 *   - 为空时：所有可见条目都可以被选择
 *   - 包含FileFilterType.Folder时：只能选择文件夹
 *   - 包含FileFilterType.File时：只能选择文件
 *   - 包含其他类型时：只能选择对应扩展名的文件类型
 * @param isSingleSelection 是否为单选模式，true表示只能选择一个文件/文件夹
 * @param onPathChanged 当前浏览路径变化回调
 * @param displayConstraints 显示约束；与 [fileFilterTypesUiState] 取交集后隐藏拒绝项，并先于选择约束执行
 * @param selectionConstraints 选择约束；仅对通过显示阶段的项目执行，拒绝项目仍保持可见
 *
 * @sample
 * ```kotlin
 * // 基本用法 - 选择任意文件
 * FileSelectorEntryRegion(
 *     openPath = "/storage/emulated/0",
 *     onFilesSelected = { selectedFiles ->
 *         println("Selected: ${selectedFiles.map { fileInfo -> fileInfo.name }}")
 *     }
 * )
 *
 * // 只允许选择图片文件
 * FileSelectorEntryRegion(
 *     openPath = "/storage/emulated/0/Pictures",
 *     selectionFilterTypes = arrayOf(FileFilterType.Image),
 *     onFilesSelected = { images ->
 *         // 只有图片文件可以被选择
 *     }
 * )
 *
 * // 只允许选择文件夹
 * FileSelectorEntryRegion(
 *     openPath = "/storage/emulated/0",
 *     selectionFilterTypes = arrayOf(FileFilterType.Folder),
 *     isSingleSelection = true,
 *     onFilesSelected = { folders ->
 *         // 只能选择一个文件夹
 *     }
 * )
 *
 * // 允许选择图片和视频文件
 * FileSelectorEntryRegion(
 *     openPath = "/storage/emulated/0",
 *     selectionFilterTypes = arrayOf(FileFilterType.Image, FileFilterType.Video),
 *     onFilesSelected = { mediaFiles ->
 *         // 只有图片和视频文件可以被选择
 *     }
 * )
 *
 * // 显示图片文件，但只允许选择特定格式
 * FileSelectorEntryRegion(
 *     openPath = "/storage/emulated/0/Pictures",
 *     fileFilterTypes = arrayOf(FileFilterType.Image),
 *     selectionFilterTypes = arrayOf(FileFilterType.Image),
 *     onFilesSelected = { selectedImages ->
 *         // 显示所有图片，但只能选择图片文件
 *     }
 * )
 * ```
 */
@Composable
fun FileSelectorEntryRegion(
    deskType: DiskBase = Local(),
    openPath: String,
    initialSelectionUiState: FileSelectionUiState = FileSelectionUiState(),
    onFilesSelected: (List<FileSimpleInfo>) -> Unit = {},
    onPathChanged: (String) -> Unit = {},
    fileFilterTypesUiState: FileFilterTypeListUiState = FileFilterTypeListUiState(),
    selectionFilterTypesUiState: FileFilterTypeListUiState = FileFilterTypeListUiState(),
    dependencies: FileSelectorDependencies = LocalFileSelectorDependencies.current,
    isSingleSelection: Boolean = false,
    displayConstraints: FileSelectorConstraints = FileSelectorConstraints.Unrestricted,
    selectionConstraints: FileSelectorConstraints = FileSelectorConstraints.Unrestricted,
) {
    // 协程作用域
    val scope = rememberCoroutineScope()
    val defaultCheckedFiles = initialSelectionUiState.files
    val fileFilterTypes = fileFilterTypesUiState.items
    val selectionFilterTypes = selectionFilterTypesUiState.items
    val currentOnPathChanged by rememberUpdatedState(onPathChanged)
    val currentOnFilesSelected by rememberUpdatedState(onFilesSelected)

    val dataSource = remember(deskType) { createFileSelectorDataSource(deskType) }
    val pathSeparator = dataSource.pathSeparator
    val selectorSessionKey = dataSource.sessionKey

    val rootFileInfos = remember { mutableStateListOf<PathInfo>() }
    var rootPath by remember { mutableStateOf("") }
    // 当前路径
    var path by remember(selectorSessionKey) {
        mutableStateOf(openPath.ifBlank { pathSeparator })
    }
    // 当前路径下的文件列表
    var files by remember(selectorSessionKey, path) {
        mutableStateOf<List<FileSimpleInfo>>(emptyList())
    }
    // 加载状态
    var isLoading by remember(selectorSessionKey, path) { mutableStateOf(true) }
    // 异常信息
    var exception by remember(selectorSessionKey, path) { mutableStateOf<Throwable?>(null) }
    var refreshErrorMessage by remember(selectorSessionKey, path) { mutableStateOf<String?>(null) }
    // 已选中的文件列表
    val checkedFiles = remember(selectorSessionKey) {
        mutableStateListOf<FileSimpleInfo>().apply {
            addAll(defaultCheckedFiles)
        }
    }
    var selectionRejectionMessage by remember { mutableStateOf<String?>(null) }

    fun updateRootPathFromPath(targetPath: String, fallbackToFirst: Boolean = false) {
        val matchedRootPathInfo = findMatchedRootPath(targetPath, rootFileInfos)
        if (matchedRootPathInfo != null) {
            rootPath = matchedRootPathInfo.path
            return
        }
        if (fallbackToFirst && rootFileInfos.isNotEmpty()) {
            rootPath = rootFileInfos.first().path
        }
    }

    LaunchedEffect(deskType, pathSeparator) {
        rootFileInfos.clear()
        rootFileInfos.addAll(dataSource.loadRootPaths().getOrDefault(emptyList()))

        updateRootPathFromPath(path, fallbackToFirst = true)

        if (path.isBlank()) {
            path = rootPath
        }
    }

    val rootSegments = remember(rootPath, pathSeparator) {
        splitPathSegments(rootPath)
    }

    val newPath = path.replaceFirst(rootPath, "")

    val allSegments = remember(newPath, pathSeparator) {
        splitPathSegments(newPath)
    }
    val paths = remember(rootSegments, allSegments) {
        if (
            allSegments.size >= rootSegments.size &&
            allSegments.take(rootSegments.size) == rootSegments
        ) {
            allSegments.drop(rootSegments.size)
        } else {
            allSegments
        }
    }

    // 是否隐藏文件
    var isHideFile by remember { mutableStateOf(false) }
    // 文件排序类型
    var fileFilterSortType by remember { mutableStateOf(FileFilterSort.NameAsc) }

    // 过滤的文件类型
    var filterFileExtensions by remember { mutableStateOf<List<FileFilterType>>(listOf()) }

    /**
     * 从当前路径加载文件列表
     * 1. 清空并更新路径分段
     * 2. 重置异常状态
     * 3. 设置加载状态
     * 4. 异步获取文件列表
     * 5. 根据结果更新状态
     */
    suspend fun loadFilesFromPath() {
        val keepContentOnFailure = files.isNotEmpty()
        exception = null
        refreshErrorMessage = null
        isLoading = true

        dataSource.loadFiles(path)
            .onSuccess { fileList ->
                files = fileList
                isLoading = false
            }
            .onFailure { error ->
                isLoading = false
                if (keepContentOnFailure) {
                    refreshErrorMessage = error.message?.takeIf { item -> item.isNotBlank() }
                        ?: AppStrings.ui_loading_failed
                } else {
                    exception = error
                    files = emptyList()
                }
            }
    }

    fun emitResolvedSelection(selectedFiles: List<FileSimpleInfo> = checkedFiles.toList()) {
        currentOnFilesSelected(
            resolveFileSelectorConfirmSelection(
                selectedFiles = selectedFiles,
                currentPath = path,
                selectionFilterTypes = selectionFilterTypes,
                selectionConstraints = selectionConstraints,
            ),
        )
    }

    LaunchedEffect(path) {
        selectionRejectionMessage = null
        currentOnPathChanged(path)
        loadFilesFromPath()
    }

    LaunchedEffect(path, selectionFilterTypes, selectionConstraints) {
        emitResolvedSelection()
    }

    Column(Modifier.fillMaxSize()) {
        val firstVisibleIndex = remember(paths.size) { (paths.size - 1).coerceAtLeast(0) }
        val listState = rememberLazyListState(initialFirstVisibleItemIndex = firstVisibleIndex)
        LazyRow(state = listState) {
            item {
                var expanded by remember { mutableStateOf(false) }
                val displayRootPath = rootPath.ifBlank { pathSeparator }

                Box(modifier = Modifier.wrapContentSize(Alignment.TopStart)) {
                    ElevatedFilterChip(
                        selected = false,
                        label = { Text(displayRootPath) },
                        border = null,
                        shape = RoundedCornerShape(25.dp),
                        trailingIcon = if (rootFileInfos.size > 1) {
                            {
                                Icon(
                                    if (expanded) Icons.Default.ArrowDropUp else Icons.Default.ArrowDropDown,
                                    null,
                                    Modifier.clip(RoundedCornerShape(25.dp)).clickable { expanded = !expanded }
                                )
                            }
                        } else {
                            null
                        },
                        onClick = { path = displayRootPath }
                    )

                    DropdownMenu(
                        expanded = expanded,
                        onDismissRequest = { expanded = false },
                    ) {
                        for ((rootFileInfoPath) in rootFileInfos) {
                            DropdownMenuItem(
                                text = { Text(rootFileInfoPath) },
                                onClick = {
                                    path = rootFileInfoPath
                                    rootPath = rootFileInfoPath
                                    expanded = false
                                })
                        }
                    }
                }
            }

            itemsIndexed(
                items = paths,
                key = { index, _ ->
                    val basePath = rootPath.ifBlank { pathSeparator }
                    buildSelectorPath(basePath, paths.subList(0, index + 1), pathSeparator)
                }
            ) { index, text ->
                val basePath = rootPath.ifBlank { pathSeparator }
                val nowPath = buildSelectorPath(basePath, paths.subList(0, index), pathSeparator)
                if (deskType is NetworkAccess) {
                    FilterChip(
                        selected = false,
                        label = { Text(text) },
                        border = null,
                        shape = RoundedCornerShape(25.dp),
                        onClick = {
                            val newPath = buildSelectorPath(basePath, paths.subList(0, index + 1), pathSeparator)
                            path = newPath
                            updateRootPathFromPath(newPath)
                        }
                    )
                } else {
                    PathSwitchContainer(
                        name = text,
                        path = nowPath,
                        isHideFile = isHideFile,
                        bookmarks = dependencies.bookmarks,
                        loadFileAndFolder = dataSource.loadFiles,
                        onClick = {
                            val newPath = buildSelectorPath(basePath, paths.subList(0, index + 1), pathSeparator)
                            path = newPath
                            updateRootPathFromPath(newPath)
                        },
                        onSelected = { item ->
                            path = item
                            updateRootPathFromPath(item)
                        }
                    )
                }
            }
        }

        val configuredFileFilters = dependencies.configuredFileFilters
        val configuredFiltersByExtension = remember(configuredFileFilters) {
            configuredFileFilters.indexByExtension()
        }
        val filterFiles = remember(
            files,
            isHideFile,
            fileFilterSortType,
            configuredFileFilters,
            filterFileExtensions,
            fileFilterTypes,
        ) {
            files.filter(
                    isHidden = isHideFile,
                    sortType = fileFilterSortType,
                    filterFileTypes = configuredFileFilters,
                    filterFileExtensions = (filterFileExtensions + fileFilterTypes).toSet().toList(),
                ).withFileFilterTypes(configuredFiltersByExtension)
        }
        val selectedFiles = checkedFiles.toList()
        val selectorEntries = remember(
            filterFiles,
            selectedFiles,
            selectionFilterTypes,
            configuredFileFilters,
            displayConstraints,
            selectionConstraints,
        ) {
            prepareFileSelectorEntries(
                files = filterFiles,
                checkedFiles = selectedFiles,
                selectionFilterTypes = selectionFilterTypes,
                configuredFileFilters = configuredFileFilters,
                displayConstraints = displayConstraints,
                selectionConstraints = selectionConstraints,
            )
        }
        val visibleFiles = remember(selectorEntries) {
            selectorEntries.map { entry -> entry.file }
        }
        val filterButtonGroupUiState = remember(
            visibleFiles,
            configuredFileFilters,
            filterFileExtensions,
            isHideFile,
        ) {
            buildFileFilterButtonGroupUiState(
                fileAndFolder = visibleFiles,
                filterFileTypes = configuredFileFilters,
                filterFileExtensions = filterFileExtensions,
                isHide = isHideFile,
            )
        }

        Row {
            FileFilterButtonGroup(
                uiState = filterButtonGroupUiState,
                onCheckedFileFilterTypeChange = { isSelected, fileFilterType ->
                    filterFileExtensions = if (isSelected) {
                        filterFileExtensions - fileFilterType
                    } else {
                        filterFileExtensions + fileFilterType
                    }
                    scope.launch {
                        loadFilesFromPath()
                    }
                },
                modifier = Modifier.weight(1f)
            )

            Row(Modifier.padding(start = 16.dp, end = 12.dp)) {
                IconButton(
                    onClick = {
                        isHideFile = !isHideFile
                        scope.launch {
                            loadFilesFromPath()
                        }
                    }
                ) {
                    Icon(
                        imageVector = if (isHideFile) Icons.Default.Visibility else Icons.Default.VisibilityOff,
                        contentDescription = if (isHideFile) AppStrings.ui_show_hidden_files else AppStrings.ui_hidden_files,
                        tint = if (isHideFile) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                SortButton(
                    sortType = fileFilterSortType,
                    onUpdateSort = { item ->
                        fileFilterSortType = item
                        scope.launch {
                            loadFilesFromPath()
                        }
                    }
                )
            }
        }

        LaunchedEffect(paths.size) {
            listState.scrollToItemAfterFrame(paths.size)
        }

        LaunchedEffect(
            displayConstraints,
            selectionConstraints,
            selectionFilterTypes,
            configuredFileFilters,
        ) {
            val retainedSelections = retainValidFileSelectorSelections(
                selectedFiles = checkedFiles.toList(),
                selectionFilterTypes = selectionFilterTypes,
                configuredFileFilters = configuredFileFilters,
                displayConstraints = displayConstraints,
                selectionConstraints = selectionConstraints,
            )
            if (retainedSelections.size != checkedFiles.size) {
                checkedFiles.clear()
                checkedFiles.addAll(retainedSelections)
                emitResolvedSelection(retainedSelections)
            }
        }

        fun showSelectionRejection(entry: FileSelectorEntryUiModel) {
            selectionRejectionMessage = entry.selectionRejection?.localizedMessage()
        }

        fun toggleSelection(entry: FileSelectorEntryUiModel) {
            if (entry.selectionRejection != null) {
                showSelectionRejection(entry)
                return
            }
            if (isSingleSelection) {
                checkedFiles.clear()
                if (!entry.isSelected) {
                    checkedFiles.add(entry.file)
                }
            } else if (entry.isSelected) {
                checkedFiles.removeAll { selected -> selected.path == entry.file.path }
            } else {
                checkedFiles.add(entry.file)
            }
            selectionRejectionMessage = null
            emitResolvedSelection()
        }

        fun activateEntry(entry: FileSelectorEntryUiModel) {
            if (entry.isSelected) {
                toggleSelection(entry)
                return
            }
            if (entry.file.isDirectory) {
                path = entry.file.path
                return
            }
            if (entry.selectionRejection != null) {
                showSelectionRejection(entry)
                return
            }
            toggleSelection(entry)
        }

        selectionRejectionMessage?.let { message ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(
                        color = MaterialTheme.colorScheme.errorContainer,
                        shape = MaterialTheme.shapes.small,
                    )
                    .semantics { liveRegion = LiveRegionMode.Assertive }
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    imageVector = Icons.Default.ErrorOutline,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onErrorContainer,
                )
                Text(
                    text = message,
                    color = MaterialTheme.colorScheme.onErrorContainer,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }

        val hasEntries = selectorEntries.isNotEmpty()
        val reloadCurrentPath: () -> Unit = {
            scope.launch { loadFilesFromPath() }
        }
        PageStateLayout(
            state = resolvePageViewState(
                isLoading = isLoading && !hasEntries,
                errorState = exception?.takeIf { !hasEntries }?.toPageErrorState(),
                isEmpty = !hasEntries,
                emptyMessage = exception?.toPageEmptyMessage(),
            ),
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
            refreshState = when {
                isLoading && hasEntries -> PageRefreshState.Refreshing
                refreshErrorMessage != null -> PageRefreshState.Error(refreshErrorMessage)
                else -> PageRefreshState.Idle
            },
            onRefresh = reloadCurrentPath,
            onRetry = reloadCurrentPath,
            onRetryRefresh = reloadCurrentPath,
        ) {
            FileSelectorEntriesRegion(
                entries = selectorEntries,
                path = path,
                onActivate = ::activateEntry,
                onToggleSelection = ::toggleSelection,
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}
