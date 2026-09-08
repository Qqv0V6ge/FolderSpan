package com.folderspan.ui.screen.file

import strings.AppStrings

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.layout.LazyLayoutCacheWindow
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.folderspan.data.StatusEnum
import com.folderspan.data.file.FileFilterType
import com.folderspan.data.file.FileFilterSort
import com.folderspan.data.file.FileInfo
import com.folderspan.data.file.FileSimpleInfo
import com.folderspan.data.main.DiskMenuPermission
import com.folderspan.data.main.share.Share
import com.folderspan.exception.EmptyDataException
import com.folderspan.extensions.fileOperationKey
import com.folderspan.extensions.getFilterByExtension
import com.folderspan.ignore.IgnoreFileMenuItem
import com.folderspan.ui.components.buttons.FileFilterButtonGroup
import com.folderspan.ui.components.buttons.FileFilterButtonGroupUiState
import com.folderspan.ui.components.buttons.SortButton
import com.folderspan.ui.components.buttons.buildFileFilterButtonGroupUiState
import com.folderspan.ui.components.dialog.*
import com.folderspan.ui.components.file.FileCard
import com.folderspan.ui.components.file.FileGridCard
import com.folderspan.ui.components.file.FileItemOperationStatus
import com.folderspan.ui.components.file.FileMenu
import com.folderspan.ui.components.file.FileMenuTrigger
import com.folderspan.ui.components.file.hasAnyFileCardMenuPermission
import com.folderspan.ui.components.file.resolveFileCardMenuPermission
import com.folderspan.ui.components.grid.GridList
import com.folderspan.ui.components.grid.GridListFabPadding
import com.folderspan.ui.components.grid.toGridListErrorState
import com.folderspan.ui.components.model.FileListUiState
import com.folderspan.ui.navigation.LocalAppNavigator
import com.folderspan.ui.screen.file.share.FileShareScreen
import com.folderspan.ui.state.file.FileFavoriteState
import com.folderspan.ui.state.file.FileFilterState
import com.folderspan.ui.state.file.FileRecentState
import com.folderspan.ui.state.file.FileShareState
import com.folderspan.ui.state.file.FileState
import com.folderspan.ui.state.file.FilePropertySummary
import com.folderspan.ui.state.main.*
import com.folderspan.ui.state.main.DrawerState
import com.folderspan.ui.thumbnail.DefaultFileThumbnailLoader
import com.folderspan.utils.VerificationUtils
import com.folderspan.utils.scrollToItemAfterFrame
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import org.koin.compose.koinInject

private data class FileListEntry(
    val operationKey: String,
    val file: FileSimpleInfo,
)

private enum class FileListContentType {
    List,
    Grid,
}

