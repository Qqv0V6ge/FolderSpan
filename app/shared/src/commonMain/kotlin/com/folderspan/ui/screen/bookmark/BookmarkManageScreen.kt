package com.folderspan.ui.screen.bookmark

import strings.AppStrings

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Sort
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.rounded.DragHandle
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.folderspan.data.main.Local
import com.folderspan.data.main.device.Device
import com.folderspan.ui.components.confirmSnackbarAction
import com.folderspan.ui.components.showLatestSnackbar
import com.folderspan.ui.components.bookmark.BookmarkListItem
import com.folderspan.ui.components.dialog.BookmarkEditorDialog
import com.folderspan.ui.components.grid.GridList
import com.folderspan.ui.components.grid.GridListFabPadding
import com.folderspan.ui.components.scaffold.AppScaffold
import com.folderspan.ui.state.file.*
import com.folderspan.ui.navigation.AppScreenRoute
import com.folderspan.ui.navigation.LocalAppNavigator
import com.folderspan.ui.navigation.currentOrThrow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.koin.compose.koinInject
import sh.calvin.reorderable.ReorderableItem
import sh.calvin.reorderable.rememberReorderableLazyGridState

class BookmarkManageScreen : AppScreenRoute {
    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    override fun Content() {
        val navigator = LocalAppNavigator.currentOrThrow

        val bookmarkState = koinInject<FileBookmarkState>()
        val fileState = koinInject<FileState>()
        val deskType by fileState.deskType.collectAsState(initial = Local())

        val currentPath by fileState.path.collectAsState()

        LaunchedEffect(deskType) {
            bookmarkState.load()
        }

        var showAddDialog by rememberSaveable { mutableStateOf(false) }
        var editing by remember { mutableStateOf<DrawerBookmark?>(null) }
        var searchText by rememberSaveable { mutableStateOf("") }
        var searchDraft by rememberSaveable { mutableStateOf("") }
        var selectedType by rememberSaveable { mutableStateOf<DrawerBookmarkType?>(null) }
        var showFilters by rememberSaveable { mutableStateOf(false) }
        var isSelectionMode by remember { mutableStateOf(false) }
        val selectedIds = remember { mutableStateListOf<Long>() }

        fun toggleSelection(id: Long) {
            if (id in selectedIds) {
                selectedIds.remove(id)
            } else {
                selectedIds.add(id)
                isSelectionMode = true
            }
        }

        val sheetState = rememberBottomSheetState(
            initialValue = SheetValue.Hidden,
            enabledValues = setOf(SheetValue.Hidden, SheetValue.Expanded),
        )

        val snackbarHostState = remember { SnackbarHostState() }
        val scope = rememberCoroutineScope()

        // 直接使用数据库中的 FileBookmark 数据
        val bookmarkItems = bookmarkState.bookmarks
        val bookmarkIds by remember {
            derivedStateOf { bookmarkItems.map { item -> item.id } }
        }
        LaunchedEffect(bookmarkIds) {
            val existingIds = bookmarkIds.toSet()
            selectedIds.retainAll(existingIds)
        }
        val filteredItems by remember {
            derivedStateOf {
                val query = searchText.trim()
                bookmarkItems
                    .sortedBy { item -> item.sort }
                    .filter { bookmark ->
                        val matchesType = selectedType?.let { item -> bookmark.type == item } ?: true
                        val matchesQuery = query.isEmpty() || bookmark.name.contains(query, ignoreCase = true) ||
                                bookmark.path.contains(query, ignoreCase = true)
                        matchesType && matchesQuery
                    }
            }
        }

        val visibleBookmarkIds by remember {
            derivedStateOf { filteredItems.map { item -> item.id } }
        }
        val hasAllVisibleSelected by remember {
            derivedStateOf {
                visibleBookmarkIds.isNotEmpty() && visibleBookmarkIds.all { id -> id in selectedIds }
            }
        }
        val typeOptions = remember { listOf<DrawerBookmarkType?>(null) + DrawerBookmarkType.entries }

        var isSortMode by remember { mutableStateOf(false) }

        val lazyGridState = rememberLazyGridState()

        val reorderableLazyListState = rememberReorderableLazyGridState(lazyGridState) { from, to ->
            val drawerBookmarks = bookmarkItems.toMutableList().apply {
                add(to.index, removeAt(from.index))
            }
            bookmarkItems.clear()
            bookmarkItems.addAll(drawerBookmarks)
        }

        AppScaffold(
            topBar = {
                TopAppBar(
                    title = {
                        Text(
                            when {
                                isSelectionMode -> AppStrings.ui_selected_item_count_arg0.format(arg0 = (selectedIds.size).toString())
                                isSortMode -> AppStrings.ui_drag_sort
                                deskType is Device -> AppStrings.ui_arg0_bookmark_management.format(arg0 = (deskType as Device).name)
                                else -> AppStrings.ui_bookmark_management
                            },
                        )
                    },
                    navigationIcon = {
                        IconButton(onClick = {
                            when {
                                isSelectionMode -> {
                                    selectedIds.clear()
                                    isSelectionMode = false
                                }
                                isSortMode -> {
                                    // 取消排序，重新加载数据
                                    scope.launch(Dispatchers.Default) {
                                        bookmarkState.load()
                                    }
                                    isSortMode = false
                                }

                                else -> navigator.pop()
                            }
                        }) {
                            Icon(
                                imageVector = when {
                                    isSelectionMode -> Icons.Default.Close
                                    isSortMode -> Icons.Default.Close
                                    else -> Icons.AutoMirrored.Filled.ArrowBack
                                },
                                contentDescription = null
                            )
                        }
                    },
                    actions = {
                        if (isSelectionMode) {
                            if (visibleBookmarkIds.isNotEmpty()) {
                                IconButton(
                                    onClick = {
                                        if (hasAllVisibleSelected) {
                                            selectedIds.removeAll(visibleBookmarkIds.toSet())
                                        } else {
                                            visibleBookmarkIds.forEach { id ->
                                                if (id !in selectedIds) selectedIds.add(id)
                                            }
                                        }
                                    },
                                ) {
                                    Icon(
                                        Icons.Default.DoneAll,
                                        contentDescription = if (hasAllVisibleSelected) {
                                            AppStrings.ui_deselect_all
                                        } else {
                                            AppStrings.ui_select_all
                                        },
                                    )
                                }
                            }
                        } else if (!isSortMode) {
                            IconButton({
                                isSortMode = true
                            }) {
                                Icon(Icons.AutoMirrored.Filled.Sort, contentDescription = AppStrings.ui_sort)
                            }
                            IconButton(onClick = {
                                searchDraft = searchText
                                showFilters = true
                            }) {
                                Icon(Icons.Default.Search, contentDescription = AppStrings.ui_filter_bookmarks)
                            }
                            IconButton(
                                onClick = { isSelectionMode = true },
                                enabled = filteredItems.isNotEmpty(),
                            ) {
                                Icon(Icons.Default.Checklist, contentDescription = AppStrings.ui_batch_operation)
                            }
                        }
                    }
                )
            },
            floatingActionButton = {
                when {
                    isSortMode -> {
                        ExtendedFloatingActionButton(
                            onClick = {
                                scope.launch(Dispatchers.Default) {
                                    bookmarkState.updateSort(bookmarkItems.toList())
                                        .onSuccess {
                                            bookmarkState.load()
                                        }
                                        .onFailure {
                                            withContext(Dispatchers.Main) {
                                                snackbarHostState.showLatestSnackbar(
                                                    message = AppStrings.ui_failed_save_sort,
                                                )
                                            }
                                        }
                                }
                                isSortMode = false
                            },
                            icon = {
                                Icon(
                                    Icons.Default.Check,
                                    contentDescription = AppStrings.ui_complete_sorting,
                                )
                            },
                            text = { Text(AppStrings.ui_complete_sorting) },
                        )
                    }

                    isSelectionMode && selectedIds.isNotEmpty() -> {
                        ExtendedFloatingActionButton(
                            onClick = {
                                val idsToDelete = selectedIds.toList()
                                scope.launch(Dispatchers.Default) {
                                    snackbarHostState.confirmSnackbarAction(
                                        message = AppStrings.ui_you_sure_you_want_delete_selected_arg0_bookmarks.format(
                                            arg0 = idsToDelete.size.toString(),
                                        ),
                                        actionLabel = AppStrings.ui_delete,
                                    ) {
                                        var failure: Throwable? = null
                                        idsToDelete.forEach { id ->
                                            val deleteResult = bookmarkState.delete(id)
                                            if (deleteResult.isFailure && failure == null) {
                                                failure = deleteResult.exceptionOrNull()
                                            }
                                        }
                                        bookmarkState.load()
                                        withContext(Dispatchers.Main) {
                                            if (failure == null) {
                                                selectedIds.clear()
                                                isSelectionMode = false
                                                snackbarHostState.showLatestSnackbar(
                                                    AppStrings.ui_arg0_bookmarks_deleted.format(
                                                        arg0 = idsToDelete.size.toString(),
                                                    ),
                                                )
                                            } else {
                                                snackbarHostState.showLatestSnackbar(
                                                    failure.message ?: AppStrings.ui_delete_failed,
                                                )
                                            }
                                        }
                                    }
                                }
                            },
                            containerColor = MaterialTheme.colorScheme.error,
                            contentColor = MaterialTheme.colorScheme.onError,
                            icon = {
                                Icon(
                                    Icons.Default.Delete,
                                    contentDescription = AppStrings.ui_delete_bookmarks_batches,
                                )
                            },
                            text = { Text(AppStrings.ui_delete) },
                        )
                    }

                    !isSelectionMode && !isSortMode -> {
                        ExtendedFloatingActionButton(
                            onClick = { showAddDialog = true },
                            icon = { Icon(Icons.Default.Add, contentDescription = AppStrings.ui_add_bookmark) },
                            text = { Text(AppStrings.ui_create_new_action) },
                        )
                    }
                }
            },
            snackbarHost = { SnackbarHost(snackbarHostState) }
        ) { innerPadding ->
            GridList(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding),
                state = lazyGridState,
                isEmpty = filteredItems.isEmpty(),
                floatingActionButtonPadding = if (!isSelectionMode || selectedIds.isNotEmpty()) {
                    GridListFabPadding
                } else {
                    0.dp
                },
            ) {
                items(
                    items = if (isSortMode) bookmarkItems else filteredItems,
                    key = { item -> item.id },
                ) { bookmark ->
                    ReorderableItem(reorderableLazyListState, key = bookmark.id) {
                        BookmarkListItem(
                            bookmark = bookmark,
                            isSelected = bookmark.id in selectedIds,
                            isSelectionMode = isSelectionMode,
                            onToggleSelect = { toggleSelection(bookmark.id) },
                            onClick = {
                                if (isSortMode) {
                                    return@BookmarkListItem
                                }
                                if (isSelectionMode) {
                                    toggleSelection(bookmark.id)
                                } else {
                                    scope.launch(Dispatchers.Default) {
                                        fileState.updatePath(bookmark.path)
                                    }
                                    navigator.pop()
                                }
                            },
                            trailingContent = {
                                if (isSortMode) {
                                    IconButton(
                                        {},
                                        modifier = Modifier.draggableHandle(),
                                    ) {
                                        Icon(
                                            Icons.Rounded.DragHandle,
                                            contentDescription = AppStrings.ui_reorder,
                                        )
                                    }
                                    return@BookmarkListItem
                                }
                                if (isSelectionMode) {
                                    return@BookmarkListItem
                                }
                                Row(
                                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    IconButton(onClick = {
                                        editing = bookmark
                                    }) {
                                        Icon(Icons.Default.Edit, contentDescription = AppStrings.ui_edit_bookmark)
                                    }
                                    IconButton(onClick = {
                                        scope.launch(Dispatchers.Default) {
                                            val result = snackbarHostState.showLatestSnackbar(
                                                message = AppStrings.dialog_delete_bookmark.format(
                                                    bookmarkName = bookmark.name,
                                                ),
                                                actionLabel = AppStrings.ui_delete,
                                                withDismissAction = true,
                                                duration = SnackbarDuration.Short
                                            )
                                            if (result == SnackbarResult.ActionPerformed) {
                                                bookmarkState.delete(bookmark.id)
                                                    .onFailure { error ->
                                                        scope.launch(Dispatchers.Default) {
                                                            snackbarHostState.showLatestSnackbar(
                                                                message = error.message ?: AppStrings.ui_delete_failed
                                                            )
                                                        }
                                                    }
                                                    .onSuccess {
                                                        scope.launch(Dispatchers.Default) {
                                                            bookmarkState.load()
                                                        }
                                                    }
                                            }
                                        }
                                    }) {
                                        Icon(Icons.Default.Delete, contentDescription = AppStrings.ui_delete_bookmark)
                                    }
                                }
                            }
                        )
                    }
                }
            }
        }

