package com.folderspan.ui.screen.main

import strings.AppStrings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.folderspan.clipboard.ClipboardFilePasteFeedbackBus
import com.folderspan.clipboard.ClipboardTextPasteBus
import com.folderspan.clipboard.PlatformClipboardFilePasteEffect
import com.folderspan.clipboard.clipboardFilePasteFeedbackMessage
import com.folderspan.clipboard.rememberClipboardFilePasteController
import com.folderspan.data.StatusEnum
import com.folderspan.data.file.FileProtocol
import com.folderspan.data.file.FileSimpleInfo
import com.folderspan.data.file.toIcon
import com.folderspan.data.main.DiskMenuPermission
import com.folderspan.extensions.fileOperationKey
import com.folderspan.extensions.getFilterByExtension
import com.folderspan.extensions.parsePath
import com.folderspan.ui.components.showLatestSnackbar
import com.folderspan.ui.components.appbar.AppBarPathContainer
import com.folderspan.ui.components.dialog.*
import com.folderspan.ui.components.file.FileIcon
import com.folderspan.ui.components.file.FileMenu
import com.folderspan.ui.components.scaffold.AppScaffold
import com.folderspan.ui.navigation.LocalAppNavigator
import com.folderspan.ui.navigation.currentOrThrow
import com.folderspan.ui.screen.file.FileScreen
import com.folderspan.ui.screen.file.share.FileShareScreen
import com.folderspan.ui.state.file.FileFavoriteState
import com.folderspan.ui.state.file.FileFilterState
import com.folderspan.ui.state.file.FileOperationState
import com.folderspan.ui.state.file.FilePropertySummary
import com.folderspan.ui.state.file.FileShareState
import com.folderspan.ui.state.file.FileState
import com.folderspan.ui.state.main.*
import com.folderspan.utils.VerificationUtils
import com.folderspan.ui.navigation.AppScreenRoute
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.koin.compose.koinInject
import com.folderspan.proNotificationUnreadCount