data class FileFilterButtonsUiState(
    val filterGroup: FileFilterButtonGroupUiState,
    val sortType: FileFilterSort,
    val isHideFile: Boolean,
    val searchText: String,
    val isSearchActive: Boolean,
    val ignoreFileMenuItems: List<IgnoreFileMenuItem>,
)

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun FileScreen(snackbarHostState: SnackbarHostState) {
    val fileState = koinInject<FileState>()
    val thumbnailLoader = remember(fileState) { DefaultFileThumbnailLoader(fileState) }
    DisposableEffect(thumbnailLoader) {
        onDispose(thumbnailLoader::close)
    }
    val homeState = koinInject<HomeState>()
    val taskState = koinInject<TaskState>()
    val drawerState = koinInject<DrawerState>()
    val path by fileState.path.collectAsState()
    val autoHighlightPaths by homeState.autoHighlightPaths.collectAsState()
    val isLoading by fileState.isLoading.collectAsState()
    val contentLocation by fileState.contentLocation.collectAsState()
    val exception by fileState.exception.collectAsState()
    val deskType by fileState.deskType.collectAsState()
    val fileInfo by homeState.fileInfo.collectAsState()
    val isPasteCopyFile by homeState.isPasteCopyFile.collectAsState()
    val isPasteMoveFile by homeState.isPasteMoveFile.collectAsState()
    val isRenameFile by homeState.isRenameFile.collectAsState()
    val isViewFile by homeState.isViewFile.collectAsState()
    val isRemoteOpenConfirmDialog by homeState.isRemoteOpenConfirmDialog.collectAsState()
    val remoteOpenFile by homeState.remoteOpenFile.collectAsState()
    val isDeviceDropUploadDialog by homeState.isDeviceDropUploadDialog.collectAsState()
    val deviceDropUploadRequest by homeState.deviceDropUploadRequest.collectAsState()
    val isFileGridView by drawerState.isFileGridView.collectAsState()
    val scrollLocation = remember(deskType, path) {
        fileState.scrollLocation(deskType, path)
    }
    val isCurrentLocationContent = contentLocation == scrollLocation

    val fileFilterState = koinInject<FileFilterState>()
    val updateKey by fileFilterState.updateKey.collectAsState()
    val fileFilterSortType by fileFilterState.sortType.collectAsState()
    val isHideFile by fileFilterState.isHideFile.collectAsState()
    val searchText by fileFilterState.searchText.collectAsState()
    val isSearchActive by fileFilterState.isSearchText.collectAsState()
    val ignoreFileMenuItems by fileFilterState.ignoreFileMenuItems.collectAsState()
    LaunchedEffect(deskType, path) {
        fileFilterState.bindPathPreferenceScope(deskType, path)
    }

    val fileFavoriteState = koinInject<FileFavoriteState>()
    val currentFavorites by fileFavoriteState.currentFavorites.collectAsState()
    val fileShareState = koinInject<FileShareState>()

    val fileRecentState = koinInject<FileRecentState>()

    val currentEntries by remember(fileState) {
        derivedStateOf { fileState.fileAndFolder.toList() }
    }
    var cachedEntries by remember(scrollLocation) { mutableStateOf<List<FileSimpleInfo>>(emptyList()) }
    LaunchedEffect(scrollLocation, currentEntries, isLoading, isCurrentLocationContent) {
        if (!isLoading && isCurrentLocationContent) {
            cachedEntries = currentEntries
        }
    }
    val displayEntries = if (isCurrentLocationContent) currentEntries else cachedEntries
    LaunchedEffect(displayEntries) {
        fileFilterState.updateCurrentDirectoryFiles(displayEntries)
    }
    val files = remember(displayEntries, updateKey) {
        fileFilterState.filter(displayEntries, updateKey)
    }
    val fileListEntries = remember(files) {
        files.map { file ->
            FileListEntry(
                operationKey = file.fileOperationKey(),
                file = file,
            )
        }
    }
    val selectedFiles by remember(homeState) {
        derivedStateOf { homeState.checkedFileSimpleInfo.toList() }
    }
    val selectedOperationKeys = remember(selectedFiles) {
        selectedFiles.map { item -> item.fileOperationKey() }.toSet()
    }
    val isSelectionMode = selectedFiles.isNotEmpty()
    val hasFloatingActionButton = !isSelectionMode && deskType !is Share
    val deletingOperationKeys = remember(taskState.revision, taskState.tasks.size) {
        taskState.tasks
            .filter { task -> task.taskType == TaskType.Delete && task.status == StatusEnum.LOADING }
            .mapNotNull { task -> task.fileOperationKey() }
            .toSet()
    }

    val scope = rememberCoroutineScope()
    val initialScrollPosition = remember(scrollLocation) {
        fileState.getScrollPosition(scrollLocation)
    }
    val cacheWindow = remember {
        LazyLayoutCacheWindow(
            aheadFraction = 0.5f,
            behindFraction = 0.25f,
        )
    }
    val lazyGridStateSaver = remember(cacheWindow) {
        listSaver<LazyGridState, Int>(
            save = { state ->
                listOf(
                    state.firstVisibleItemIndex,
                    state.firstVisibleItemScrollOffset,
                )
            },
            restore = { values ->
                LazyGridState(
                    cacheWindow = cacheWindow,
                    firstVisibleItemIndex = values[0],
                    firstVisibleItemScrollOffset = values[1],
                )
            },
        )
    }
    val lazyGridState = rememberSaveable(
        scrollLocation,
        cacheWindow,
        saver = lazyGridStateSaver,
    ) {
        LazyGridState(
            cacheWindow = cacheWindow,
            firstVisibleItemIndex = initialScrollPosition?.index ?: 0,
            firstVisibleItemScrollOffset = initialScrollPosition?.offset ?: 0,
        )
    }
    var isScrollRestored by remember(scrollLocation) { mutableStateOf(false) }
    var menuTargetKey by remember(scrollLocation) { mutableStateOf<String?>(null) }

    LaunchedEffect(
        scrollLocation,
        isCurrentLocationContent,
        files.size,
        isLoading,
        autoHighlightPaths,
        homeState.checkedFileSimpleInfo.size,
    ) {
        if (!isCurrentLocationContent || isLoading || files.isEmpty()) return@LaunchedEffect
        val highlightIndex = if (autoHighlightPaths.isNotEmpty()) {
            files.indexOfFirst { item -> item.path in autoHighlightPaths }
        } else {
            -1
        }
        if (highlightIndex >= 0) {
            lazyGridState.scrollToItemAfterFrame(highlightIndex, 0)
            isScrollRestored = true
            return@LaunchedEffect
        }
        if (isScrollRestored) return@LaunchedEffect
        val position = fileState.getScrollPosition(scrollLocation)
        if (position != null) {
            val targetIndex = position.index.coerceIn(0, files.lastIndex)
            val targetOffset = position.offset.coerceAtLeast(0)
            lazyGridState.scrollToItemAfterFrame(targetIndex, targetOffset)
        }
        isScrollRestored = true
    }

    val canTrackScroll = isScrollRestored && isCurrentLocationContent && !isLoading && files.isNotEmpty()
    LaunchedEffect(scrollLocation, lazyGridState, canTrackScroll, fileState) {
        if (!canTrackScroll) return@LaunchedEffect
        var hasObservedScrolling = false
        snapshotFlow { lazyGridState.isScrollInProgress }
            .distinctUntilChanged()
            .collect { isScrolling ->
                if (isScrolling) {
                    hasObservedScrolling = true
                    menuTargetKey = null
                } else if (hasObservedScrolling) {
                    fileState.updateScrollPosition(
                        location = scrollLocation,
                        index = lazyGridState.firstVisibleItemIndex,
                        offset = lazyGridState.firstVisibleItemScrollOffset,
                    )
                }
            }
    }

    DisposableEffect(scrollLocation, lazyGridState, isScrollRestored, fileState) {
        onDispose {
            if (isScrollRestored) {
                fileState.updateScrollPosition(
                    location = scrollLocation,
                    index = lazyGridState.firstVisibleItemIndex,
                    offset = lazyGridState.firstVisibleItemScrollOffset,
                )
            }
        }
    }

    LaunchedEffect(files, menuTargetKey) {
        val targetKey = menuTargetKey ?: return@LaunchedEffect
        if (files.none { item -> item.fileOperationKey() == targetKey }) {
            menuTargetKey = null
        }
    }

    LaunchedEffect(isFileGridView) {
        menuTargetKey = null
    }

    LaunchedEffect(currentEntries, isLoading, autoHighlightPaths) {
        if (isLoading || autoHighlightPaths.isEmpty()) return@LaunchedEffect
        homeState.syncAutoHighlightSelection(currentEntries)
    }

    val filterFileTypes = fileFilterState.filterFileTypes.toList()
    val selectedFilterTypes = fileFilterState.filterFileExtensions.toList()
    val fileFilterButtonGroupUiState = remember(
        displayEntries,
        filterFileTypes,
        selectedFilterTypes,
        isHideFile,
    ) {
        buildFileFilterButtonGroupUiState(
            fileAndFolder = displayEntries,
            filterFileTypes = filterFileTypes,
            filterFileExtensions = selectedFilterTypes,
            isHide = isHideFile,
        )
    }
    FileFilterButtons(
        uiState = FileFilterButtonsUiState(
            filterGroup = fileFilterButtonGroupUiState,
            sortType = fileFilterSortType,
            isHideFile = isHideFile,
            searchText = searchText,
            isSearchActive = isSearchActive,
            ignoreFileMenuItems = ignoreFileMenuItems,
        ),
        onFilterTypeChange = { isSelected, fileFilterType ->
            val changed = if (isSelected) {
                fileFilterState.filterFileExtensions.remove(fileFilterType)
            } else if (fileFilterType !in fileFilterState.filterFileExtensions) {
                fileFilterState.filterFileExtensions.add(fileFilterType)
            } else {
                false
            }
            if (changed) fileFilterState.updateFilerKey()
        },
        onSortChange = fileFilterState::updateSortType,
        onHideFileChange = fileFilterState::updateHideFile,
        onIgnoreFileToggle = fileFilterState::toggleIgnoreFile,
        onSearch = { query ->
            fileFilterState.updateSearchText(query)
            fileFilterState.updateSearch(query.isNotEmpty())
        },
    )

    val navigator = LocalAppNavigator.current
    val baseMenuPermission = deskType.menuPermission ?: DiskMenuPermission()
    val selectionDisallowsPaste = remember(selectedFiles) {
        selectedFiles.size > 1 || selectedFiles.any { item -> item.isDirectory }
    }
    val itemContentType = if (isFileGridView) FileListContentType.Grid else FileListContentType.List
    val thumbnailLoadRange = remember(lazyGridState, isFileGridView) {
        derivedStateOf {
            fileThumbnailLoadRange(
                visibleItemIndices = lazyGridState.layoutInfo.visibleItemsInfo.map { info -> info.index },
                isGrid = isFileGridView,
            )
        }
    }

    val pageException = exception.takeIf { isCurrentLocationContent }
    GridList(
        isLoading = isLoading && cachedEntries.isEmpty(),
        errorState = pageException?.toGridListErrorState(),
        isEmpty = pageException is EmptyDataException,
        emptyMessage = (pageException as? EmptyDataException)?.message,
        modifier = Modifier.fillMaxWidth(),
        state = lazyGridState,
        adaptiveMinCellSize = if (isFileGridView) 108.dp else null,
        verticalSpacing = 0.dp,
        horizontalSpacing = 0.dp,
        floatingActionButtonPadding = if (hasFloatingActionButton) GridListFabPadding else 0.dp
    ) {
        itemsIndexed(
            items = fileListEntries,
            key = { _, entry -> entry.operationKey },
            contentType = { _, _ -> itemContentType },
        ) { index, entry ->
            val item = entry.file
            val shouldLoadThumbnail = thumbnailLoadRange.value?.contains(index) == true
            val itemOperationKey = entry.operationKey
            val isSelected = itemOperationKey in selectedOperationKeys
            val operationStatus = when {
                itemOperationKey in deletingOperationKeys -> FileItemOperationStatus.Delete
                isSelected && isPasteCopyFile -> FileItemOperationStatus.Copy
                isSelected && isPasteMoveFile -> FileItemOperationStatus.Move
                else -> null
            }
            val hasMenuPermission = hasAnyFileCardMenuPermission(
                basePermission = baseMenuPermission,
                file = item,
                isFileChecked = isSelected,
                currentSelectionDisallowsPaste = selectionDisallowsPaste,
                isPasteCopyFile = isPasteCopyFile,
                isPasteMoveFile = isPasteMoveFile,
                hasNavigator = navigator != null,
            )
            val onMenuRequest: (() -> Unit)? = if (hasMenuPermission) {
                { menuTargetKey = itemOperationKey }
            } else {
                null
            }
            val isMenuTarget = menuTargetKey == itemOperationKey
            val toggleSelection = {
                homeState.clearAutoHighlightIfNeeded()
                val selectedIndex = homeState.checkedFileSimpleInfo.indexOfFirst { selectedItem ->
                    selectedItem.fileOperationKey() == itemOperationKey
                }
                if (selectedIndex >= 0) {
                    homeState.checkedFileSimpleInfo.removeAt(selectedIndex)
                } else {
                    homeState.checkedFileSimpleInfo.add(item)
                }
                Unit
            }
            val onClick = {
                if (isSelectionMode && !item.isDirectory) {
                    toggleSelection()
                } else {
                    scope.launch(Dispatchers.Default) {
                        fileRecentState.record(item)
                        if (item.isDirectory) {
                            fileState.updatePath(item.path)
                        } else {
                            homeState.openFile(item)
                        }
                    }
                }
                Unit
            }
            val onRemove = {
                scope.launch(Dispatchers.Default) {
                    val showSnackbar = snackbarHostState.showSnackbar(
                        message = item.name,
                        actionLabel = AppStrings.ui_delete,
                        withDismissAction = true,
                        duration = SnackbarDuration.Short
                    )
                    when (showSnackbar) {
                        SnackbarResult.Dismissed -> {}
                        SnackbarResult.ActionPerformed -> {
                            homeState.clearCheckedFiles()
                            fileState.deleteFile(
                                Task(
                                    taskType = TaskType.Delete,
                                    status = StatusEnum.LOADING,
                                    values = mapOf("path" to item.path),
                                    protocol = item.protocol,
                                    protocolId = item.protocolId,
                                ),
                                item
                            )
                        }
                    }
                }
                Unit
            }
            val anchoredMenuContent: @Composable (() -> Unit)? = if (isMenuTarget) {
                {
                    val menuPermission = resolveFileCardMenuPermission(
                        basePermission = baseMenuPermission,
                        file = item,
                        isFileChecked = isSelected,
                        currentSelectionDisallowsPaste = selectionDisallowsPaste,
                        isPasteCopyFile = isPasteCopyFile,
                        isPasteMoveFile = isPasteMoveFile,
                        hasNavigator = navigator != null,
                    )
                    FileMenu(
                        permission = menuPermission,
                        isFavorite = currentFavorites.contains(item.path),
                        showTrigger = false,
                        expanded = true,
                        onExpandedChange = { expanded ->
                            if (!expanded && menuTargetKey == itemOperationKey) {
                                menuTargetKey = null
                            }
                        },
                        onPaste = {
                            scope.launch {
                                if (isPasteCopyFile) homeState.pasteCopyFile(item)
                                if (isPasteMoveFile) homeState.pasteMoveFile(item.path)
                            }
                        },
                        onCopy = {
                            if (isPasteCopyFile) homeState.cancelCopyFile()
                            if (isPasteMoveFile) homeState.cancelMoveFile()
                            homeState.clearCheckedFiles()
                            homeState.checkedFileSimpleInfo.add(item)
                            homeState.copyFile()
                        },
                        onMove = {
                            if (isPasteCopyFile) homeState.cancelCopyFile()
                            if (isPasteMoveFile) homeState.cancelMoveFile()
                            homeState.clearCheckedFiles()
                            homeState.checkedFileSimpleInfo.add(item)
                            homeState.moveFile()
                        },
                        onDelete = onRemove,
                        onRead = {
                            navigator?.push(
                                FileEditorScreen(
                                    file = item,
                                    canWrite = menuPermission.write,
                                )
                            )
                        },
                        onRename = {
                            homeState.updateFileInfo(item)
                            homeState.updateRenameFile(true)
                        },
                        onFavorite = {
                            scope.launch { fileFavoriteState.toggleFavorite(item) }
                        },
                        onShare = {
                            fileShareState.updateIncomingFiles(listOf(item))
                            navigator?.push(FileShareScreen)
                        },
                        onInfo = {
                            homeState.updateFileInfo(item)
                            homeState.updateViewFile(true)
                        },
                    )
                }
            } else {
                null
            }
            val cardTrailingContent: @Composable (() -> Unit)? = if (onMenuRequest != null) {
                {
                    Box {
                        FileMenuTrigger(onClick = onMenuRequest)
                        anchoredMenuContent?.invoke()
                    }
                }
            } else {
                null
            }

            if (isFileGridView) {
                FileGridCard(
                    file = item,
                    isSelected = isSelected,
                    isSelectionMode = isSelectionMode,
                    operationStatus = operationStatus,
                    thumbnailLoader = thumbnailLoader,
                    loadThumbnail = shouldLoadThumbnail && !isSelectionMode,
                    modifier = Modifier
                        .fillMaxWidth()
                        .aspectRatio(0.95f),
                    onToggleSelect = toggleSelection,
                    onClick = onClick,
                    onLongClick = onMenuRequest,
                    menuContent = anchoredMenuContent,
                )
            } else {
                FileCard(
                    file = item,
                    isSelected = isSelected,
                    isSelectionMode = isSelectionMode,
                    isFavorite = currentFavorites.contains(item.path),
                    operationStatus = operationStatus,
                    thumbnailLoader = thumbnailLoader,
                    loadThumbnail = shouldLoadThumbnail && !isSelectionMode,
                    modifier = Modifier,
                    onToggleSelect = toggleSelection,
                    onClick = onClick,
                    onLongClick = onMenuRequest,
                    trailingContent = cardTrailingContent,
                )
            }
        }
    }

    if (isRenameFile && fileInfo != null) {
        FileRenameDialog(fileInfo!!, { item ->
            VerificationUtils.folder(item, fileState.fileAndFolder, listOf(fileInfo!!.name))
        }) { item ->
            homeState.updateRenameFile(false)
            if (item.isEmpty()) {
                homeState.updateFileInfo(null)
                return@FileRenameDialog
            }
            scope.launch(Dispatchers.Default) {
                fileState.rename(path, fileInfo!!.name, item)
                    .onSuccess {
                        fileFilterState.updateFilerKey()
                        homeState.updateFileInfo(null)
                    }
                    .onFailure { throwable ->
                        snackbarHostState.showSnackbar(
                            message = throwable.message ?: AppStrings.ui_creation_failed,
                            withDismissAction = true,
                            duration = SnackbarDuration.Short
                        )
                    }
            }
        }
    }
    if (isViewFile && fileInfo != null) {
        FileInfoDialogContainer(
            fileInfo = fileInfo!!,
            fileState = fileState,
            fileFilterState = fileFilterState,
            onCancel = {
                homeState.updateFileInfo(null)
                homeState.updateViewFile(false)
            },
        )
    }

    if (isRemoteOpenConfirmDialog && remoteOpenFile != null) {
        RemoteOpenConfirmDialog(
            onConfirm = { dontShowAgain -> homeState.confirmRemoteOpen(dontShowAgain, true) },
            onCancel = { dontShowAgain -> homeState.confirmRemoteOpen(dontShowAgain, false) }
        )
    }

    if (isDeviceDropUploadDialog && deviceDropUploadRequest != null) {
        DeviceDropUploadConfirmDialog(
            fileCount = deviceDropUploadRequest!!.sourcePaths.size,
            targetPath = deviceDropUploadRequest!!.targetPath,
            onConfirm = { homeState.confirmDeviceDropUpload(true) },
            onCancel = { homeState.confirmDeviceDropUpload(false) }
        )
    }

}