        if (showFilters) {
            ModalBottomSheet(
                onDismissRequest = { showFilters = false },
                sheetState = sheetState
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 24.dp, vertical = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    TextField(
                        value = searchDraft,
                        onValueChange = { item -> searchDraft = item },
                        label = { Text(AppStrings.ui_search_bookmarks) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )

                    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                        typeOptions.forEachIndexed { index, type ->
                            val label = type?.displayName() ?: AppStrings.ui_all
                            SegmentedButton(
                                selected = selectedType == type,
                                onClick = { selectedType = type },
                                shape = SegmentedButtonDefaults.itemShape(index, typeOptions.size),
                                label = { Text(label) }
                            )
                        }
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.End
                    ) {
                        TextButton(
                            onClick = {
                                searchText = ""
                                searchDraft = ""
                                selectedType = null
                                selectedIds.clear()
                            },
                            modifier = Modifier.padding(end = 8.dp)
                        ) {
                            Text(AppStrings.ui_reset)
                        }
                        TextButton(onClick = {
                            searchText = searchDraft.trim()
                            showFilters = false
                        }) {
                            Text(AppStrings.ui_search)
                        }
                    }
                }
            }
        }

        if (showAddDialog) {
            BookmarkEditorDialog(
                deskType = deskType,
                title = AppStrings.ui_add_bookmark,
                confirmText = AppStrings.ui_save,
                initialPath = currentPath,
                onDismiss = { showAddDialog = false },
                onConfirm = { name, path, type ->
                    scope.launch(Dispatchers.Default) {
                        bookmarkState.add(name, type, path)
                            .onFailure { error ->
                                scope.launch {
                                    snackbarHostState.showLatestSnackbar(
                                        message = error.message ?: AppStrings.ui_failed_add,
                                    )
                                }
                            }
                            .onSuccess {
                                scope.launch(Dispatchers.Default) {
                                    bookmarkState.load()
                                }
                            }
                    }
                    showAddDialog = false
                }
            )
        }

        editing?.let { bookmark ->
            BookmarkEditorDialog(
                deskType = deskType,
                title = AppStrings.ui_edit_bookmark,
                confirmText = AppStrings.ui_update,
                initialName = bookmark.name,
                initialPath = bookmark.path,
                initialType = bookmark.type,
                onDismiss = { editing = null },
                onConfirm = { name, path, type ->
                    scope.launch(Dispatchers.Default) {
                        bookmarkState.update(bookmark.id, name, type, path, bookmark.icon)
                            .onFailure { error ->
                                scope.launch {
                                    snackbarHostState.showLatestSnackbar(
                                        message = error.message ?: AppStrings.ui_update_failed,
                                    )
                                }
                            }
                            .onSuccess {
                                scope.launch(Dispatchers.Default) {
                                    bookmarkState.load()
                                }
                            }
                    }
                    editing = null
                }
            )
        }

    }
}
