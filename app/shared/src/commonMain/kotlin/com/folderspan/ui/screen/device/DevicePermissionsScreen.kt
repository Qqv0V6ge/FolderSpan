package com.folderspan.ui.screen.device

import strings.AppStrings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.folderspan.db.DevicePermission
import com.folderspan.localization.localizedComment
import com.folderspan.ui.components.showLatestSnackbar
import com.folderspan.ui.components.dialog.DevicePermissionEditDialog
import com.folderspan.ui.components.dialog.SearchDialog
import com.folderspan.ui.components.grid.GridList
import com.folderspan.ui.components.grid.GridListFabPadding
import com.folderspan.ui.components.scaffold.AppScaffold
import com.folderspan.ui.navigation.AppScreenRoute
import com.folderspan.ui.navigation.LocalAppNavigator
import com.folderspan.ui.navigation.currentOrThrow
import com.folderspan.ui.state.device.DevicePermissionState
import com.folderspan.utils.WindowSizeClass
import com.folderspan.utils.calculateWindowSizeClass
import kotlinx.coroutines.launch
import org.koin.compose.koinInject

class DevicePermissionScreen : AppScreenRoute {
    @Composable
    override fun Content() {
        val navigator = LocalAppNavigator.currentOrThrow
        val state = koinInject<DevicePermissionState>()

        val scope = rememberCoroutineScope()
        var devicePermission by remember { mutableStateOf<DevicePermission?>(null) }
        var openDialog by rememberSaveable { mutableStateOf(false) }
        var showSearchDialog by rememberSaveable { mutableStateOf(false) }
        var searchText by rememberSaveable { mutableStateOf("") }
        var searchDraft by rememberSaveable { mutableStateOf("") }
        val snackbarHostState = remember { SnackbarHostState() }

        val filteredPermissions by remember {
            derivedStateOf {
                if (searchText.isBlank()) {
                    state.permissions
                } else {
                    state.permissions.filter { permission ->
                        permission.path.contains(searchText, ignoreCase = true) ||
                                permission.localizedComment?.contains(searchText, ignoreCase = true) == true
                    }
                }
            }
        }

        DevicePermissionsPage(
            permissions = filteredPermissions,
            emptyMessage = if (searchText.isBlank()) {
                AppStrings.ui_no_available_permissions_configured
            } else {
                AppStrings.ui_no_search_results
            },
            snackbarHostState = snackbarHostState,
            onBack = navigator::pop,
            onSearch = {
                searchDraft = searchText
                showSearchDialog = true
            },
            onCreate = { openDialog = true },
            onToggleStatus = { updatedPermission ->
                scope.launch {
                    state.update(
                        updatedPermission,
                        state.permissions.indexOfFirst { it.id == updatedPermission.id },
                    )
                }
            },
            onEdit = { permission ->
                devicePermission = permission
                openDialog = true
            },
            onDelete = { permission ->
                scope.launch {
                    when (
                        snackbarHostState.showLatestSnackbar(
                            message = permission.path,
                            actionLabel = AppStrings.ui_delete,
                            withDismissAction = true,
                            duration = SnackbarDuration.Short,
                        )
                    ) {
                        SnackbarResult.Dismissed -> Unit
                        SnackbarResult.ActionPerformed -> state.delete(
                            permission,
                            state.permissions.indexOfFirst { it.id == permission.id },
                        )
                    }
                }
            },
            modifier = Modifier.fillMaxSize(),
        )

        if (openDialog) {
            DevicePermissionEditDialog(
                initialPermission = devicePermission,
                onDismiss = {
                    devicePermission = null
                    openDialog = false
                },
                onSave = { path, comment ->
                    val permission = devicePermission
                    scope.launch {
                        if (permission == null) {
                            state.create(path, comment)
                        } else {
                            state.update(
                                permission.copy(path = path, comment = comment),
                                state.permissions.indexOfFirst { it.id == permission.id },
                            )
                        }
                    }
                },
            )
        }

        if (showSearchDialog) {
            SearchDialog(
                title = AppStrings.ui_search_permissions,
                query = searchDraft,
                onQueryChange = { searchDraft = it },
                onConfirm = {
                    searchText = searchDraft.trim()
                    showSearchDialog = false
                },
                onDismissRequest = { showSearchDialog = false },
                confirmText = AppStrings.ui_search,
                dismissText = AppStrings.ui_reset,
                onDismissButtonClick = {
                    searchText = ""
                    searchDraft = ""
                    showSearchDialog = false
                },
                label = AppStrings.ui_path_notes,
                onClear = { searchDraft = "" },
                clearIcon = Icons.Default.Close,
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DevicePermissionsPage(
    permissions: List<DevicePermission>,
    emptyMessage: String,
    snackbarHostState: SnackbarHostState,
    onBack: () -> Unit,
    onSearch: () -> Unit,
    onCreate: () -> Unit,
    onToggleStatus: (DevicePermission) -> Unit,
    onEdit: (DevicePermission) -> Unit,
    onDelete: (DevicePermission) -> Unit,
    modifier: Modifier = Modifier,
) {
    AppScaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = AppStrings.ui_permissions,
                        modifier = Modifier.semantics { heading() },
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = AppStrings.ui_return,
                        )
                    }
                },
                actions = {
                    IconButton(onClick = onSearch) {
                        Icon(
                            imageVector = Icons.Default.Search,
                            contentDescription = AppStrings.ui_search,
                        )
                    }
                },
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = onCreate,
                icon = {
                    Icon(
                        imageVector = Icons.Default.Add,
                        contentDescription = null,
                    )
                },
                text = { Text(AppStrings.ui_create_new_action) },
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { paddingValues ->
        BoxWithConstraints(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues),
        ) {
            GridList(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 8.dp),
                isEmpty = permissions.isEmpty(),
                emptyMessage = emptyMessage,
                floatingActionButtonPadding = GridListFabPadding,
                fixedColumnCount = permissionColumnCount(
                    calculateWindowSizeClass(maxWidth, maxHeight),
                ),
            ) {
                items(
                    items = permissions,
                    key = { it.id },
                ) { permission ->
                    PermissionItem(
                        permission = permission,
                        onToggleStatus = onToggleStatus,
                        onEdit = { onEdit(permission) },
                        onDelete = { onDelete(permission) },
                        modifier = Modifier.padding(horizontal = 8.dp),
                    )
                }
            }
        }
    }
}