internal fun shouldLoadFileThumbnail(
    itemIndex: Int,
    visibleItemIndices: List<Int>,
    isGrid: Boolean,
): Boolean = fileThumbnailLoadRange(visibleItemIndices, isGrid)?.contains(itemIndex) == true

private fun fileThumbnailLoadRange(
    visibleItemIndices: List<Int>,
    isGrid: Boolean,
): IntRange? {
    if (visibleItemIndices.isEmpty()) return null
    val firstVisible = visibleItemIndices.minOrNull() ?: return null
    val lastVisible = visibleItemIndices.maxOrNull() ?: return null
    val aheadItems = if (isGrid) maxOf(1, visibleItemIndices.size / 4) else 1
    return firstVisible..(lastVisible + aheadItems)
}

@Composable
private fun FileInfoDialogContainer(
    fileInfo: FileSimpleInfo,
    fileState: FileState,
    fileFilterState: FileFilterState,
    onCancel: () -> Unit,
) {
    val rootPath by fileState.rootPath.collectAsState()
    var errorText by remember(fileInfo.path) { mutableStateOf("") }
    var propertySummary by remember(fileInfo.path) {
        mutableStateOf(
            FilePropertySummary(
                totalSize = if (fileInfo.isDirectory) 0L else fileInfo.size,
                fileCount = if (fileInfo.isDirectory) 0 else 1,
            )
        )
    }
    var isTraversing by remember(fileInfo.path) { mutableStateOf(false) }
    var fullInfo by remember(fileInfo.path) { mutableStateOf<FileInfo?>(null) }
    var fullInfoError by remember(fileInfo.path) { mutableStateOf("") }

    LaunchedEffect(fileInfo) {
        errorText = ""
        propertySummary = FilePropertySummary(
            totalSize = if (fileInfo.isDirectory) 0L else fileInfo.size,
            fileCount = if (fileInfo.isDirectory) 0 else 1,
        )
        if (fileInfo.isDirectory) {
            isTraversing = true
            try {
                fileState.summarizeFileProperties(fileInfo) { summary ->
                    propertySummary = summary
                }.onFailure { throwable ->
                    errorText = throwable.message ?: AppStrings.ui_operation_failed
                }
            } finally {
                isTraversing = false
            }
        }
    }

    LaunchedEffect(fileInfo.path) {
        fullInfoError = ""
        fileState.getFileInfo(fileInfo.path)
            .onSuccess { info -> fullInfo = info }
            .onFailure { throwable ->
                fullInfoError = throwable.message ?: AppStrings.ui_operation_failed
            }
    }

    val fileTypeText = remember(fileInfo, fileFilterState.filterFileTypes) {
        if (fileInfo.isDirectory) {
            AppStrings.ui_folder
        } else {
            val filterName = fileFilterState.filterFileTypes
                .getFilterByExtension(fileInfo.mineType)
                ?.name
                .orEmpty()
            AppStrings.ui_file_arg0.format(arg0 = filterName.takeIf(String::isNotEmpty)?.let { "($it)" }.orEmpty())
        }
    }
    FileInfoDialog(
        uiState = FileInfoDialogUiState(
            fileInfo = fileInfo,
            totalSize = propertySummary.totalSize,
            freeSpace = rootPath.freeSpace,
            totalSpace = rootPath.totalSpace,
            fileCount = propertySummary.fileCount,
            folderCount = propertySummary.folderCount,
            isTraversing = isTraversing,
            errorText = errorText,
            fullInfo = fullInfo,
            fullInfoError = fullInfoError,
            fileTypeText = fileTypeText,
            skippedItemCount = propertySummary.skippedItemCount,
            scanIssues = propertySummary.scanIssues,
        ),
        onCancel = onCancel,
    )
}