object HomeScreen : AppScreenRoute {
    @Composable
    override fun Content() {
        val scope = rememberCoroutineScope()

        val mainState = koinInject<MainState>()
        val editPath by mainState.isEditPath.collectAsState()
        val expandDrawer by mainState.isExpandDrawer.collectAsState()
        val notificationState = koinInject<NotificationState>()
        val accountUnreadCount = proNotificationUnreadCount()
        val notificationRevision = notificationState.revision
        val localUnreadCount = remember(notificationRevision) {
            notificationState.notifications.count { item -> !item.isRead && item.shouldShowInBell() }
        }
        val unreadCount = notificationBellUnreadCount(
            localUnread = localUnreadCount,
            accountUnread = accountUnreadCount,
        )

        val fileState = koinInject<FileState>()
        val homeState = koinInject<HomeState>()
        val path by fileState.path.collectAsState()
        val isCreateFolder by homeState.isCreateFolder.collectAsState()
        val isRenameFile by homeState.isRenameFile.collectAsState()
        val isViewFile by homeState.isViewFile.collectAsState()
        val isRemoteOpenConfirmDialog by homeState.isRemoteOpenConfirmDialog.collectAsState()
        val isDeviceDropUploadDialog by homeState.isDeviceDropUploadDialog.collectAsState()
        val deskType by fileState.deskType.collectAsState()
        val canWriteCurrentDesk = deskType.menuPermission?.write == true

        val fileFilterState = koinInject<FileFilterState>()
        val isSearchActive by fileFilterState.isSearchText.collectAsState()
        val fileOperationState = koinInject<FileOperationState>()
        val isWarningOperationDialog by fileOperationState.isWarningOperationDialog.collectAsState()
        val clipboardFilePasteController = rememberClipboardFilePasteController(fileState, fileOperationState)

        val snackbarHostState = remember { SnackbarHostState() }
        val isEditableOrModalContext = editPath ||
            isCreateFolder ||
            isSearchActive ||
            isRenameFile ||
            isViewFile ||
            isRemoteOpenConfirmDialog ||
            isDeviceDropUploadDialog ||
            isWarningOperationDialog
        val clipboardPasteEnabled = !isEditableOrModalContext
        PlatformClipboardFilePasteEffect(
            enabled = clipboardPasteEnabled,
            onPasteRequest = clipboardFilePasteController::pasteFiles,
            onTextPasteRequest = ClipboardTextPasteBus::publish,
        )
        LaunchedEffect(snackbarHostState) {
            ClipboardFilePasteFeedbackBus.events.collect { result ->
                snackbarHostState.showLatestSnackbar(
                    message = clipboardFilePasteFeedbackMessage(result),
                    withDismissAction = true,
                    duration = SnackbarDuration.Short,
                )
            }
        }
        AppScaffold(
            topBar = {
                HomeTopBar(
                    isDrawerExpanded = expandDrawer,
                    unreadCount = unreadCount,
                    onToggleDrawer = { mainState.updateExpandDrawer(!expandDrawer) },
                    onEditPath = { mainState.updateEditPath(true) },
                    title = { AppBarPathContainer() },
                )
            },
            snackbarHost = { SnackbarHost(snackbarHostState) },
            bottomBar = { HomeBottomBarContainer(snackbarHostState) },
            floatingActionButton = {
                if (homeState.checkedFileSimpleInfo.isEmpty() && canWriteCurrentDesk) {
                    ExtendedFloatingActionButton(
                        onClick = { homeState.updateCreateFolder(true) },
                        icon = { Icon(Icons.Filled.Add, contentDescription = null) },
                        text = { Text(AppStrings.ui_create_new_action) }
                    )
                }
            }
        ) { item ->
            Column(Modifier.padding(item)) {
                FileScreen(snackbarHostState)
            }
        }

        if (isCreateFolder) {
            TextFieldWithTypeDialog(
                title = AppStrings.ui_add_new_items,
                label = AppStrings.ui_name,
                initType = FileType.FOLDER,
                verifyFun = { text, type ->
                    when (type) {
                        FileType.FOLDER -> VerificationUtils.folder(text, fileState.fileAndFolder)
                        FileType.FILE -> VerificationUtils.file(text, fileState.fileAndFolder)
                    }
                },
                onConfirm = { name, type ->
                    homeState.updateCreateFolder(false)
                    scope.launch(Dispatchers.Default) {
                        when (type) {
                            FileType.FOLDER -> {
                                fileState.createFolder(path, name)
                                    .onSuccess {
                                        fileState.updateFileAndFolder()
                                    }
                                    .onFailure { throwable ->
                                        snackbarHostState.showLatestSnackbar(
                                            message = throwable.message ?: AppStrings.ui_creation_failed,
                                            withDismissAction = true,
                                            duration = SnackbarDuration.Short
                                        )
                                    }
                            }

                            FileType.FILE -> {
                                fileState.createFile(path, name)
                                    .onSuccess {
                                        fileState.updateFileAndFolder()
                                    }
                                    .onFailure { throwable ->
                                        snackbarHostState.showLatestSnackbar(
                                            message = throwable.message ?: AppStrings.ui_creation_failed,
                                            withDismissAction = true,
                                            duration = SnackbarDuration.Short
                                        )
                                    }
                            }
                        }
                    }
                },
                onCancel = {
                    homeState.updateCreateFolder(false)
                }
            )
        }

        if (editPath) {
            val childDirectoryPaths = fileState.fileAndFolder
                .filter { item -> item.isDirectory }
                .map { item -> item.path }

            PathDropdownDialog(
                initText = path,
                directoryPaths = childDirectoryPaths,
                pathSeparator = deskType.pathSeparator.ifBlank { "/" },
                loadDirectoryPaths = { directoryPath ->
                    fileState.getFileAndFolder(directoryPath).map { files ->
                        files.filter { item -> item.isDirectory }.map { item -> item.path }
                    }
                },
                onConfirm = { item ->
                    mainState.updateEditPath(false)
                    if (item.isEmpty()) return@PathDropdownDialog
                    if (item.parsePath().isNotEmpty()) {
                        scope.launch(Dispatchers.Default) {
                            val target = fileState.getFile(item).getOrNull() ?: return@launch
                            if (target.isDirectory) {
                                homeState.clearAutoHighlightIfNeeded()
                                fileState.updatePath(target.path)
                            } else {
                                homeState.openPathWithHighlight(target.path)
                            }
                        }
                    }
                },
                onCancel = {
                    mainState.updateEditPath(false)
                }
            )
        }

        if (isWarningOperationDialog) {
            val conflictOperations = fileOperationState.files.filter { operation -> operation.isConflict }
            FileWarningOperationDialog(
                fileOperations = conflictOperations,
                onOperationTypeChange = { operation, operationType ->
                    fileOperationState.files.indexOf(operation)
                        .takeIf { index -> index >= 0 }
                        ?.let { index ->
                            fileOperationState.files[index] = operation.withCopy(type = operationType)
                        }
                },
                onConfirm = { fileOperationState.updateWarningOperationDialog(false) },
                onCancel = {
                    fileOperationState.files.clear()
                    fileOperationState.updateWarningOperationDialog(false)
                },
            )
        }
    }

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    fun HomeTopBar(
        isDrawerExpanded: Boolean,
        unreadCount: Int,
        onToggleDrawer: () -> Unit,
        onEditPath: () -> Unit,
        title: @Composable () -> Unit,
        modifier: Modifier = Modifier,
    ) {
        TopAppBar(
            modifier = modifier,
            title = title,
            navigationIcon = {
                IconButton(onClick = onToggleDrawer) {
                    BadgedBox(badge = {
                        if (!isDrawerExpanded && unreadCount > 0) {
                            Badge { Text(if (unreadCount > 99) "99+" else unreadCount.toString()) }
                        }
                    }) {
                        Icon(if (isDrawerExpanded) Icons.Default.Close else Icons.Default.Menu, null)
                    }
                }
            },
            actions = {
                IconButton(onClick = onEditPath) {
                    Icon(Icons.Default.Edit, null)
                }
            }
        )
    }

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    internal fun HomeBottomBarContainer(
        snackbarHostState: SnackbarHostState,
        previewState: HomeBottomBarPreviewState? = null,
    ) {
        if (previewState != null) {
            HomeBottomBarContent(
                selectedFileCount = previewState.selectedFiles.size,
                isCheckedAll = previewState.isCheckedAll,
                isPasteCopyFile = previewState.isPasteCopyFile,
                isPasteMoveFile = previewState.isPasteMoveFile,
                canPasteIntoCurrent = previewState.canPasteIntoCurrent,
                hasMenuPermission = previewState.hasMenuPermission,
                canCreateInCurrentDesk = previewState.canCreateInCurrentDesk,
                onToggleSelectAll = {},
                onSelectedFilesClick = {},
                onPasteClick = {},
                onCancelClick = {},
                onCreateClick = {},
                menuContent = {
                    FileMenu(
                        permission = previewState.menuPermission,
                        onCopy = {},
                        onMove = {},
                        onDelete = {},
                        onFavorite = {},
                        onShare = {},
                        onInfo = {}
                    )
                }
            )
            return
        }

        val scope = rememberCoroutineScope()

        val homeState = koinInject<HomeState>()

        if (homeState.checkedFileSimpleInfo.isEmpty()) {
            return
        }

        val fileState = koinInject<FileState>()
        val path by fileState.path.collectAsState()
        val isPasteCopyFile by homeState.isPasteCopyFile.collectAsState()
        val isPasteMoveFile by homeState.isPasteMoveFile.collectAsState()
        val deskType by fileState.deskType.collectAsState()
        val basePermission = deskType.menuPermission ?: DiskMenuPermission()
        val canWriteCurrentDesk = basePermission.write
        val canPasteIntoCurrent = basePermission.paste

        val fileFilterState = koinInject<FileFilterState>()
        val fileFavoriteState = koinInject<FileFavoriteState>()
        val fileShareState = koinInject<FileShareState>()
        val navigator = LocalAppNavigator.currentOrThrow
        val currentFavorites by fileFavoriteState.currentFavorites.collectAsState()
        val updateKey by fileFilterState.updateKey.collectAsState()

        val sheetState = rememberBottomSheetState(
            initialValue = SheetValue.Hidden,
            enabledValues = setOf(SheetValue.Hidden, SheetValue.Expanded),
        )
        var showSelectedFilesSheet by remember { mutableStateOf(false) }
        val selectedFiles by remember(homeState) {
            derivedStateOf { homeState.checkedFileSimpleInfo.toList() }
        }
        var batchInfoFiles by remember { mutableStateOf<List<FileSimpleInfo>>(emptyList()) }

        LaunchedEffect(selectedFiles.size) {
            if (selectedFiles.isEmpty() && showSelectedFilesSheet) {
                if (sheetState.isVisible) {
                    sheetState.hide()
                }
                showSelectedFilesSheet = false
            }
        }

        val currentEntries by remember(fileState) {
            derivedStateOf { fileState.fileAndFolder.toList() }
        }
        val files = remember(currentEntries, updateKey) {
            fileFilterState.filter(currentEntries, updateKey)
        }
        val selectedFileSet = remember(selectedFiles) { selectedFiles.toSet() }
        val isCheckedAll = selectedFiles.size == files.size && selectedFileSet.containsAll(files)
        val selectionProtocol = selectedFiles.firstOrNull()?.protocol
        val allSameProtocol = selectionProtocol != null && selectedFiles.all { item -> item.protocol == selectionProtocol }
        val applyPasteState = !allSameProtocol || selectionProtocol != FileProtocol.Share
        val menuPermission = DiskMenuPermission(
            read = false,
            write = false,
            paste = false,
            copy = basePermission.copy && (!applyPasteState || (!isPasteCopyFile && !isPasteMoveFile)),
            move = basePermission.move && (!applyPasteState || (!isPasteCopyFile && !isPasteMoveFile)),
            delete = basePermission.delete,
            rename = false,
            setting = false,
            favorite = basePermission.favorite,
            share = basePermission.share,
            info = basePermission.info,
        )
        val hasMenuPermission = menuPermission.hasAnyPermission()
        val isAllFavorite = selectedFiles.isNotEmpty() &&
            selectedFiles.all { item -> currentFavorites.contains(item.path) }
        if (showSelectedFilesSheet) {
            ModalBottomSheet(
                onDismissRequest = {
                    scope.launch {
                        if (sheetState.isVisible) {
                            sheetState.hide()
                        }
                        showSelectedFilesSheet = false
                    }
                },
                sheetState = sheetState
            ) {
                Column(Modifier.fillMaxWidth()) {
                    Text(text = AppStrings.ui_selected_item_count_arg0.format(arg0 = (selectedFiles.size).toString()), modifier = Modifier.padding(horizontal = 16.dp))
                    Spacer(Modifier.height(16.dp))
                    LazyColumn(modifier = Modifier.fillMaxWidth()) {
                        items(
                            items = selectedFiles,
                            key = { item -> item.fileOperationKey() },
                            contentType = { "selected-file" },
                        ) { file ->
                            val headline = file.name.ifEmpty { file.path }
                            val accessibilityDescription = remember(headline, file.path) {
                                if (file.name.isEmpty()) headline else "$headline, ${file.path}"
                            }
                            ListItem(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .semantics {
                                        contentDescription = accessibilityDescription
                                    },
                                headlineContent = {
                                    Text(
                                        text = headline,
                                        modifier = Modifier.clearAndSetSemantics {},
                                    )
                                },
                                supportingContent = {
                                    Row(
                                        modifier = Modifier.clearAndSetSemantics {},
                                        verticalAlignment = Alignment.CenterVertically,
                                    ) {
                                        file.protocol.toIcon()

                                        if (file.name.isNotEmpty()) {
                                            Text(
                                                text = file.path,
                                                style = MaterialTheme.typography.bodySmall
                                            )
                                        }
                                    }
                                },
                                leadingContent = { FileIcon(file) },
                                trailingContent = {
                                    IconButton(
                                        onClick = { homeState.checkedFileSimpleInfo.remove(file) },
                                        modifier = Modifier.semantics {
                                            contentDescription = AppStrings.ui_remove
                                        },
                                    ) {
                                        Icon(Icons.Default.Delete, contentDescription = null)
                                    }
                                }
                            )
                        }
                    }
                }
            }
        }


        HomeBottomBarContent(
            selectedFileCount = selectedFiles.size,
            isCheckedAll = isCheckedAll,
            isPasteCopyFile = isPasteCopyFile,
            isPasteMoveFile = isPasteMoveFile,
            canPasteIntoCurrent = canPasteIntoCurrent,
            hasMenuPermission = hasMenuPermission,
            canCreateInCurrentDesk = canWriteCurrentDesk,
            onToggleSelectAll = {
                if (isPasteCopyFile) homeState.cancelCopyFile()
                if (isPasteMoveFile) homeState.cancelMoveFile()
                homeState.clearCheckedFiles()
                if (!isCheckedAll) {
                    homeState.checkedFileSimpleInfo.addAll(files)
                }
            },
            onSelectedFilesClick = {
                if (showSelectedFilesSheet) {
                    scope.launch {
                        if (sheetState.isVisible) {
                            sheetState.hide()
                        }
                        showSelectedFilesSheet = false
                    }
                } else {
                    showSelectedFilesSheet = true
                }
            },
            onPasteClick = {
                scope.launch(Dispatchers.Default) {
                    if (isPasteCopyFile) {
                        val fileSimpleInfo = fileState.getFile(path).getOrNull() ?: return@launch
                        homeState.pasteCopyFile(fileSimpleInfo)
                    }
                    if (isPasteMoveFile) {
                        homeState.pasteMoveFile(path)
                    }
                }
            },
            onCancelClick = {
                if (isPasteCopyFile) homeState.cancelCopyFile()
                if (isPasteMoveFile) homeState.cancelMoveFile()
                homeState.clearCheckedFiles()
            },
            onCreateClick = { homeState.updateCreateFolder(true) },
            menuContent = {
                FileMenu(
                    permission = menuPermission,
                    isFavorite = isAllFavorite,
                    onCopy = { homeState.copyFile() },
                    onMove = { homeState.moveFile() },
                    onDelete = {
                        val filesToDelete = selectedFiles.toList()
                        if (filesToDelete.isEmpty()) {
                            return@FileMenu
                        }

                        scope.launch(Dispatchers.Default) {
                            when (snackbarHostState.showLatestSnackbar(
                                message = AppStrings.ui_you_sure_you_want_delete_selected_files_folders,
                                actionLabel = AppStrings.ui_delete,
                                withDismissAction = true,
                                duration = SnackbarDuration.Short
                            )) {
                                SnackbarResult.Dismissed -> {}
                                SnackbarResult.ActionPerformed -> {
                                    for (item in filesToDelete) {
                                        fileState.deleteFile(
                                            Task(
                                                taskType = TaskType.Delete,
                                                status = StatusEnum.LOADING,
                                                values = mapOf("path" to item.path),
                                                protocol = item.protocol,
                                                protocolId = item.protocolId,
                                            ),
                                            item,
                                        )
                                    }
                                    homeState.clearCheckedFiles()
                                }
                            }
                        }

                    },
                    onFavorite = {
                        val selectedFilesSnapshot = selectedFiles.toList()
                        if (selectedFilesSnapshot.isEmpty()) return@FileMenu
                        scope.launch {
                            val favorites = fileFavoriteState.currentFavorites.value
                            val shouldRemove = selectedFilesSnapshot.all { item -> favorites.contains(item.path) }
                            if (shouldRemove) {
                                selectedFilesSnapshot.forEach { item -> fileFavoriteState.removeFavorite(item) }
                            } else {
                                selectedFilesSnapshot.forEach { file ->
                                    if (!favorites.contains(file.path)) fileFavoriteState.addFavorite(file)
                                }
                            }
                        }
                    },
                    onShare = {
                        fileShareState.updateIncomingFiles(selectedFiles.toList())
                        navigator.push(FileShareScreen)
                    },
                    onInfo = {
                        val files = selectedFiles.toList()
                        if (files.isEmpty()) return@FileMenu
                        if (files.size == 1) {
                            homeState.updateFileInfo(files.first())
                            homeState.updateViewFile(true)
                        } else {
                            batchInfoFiles = files
                        }
                    }
                )
            },
        )

        if (batchInfoFiles.isNotEmpty()) {
            BatchFileInfoDialogContainer(
                fileInfos = batchInfoFiles,
                selectionPath = path,
                fileState = fileState,
                fileFilterState = fileFilterState,
                onCancel = { batchInfoFiles = emptyList() },
            )
        }
    }