internal fun permissionColumnCount(windowSizeClass: WindowSizeClass): Int = when (windowSizeClass) {
    WindowSizeClass.Compact,
    WindowSizeClass.Medium,
    -> 1

    WindowSizeClass.Expanded -> 2
    WindowSizeClass.Large -> 3
    WindowSizeClass.ExtraLarge -> 4
}

@Composable
private fun PermissionItem(
    permission: DevicePermission,
    onToggleStatus: (DevicePermission) -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxWidth()) {
        ListItem(
            headlineContent = {
                Text(
                    text = permission.path,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            },
            supportingContent = {
                Text(
                    text = permission.localizedComment.orEmpty(),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    minLines = 2,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            },
            leadingContent = {
                Icon(
                    imageVector = Icons.Default.Folder,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            },
            trailingContent = {
                Row {
                    IconButton(onClick = onEdit) {
                        Icon(
                            imageVector = Icons.Default.Edit,
                            contentDescription = AppStrings.ui_edit,
                        )
                    }
                    IconButton(onClick = onDelete) {
                        Icon(
                            imageVector = Icons.Default.Delete,
                            contentDescription = AppStrings.ui_delete,
                            tint = MaterialTheme.colorScheme.error,
                        )
                    }
                }
            },
            modifier = Modifier.fillMaxWidth(),
            colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        )
        FlowRow(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 56.dp, end = 16.dp, bottom = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            PermissionToggleChip(
                label = AppStrings.ui_apply_children,
                selected = permission.useAll,
                onClick = { onToggleStatus(permission.copy(useAll = !permission.useAll)) },
            )
            PermissionToggleChip(
                label = AppStrings.ui_read,
                selected = permission.read,
                onClick = { onToggleStatus(permission.copy(read = !permission.read)) },
            )
            PermissionToggleChip(
                label = AppStrings.ui_write,
                selected = permission.write,
                onClick = { onToggleStatus(permission.copy(write = !permission.write)) },
            )
            PermissionToggleChip(
                label = AppStrings.ui_delete,
                selected = permission.remove,
                onClick = { onToggleStatus(permission.copy(remove = !permission.remove)) },
            )
            PermissionToggleChip(
                label = AppStrings.ui_rename,
                selected = permission.rename,
                onClick = { onToggleStatus(permission.copy(rename = !permission.rename)) },
            )
        }

        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
    }
}

@Composable
private fun PermissionToggleChip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    FilterChip(
        selected = selected,
        onClick = onClick,
        label = { Text(label) },
        modifier = modifier,
    )
}

private val previewPermissions = listOf(
    DevicePermission(
        id = 1,
        path = "/",
        useAll = true,
        read = true,
        write = true,
        remove = true,
        rename = true,
        sortOrder = 0,
        comment = "Full device access",
    ),
    DevicePermission(
        id = 2,
        path = "/Documents",
        useAll = true,
        read = true,
        write = true,
        remove = false,
        rename = true,
        sortOrder = 1,
        comment = "Documents and nested folders",
    ),
    DevicePermission(
        id = 3,
        path = "/Pictures",
        useAll = false,
        read = true,
        write = false,
        remove = false,
        rename = false,
        sortOrder = 2,
        comment = null,
    ),
)

@Composable
private fun DevicePermissionsPreview() {
    MaterialTheme {
        DevicePermissionsPage(
            permissions = previewPermissions,
            emptyMessage = AppStrings.ui_no_available_permissions_configured,
            snackbarHostState = remember { SnackbarHostState() },
            onBack = {},
            onSearch = {},
            onCreate = {},
            onToggleStatus = {},
            onEdit = {},
            onDelete = {},
            modifier = Modifier.fillMaxSize(),
        )
    }
}

@Preview(name = "Window - Compact portrait", widthDp = 360, heightDp = 800)
@Composable
private fun DevicePermissionsCompactPreview() {
    DevicePermissionsPreview()
}

@Preview(name = "Window - Medium short landscape", widthDp = 640, heightDp = 360)
@Composable
private fun DevicePermissionsShortLandscapePreview() {
    DevicePermissionsPreview()
}

@Preview(name = "Window - Medium portrait", widthDp = 720, heightDp = 900)
@Composable
private fun DevicePermissionsMediumPreview() {
    DevicePermissionsPreview()
}

@Preview(name = "Window - Expanded landscape", widthDp = 1024, heightDp = 768)
@Composable
private fun DevicePermissionsExpandedPreview() {
    DevicePermissionsPreview()
}

@Preview(name = "Window - Large", widthDp = 1366, heightDp = 900)
@Composable
private fun DevicePermissionsLargePreview() {
    DevicePermissionsPreview()
}

@Preview(name = "Window - Extra-large", widthDp = 1600, heightDp = 900)
@Composable
private fun DevicePermissionsExtraLargePreview() {
    DevicePermissionsPreview()
}