@Composable
fun FileFilterButtons(
    uiState: FileFilterButtonsUiState,
    onFilterTypeChange: (Boolean, FileFilterType) -> Unit,
    onSortChange: (FileFilterSort) -> Unit,
    onHideFileChange: (Boolean) -> Unit,
    onIgnoreFileToggle: (String) -> Unit,
    onSearch: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    var showSearchDialog by remember { mutableStateOf(false) }
    var searchDraft by remember { mutableStateOf("") }

    Row(modifier) {
        FileFilterButtonGroup(
            uiState = uiState.filterGroup,
            onCheckedFileFilterTypeChange = onFilterTypeChange,
            modifier = Modifier.weight(1f)
        )

        Row(Modifier.padding(end = 6.dp)) {
            val isSearchChecked = uiState.isSearchActive && uiState.searchText.isNotEmpty()
            val isSortButtonActive = isFileSortButtonActive(
                sortType = uiState.sortType,
                isHideFile = uiState.isHideFile,
                hasActiveIgnoreFile = uiState.ignoreFileMenuItems.any { item -> item.enabled },
            )

            if (isSearchChecked) {
                FilledIconButton(onClick = {
                    searchDraft = uiState.searchText
                    showSearchDialog = true
                }) {
                    Icon(
                        imageVector = Icons.Default.Search,
                        contentDescription = AppStrings.ui_search
                    )
                }
            } else {
                IconButton(onClick = {
                    searchDraft = uiState.searchText
                    showSearchDialog = true
                }) {
                    Icon(
                        imageVector = Icons.Default.Search,
                        contentDescription = AppStrings.ui_search
                    )
                }
            }

            SortButton(
                sortType = uiState.sortType,
                onUpdateSort = onSortChange,
                isFilled = isSortButtonActive,
                isHideFile = uiState.isHideFile,
                onHideFileChange = onHideFileChange,
                ignoreFileMenuItems = uiState.ignoreFileMenuItems,
                onIgnoreFileToggle = onIgnoreFileToggle,
            )
        }
    }

    if (showSearchDialog) {
        SearchDialog(
            title = AppStrings.ui_search_files,
            query = searchDraft,
            onQueryChange = { item -> searchDraft = item },
            onConfirm = {
                val trimmed = searchDraft.trim()
                onSearch(trimmed)
                showSearchDialog = false
            },
            onDismissRequest = { showSearchDialog = false },
            confirmText = AppStrings.ui_search,
            dismissText = AppStrings.ui_reset,
            onDismissButtonClick = {
                onSearch("")
                searchDraft = ""
                showSearchDialog = false
            },
            label = AppStrings.ui_enter_search_keywords,
            leadingIcon = null,
            onClear = { searchDraft = "" }
        )
    }
}

internal fun isFileSortButtonActive(
    sortType: FileFilterSort,
    isHideFile: Boolean,
    hasActiveIgnoreFile: Boolean,
): Boolean {
    return sortType != FileFilterSort.NameAsc || isHideFile || hasActiveIgnoreFile
}