    @Composable
    private fun BatchFileInfoDialogContainer(
        fileInfos: List<FileSimpleInfo>,
        selectionPath: String,
        fileState: FileState,
        fileFilterState: FileFilterState,
        onCancel: () -> Unit,
    ) {
        val rootPath by fileState.rootPath.collectAsState()
        val dialogKey = remember(fileInfos) { fileInfos.joinToString(separator = "|") { item -> item.path } }
        val selectedFileCount = remember(fileInfos) { fileInfos.count { item -> !item.isDirectory } }
        val selectedFolderCount = remember(fileInfos) { fileInfos.count { item -> item.isDirectory } }
        var errorText by remember(dialogKey) { mutableStateOf("") }
        var propertySummary by remember(dialogKey) {
            mutableStateOf(
                FilePropertySummary(
                    fileCount = selectedFileCount,
                    folderCount = selectedFolderCount,
                )
            )
        }
        var isTraversing by remember(dialogKey) { mutableStateOf(false) }

        LaunchedEffect(dialogKey) {
            errorText = ""
            propertySummary = FilePropertySummary(
                fileCount = selectedFileCount,
                folderCount = selectedFolderCount,
            )
            isTraversing = fileInfos.any { item -> item.isDirectory }
            try {
                fileState.summarizeFileProperties(fileInfos) { summary ->
                    propertySummary = summary
                }.onFailure { throwable ->
                    errorText = throwable.message ?: AppStrings.ui_operation_failed
                }
            } finally {
                isTraversing = false
            }
        }

        val typeText = remember(fileInfos, fileFilterState.filterFileTypes) {
            when {
                fileInfos.isEmpty() -> "-"
                selectedFolderCount == fileInfos.size -> if (fileInfos.size == 1) {
                    AppStrings.ui_folder
                } else {
                    AppStrings.ui_folder_arg0.format(arg0 = selectedFolderCount.toString())
                }
                selectedFileCount == fileInfos.size -> {
                    val filterNames = fileInfos.mapNotNull { item ->
                        fileFilterState.filterFileTypes.getFilterByExtension(item.mineType)?.name
                    }.distinct()
                    when {
                        filterNames.size == 1 ->
                            AppStrings.ui_file_filter_arg0.format(arg0 = filterNames.first())
                        fileInfos.size == 1 -> AppStrings.ui_file
                        else -> AppStrings.ui_file_arg0_item.format(arg0 = selectedFileCount.toString())
                    }
                }
                else -> AppStrings.ui_mixed_arg0_files_arg1_folders.format(
                    arg0 = selectedFileCount.toString(),
                    arg1 = selectedFolderCount.toString(),
                )
            }
        }
        BatchFileInfoDialog(
            uiState = BatchFileInfoDialogUiState(
                itemCount = fileInfos.size,
                selectionPath = selectionPath,
                typeText = typeText,
                totalSize = propertySummary.totalSize,
                freeSpace = rootPath.freeSpace,
                totalSpace = rootPath.totalSpace,
                fileCount = propertySummary.fileCount,
                folderCount = propertySummary.folderCount,
                isTraversing = isTraversing,
                errorText = errorText,
                skippedItemCount = propertySummary.skippedItemCount,
                scanIssues = propertySummary.scanIssues,
            ),
            onCancel = onCancel,
        )
    }
}

