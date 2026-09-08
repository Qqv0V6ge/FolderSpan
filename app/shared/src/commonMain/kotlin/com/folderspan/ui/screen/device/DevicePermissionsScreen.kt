package com.folderspan.ui.screen.device

import strings.AppStrings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.material3.MaterialTheme.colorScheme
import androidx.compose.material3.MaterialTheme.typography
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.folderspan.db.DevicePermission
import com.folderspan.ui.components.dialog.DevicePermissionEditDialog
import com.folderspan.ui.components.grid.GridList
import com.folderspan.ui.components.grid.GridListFabPadding
import com.folderspan.ui.components.dialog.SearchDialog
import com.folderspan.ui.components.scaffold.AppScaffold
import com.folderspan.ui.state.device.DevicePermissionState
import com.folderspan.ui.navigation.AppScreenRoute
import com.folderspan.ui.navigation.LocalAppNavigator
import com.folderspan.ui.navigation.currentOrThrow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.koin.compose.koinInject

class DevicePermissionScreen : AppScreenRoute {
    @OptIn(ExperimentalMaterial3Api::class)
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
                                permission.comment?.contains(searchText, ignoreCase = true) ?: false
                    }
                }
            }
        }

        AppScaffold(
            topBar = {
                TopAppBar(
                    title = { Text(AppStrings.ui_permissions) },
                    navigationIcon = {
                        IconButton(
                            onClick = { navigator.pop() }
                        ) {
                            Icon(Icons.AutoMirrored.Default.ArrowBack, contentDescription = null)
                        }
                    },
                    actions = {
                        IconButton(onClick = {
                            searchDraft = searchText
                            showSearchDialog = true
                        }) {
                            Icon(Icons.Filled.Search, contentDescription = null)
                        }
                    }
                )
            },
            floatingActionButton = {
                ExtendedFloatingActionButton(
                    onClick = { openDialog = true },
                    icon = { Icon(Icons.Filled.Add, contentDescription = null) },
                    text = { Text(AppStrings.ui_create_new_action) }
                )
            },
            snackbarHost = { SnackbarHost(snackbarHostState) },
        ) { paddingValues ->
            GridList(
                modifier = Modifier.padding(paddingValues),
                isEmpty = filteredPermissions.isEmpty(),
                floatingActionButtonPadding = GridListFabPadding
            ) {
                itemsIndexed(filteredPermissions, key = { _, permission -> permission.id }) { index, permission ->
                    PermissionItem(
                        permission = permission,
                        includeTopPadding = index != 0,
                        onToggleStatus = { updatedPermission ->
                            scope.launch {
                                state.update(updatedPermission, index)
                            }
                        },
                        onEdit = {
                            devicePermission = permission
                            openDialog = true
                        },
                        onDelete = {
                            scope.launch(Dispatchers.Default) {
                                when (snackbarHostState.showSnackbar(
                                    message = permission.path,
                                    actionLabel = AppStrings.ui_delete,
                                    withDismissAction = true,
                                    duration = SnackbarDuration.Short
                                )) {
                                    SnackbarResult.Dismissed -> {}
                                    SnackbarResult.ActionPerformed -> {
                                        state.delete(permission, index)
                                    }
                                }
                            }
                        }
                    )
                }
            }

            if (openDialog) {
                DevicePermissionEditDialog(
                    initialPermission = devicePermission,
                    onDismiss = {
                        devicePermission = null
                        openDialog = false
                    },
                    onSave = { path, comment ->
                        if (devicePermission != null) {
                            scope.launch {
                                state.update(
                                    devicePermission!!.copy(
                                        path = path,
                                        comment = comment
                                    ),
                                    state.permissions.indexOf(devicePermission!!)
                                )
                            }
                        } else {
                            scope.launch {
                                state.create(path, comment)
                            }
                        }
                    }
                )
            }

            if (showSearchDialog) {
                SearchDialog(
                    title = AppStrings.ui_search_permissions,
                    query = searchDraft,
                    onQueryChange = { item -> searchDraft = item },
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
                    clearIcon = Icons.Default.Close
                )
            }
        }
    }

    @Composable
    fun PermissionItem(
        permission: DevicePermission,
        includeTopPadding: Boolean = true,
        onToggleStatus: (DevicePermission) -> Unit,
        onEdit: () -> Unit,
        onDelete: () -> Unit
    ) {
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(
                    start = 16.dp,
                    top = if (includeTopPadding) 8.dp else 0.dp,
                    end = 16.dp,
                    bottom = 8.dp,
                ),
            shape = RoundedCornerShape(8.dp),
        ) {
            Column(modifier = Modifier) {
                Box(Modifier.clickable(onClick = onEdit)) {
                    Row(
                        modifier = Modifier.padding(16.dp).fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(
                                text = permission.path,
                                style = typography.titleMedium
                            )
                            val comment = permission.comment
                            if (comment != null) {
                                Spacer(modifier = Modifier.height(4.dp))
                                Text(
                                    text = comment,
                                    style = typography.bodyMedium,
                                    color = colorScheme.onSurface.copy(alpha = 0.6f)
                                )
                            }
                        }
                        IconButton(onClick = onDelete) {
                            Icon(Icons.Default.Delete, null)
                        }
                    }
                }
                Column(Modifier.padding(start = 16.dp, end = 16.dp, bottom = 16.dp)) {
                    HorizontalDivider()
                    Spacer(modifier = Modifier.height(16.dp))
                    Text(AppStrings.ui_permission_list)
                    FlowRow {
                        FilterChip(
                            selected = permission.useAll,
                            onClick = { onToggleStatus(permission.copy(useAll = !permission.useAll)) },
                            shape = RoundedCornerShape(25.dp),
                            modifier = Modifier.padding(horizontal = 4.dp)
                                .align(Alignment.CenterVertically),
                            label = { Text(AppStrings.ui_apply_children) }
                        )
                        FilterChip(
                            selected = permission.read,
                            onClick = { onToggleStatus(permission.copy(read = !permission.read)) },
                            shape = RoundedCornerShape(25.dp),
                            modifier = Modifier.padding(horizontal = 4.dp)
                                .align(Alignment.CenterVertically),
                            label = { Text(AppStrings.ui_read) }
                        )
                        FilterChip(
                            selected = permission.write,
                            onClick = { onToggleStatus(permission.copy(write = !permission.write)) },
                            shape = RoundedCornerShape(25.dp),
                            modifier = Modifier.padding(horizontal = 4.dp)
                                .align(Alignment.CenterVertically),
                            label = { Text(AppStrings.ui_write) }
                        )
                        FilterChip(
                            selected = permission.remove,
                            onClick = { onToggleStatus(permission.copy(remove = !permission.remove)) },
                            shape = RoundedCornerShape(25.dp),
                            modifier = Modifier.padding(horizontal = 4.dp)
                                .align(Alignment.CenterVertically),
                            label = { Text(AppStrings.ui_delete) }
                        )
                        FilterChip(
                            selected = permission.rename,
                            onClick = { onToggleStatus(permission.copy(rename = !permission.rename)) },
                            shape = RoundedCornerShape(25.dp),
                            modifier = Modifier.padding(horizontal = 4.dp)
                                .align(Alignment.CenterVertically),
                            label = { Text(AppStrings.ui_rename) }
                        )
                    }
                }
            }
        }
    }

}