internal fun shouldHandleClipboardFilePasteShortcut(
    isFileBrowserActive: Boolean,
    isEditableContext: Boolean,
    isVKey: Boolean,
    isCtrlPressed: Boolean,
    isMetaPressed: Boolean,
    isAltPressed: Boolean,
    isShiftPressed: Boolean,
    isKeyDown: Boolean,
): Boolean = isFileBrowserActive &&
    !isEditableContext &&
    isVKey &&
    (isCtrlPressed || isMetaPressed) &&
    !isAltPressed &&
    !isShiftPressed &&
    isKeyDown

internal data class HomeBottomBarPreviewState(
    val selectedFiles: List<FileSimpleInfo>,
    val isCheckedAll: Boolean = false,
    val isPasteCopyFile: Boolean = false,
    val isPasteMoveFile: Boolean = false,
    val canPasteIntoCurrent: Boolean = false,
    val hasMenuPermission: Boolean = false,
    val canCreateInCurrentDesk: Boolean = false,
    val menuPermission: DiskMenuPermission = DiskMenuPermission(
        copy = true,
        move = true,
        delete = true,
        favorite = true,
        share = true,
        info = true,
    ),
)

@Composable
internal fun HomeBottomBarContent(
    selectedFileCount: Int,
    isCheckedAll: Boolean,
    isPasteCopyFile: Boolean,
    isPasteMoveFile: Boolean,
    canPasteIntoCurrent: Boolean,
    hasMenuPermission: Boolean,
    canCreateInCurrentDesk: Boolean,
    onToggleSelectAll: () -> Unit,
    onSelectedFilesClick: () -> Unit,
    onPasteClick: () -> Unit,
    onCancelClick: () -> Unit,
    onCreateClick: () -> Unit,
    modifier: Modifier = Modifier,
    menuContent: @Composable () -> Unit,
) {
    BottomAppBar(
        modifier = modifier,
        actions = {
            Row(
                modifier = Modifier.padding(16.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Box(
                    modifier = Modifier
                        .toggleable(
                            value = isCheckedAll,
                            onValueChange = { onToggleSelectAll() },
                            role = Role.Checkbox,
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Checkbox(isCheckedAll, onCheckedChange = null)
                        Text(
                            text = selectedFileCount.toString(),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurface
                        )

                        Icon(
                            imageVector = Icons.Default.ExpandLess,
                            modifier = Modifier.clickable(
                                enabled = selectedFileCount > 0,
                                onClick = onSelectedFilesClick
                            ),
                            contentDescription = null
                        )
                    }
                }
            }

            if ((isPasteCopyFile || isPasteMoveFile) && canPasteIntoCurrent) {
                IconButton(onClick = onPasteClick) {
                    Icon(Icons.Filled.ContentPaste, null)
                }
            }

            IconButton(onClick = onCancelClick) {
                Icon(Icons.Filled.Close, null)
            }

            Spacer(Modifier.weight(1f))

            if (hasMenuPermission) {
                menuContent()
            }

            Spacer(Modifier.width(8.dp))
        },
        floatingActionButton = {
            if (!canCreateInCurrentDesk) return@BottomAppBar
            FloatingActionButton(
                onClick = onCreateClick,
                containerColor = BottomAppBarDefaults.bottomAppBarFabColor,
                elevation = FloatingActionButtonDefaults.bottomAppBarFabElevation()
            ) {
                Icon(Icons.Filled.Add, null)
            }
        }
    )
}
