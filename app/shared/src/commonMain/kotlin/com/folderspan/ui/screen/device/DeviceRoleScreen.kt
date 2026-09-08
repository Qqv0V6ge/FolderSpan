package com.folderspan.ui.screen.device

import strings.AppStrings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.material3.MaterialTheme.typography
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.folderspan.data.device.DeviceRole
import com.folderspan.db.DevicePermission
import com.folderspan.db.FolderSpanDatabase
import com.folderspan.ui.components.grid.GridList
import com.folderspan.ui.components.grid.GridListFabPadding
import com.folderspan.ui.components.dialog.SearchDialog
import com.folderspan.ui.components.scaffold.AppScaffold
import com.folderspan.ui.state.device.DevicePermissionState
import com.folderspan.ui.state.device.DeviceRoleState
import com.folderspan.utils.awaitDatabaseReady
import com.folderspan.utils.calculateGridColumnCount
import com.folderspan.utils.executeAsListAwait
import com.folderspan.utils.executeAsOneAwait
import com.folderspan.utils.executeAsOneOrNullAwait
import com.folderspan.utils.SyncSnapshotChangeNotifier
import com.folderspan.ui.navigation.AppScreenRoute
import com.folderspan.ui.navigation.LocalAppNavigator
import com.folderspan.ui.navigation.currentOrThrow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.koin.compose.koinInject

class DeviceRoleScreen : AppScreenRoute {
    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    override fun Content() {
        val navigator = LocalAppNavigator.currentOrThrow
        val state = koinInject<DeviceRoleState>()

        val scope = rememberCoroutineScope()
        val snackbarHostState = remember { SnackbarHostState() }
        var showSearchDialog by rememberSaveable { mutableStateOf(false) }
        var searchText by rememberSaveable { mutableStateOf("") }
        var searchDraft by rememberSaveable { mutableStateOf("") }

        val filteredRoles by remember {
            derivedStateOf {
                if (searchText.isBlank()) {
                    state.roles
                } else {
                    state.roles.filter { role ->
                        role.name.contains(searchText, ignoreCase = true) ||
                                (role.comment?.contains(searchText, ignoreCase = true) ?: false)
                    }
                }
            }
        }

        AppScaffold(
            topBar = {
                TopAppBar(
                    title = { Text(AppStrings.ui_device_role) },
                    navigationIcon = {
                        IconButton(
                            onClick = navigator::pop
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
            snackbarHost = { SnackbarHost(snackbarHostState) },
            floatingActionButton = {
                ExtendedFloatingActionButton(
                    onClick = {
                        navigator.push(EditRoleScreen(onSave = { newRole ->
                            state.roles.add(newRole)
                        }))
                    },
                    icon = { Icon(Icons.Filled.Add, contentDescription = null) },
                    text = { Text(AppStrings.ui_create_new_action) }
                )
            }
        ) { paddingValues ->
            GridList(
                modifier = Modifier.padding(paddingValues),
                isEmpty = filteredRoles.isEmpty(),
                floatingActionButtonPadding = GridListFabPadding
            ) {
                itemsIndexed(
                    items = filteredRoles,
                    key = { _, role -> role.id }
                ) { index, role ->
                    RoleItem(
                        role = role,
                        onEdit = {
                            navigator.push(
                                EditRoleScreen(
                                    role = role,
                                    onSave = { updatedRole ->
                                        state.roles[index] = updatedRole
                                        println(state.roles[index])
                                        println(updatedRole)
                                    }
                                ))
                        },
                        onDelete = {
                            scope.launch(Dispatchers.Default) {
                                when (snackbarHostState.showSnackbar(
                                    message = role.name,
                                    actionLabel = AppStrings.ui_delete,
                                    withDismissAction = true,
                                    duration = SnackbarDuration.Short
                                )) {
                                    SnackbarResult.Dismissed -> {}
                                    SnackbarResult.ActionPerformed -> {
                                        state.delete(role, index)
                                    }
                                }
                            }
                        })
                }
            }

            if (showSearchDialog) {
                SearchDialog(
                    title = AppStrings.ui_search_roles,
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
                    label = AppStrings.ui_character_name_remarks,
                    onClear = { searchDraft = "" }
                )
            }
        }
    }

    @Composable
    private fun RoleItem(role: DeviceRole, onEdit: () -> Unit, onDelete: () -> Unit) {
        val comment = role.comment
        ListItem(
            modifier = Modifier.clickable(onClick = onEdit),
            overlineContent = {
                Text(AppStrings.ui_arg0_permissions_configured.format(arg0 = (role.permissionCount).toString()))
            },
            headlineContent = { Text(role.name) },
            supportingContent =
                if (comment.isNullOrEmpty())
                    null
                else {
                    {
                        Text(comment)
                    }
                },
            trailingContent = {
                IconButton(onDelete) { Icon(Icons.Default.Delete, null) }
            }
        )
    }
}

class EditRoleScreen(
    private val role: DeviceRole? = null,
    private val onSave: (DeviceRole) -> Unit
) : AppScreenRoute {
    private val isEdit = role != null
    private val permissionIds = mutableStateListOf<Long>()
    private val oldPermissionIds = mutableStateListOf<Long>()

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    override fun Content() {
        val navigator = LocalAppNavigator.currentOrThrow
        val database = koinInject<FolderSpanDatabase>()
        val permissionState = koinInject<DevicePermissionState>()
        val scope = rememberCoroutineScope()
        val snackbarHostState = remember { SnackbarHostState() }
        val roleKey = role?.id ?: -1L

        val (name, setName) = rememberSaveable(roleKey) { mutableStateOf(role?.name ?: "") }
        val (comment, setComment) = rememberSaveable(roleKey) { mutableStateOf(role?.comment ?: "") }

        LaunchedEffect(roleKey) {
            if (!isEdit) return@LaunchedEffect
            permissionIds.clear()
            oldPermissionIds.clear()
            database
                .deviceRoleDevicePermissionQueries
                .queryDevicePermissionIdByRoleId(roleKey)
                .executeAsListAwait()
                .apply {
                    permissionIds.addAll(this)
                    oldPermissionIds.addAll(this)
                }
        }

        AppScaffold(
            topBar = {
                TopAppBar(
                    title = {
                        Text(if (role == null) AppStrings.ui_add_role else AppStrings.ui_edit_role)
                    },
                    navigationIcon = {
                        IconButton(onClick = {
                            if (isNotSave(name, comment)) {
                                scope.launch(Dispatchers.Default) {
                                    when (snackbarHostState.showSnackbar(
                                        message = AppStrings.ui_data_not_saved,
                                        actionLabel = AppStrings.ui_abandon,
                                        withDismissAction = true,
                                        duration = SnackbarDuration.Short
                                    )) {
                                        SnackbarResult.Dismissed -> {}
                                        SnackbarResult.ActionPerformed -> {
                                            navigator.pop()
                                        }
                                    }
                                }
                                return@IconButton
                            }
                            navigator.pop()
                        }) {
                            Icon(Icons.AutoMirrored.Default.ArrowBack, contentDescription = null)
                        }
                    },
                )
            },
            snackbarHost = { SnackbarHost(snackbarHostState) },
            floatingActionButton = {
                ExtendedFloatingActionButton(
                    onClick = {
                        scope.launch {
                            var id = role?.id ?: 0L
                            if (isEdit) {
                                database.deviceRoleQueries.updateRoleById(
                                    name = name,
                                    comment = comment,
                                    id = role?.id ?: 0L
                                ).awaitDatabaseReady()
                            } else {
                                database.deviceRoleQueries.insert(
                                    name,
                                    comment,
                                    role?.sortOrder ?: 0L
                                ).awaitDatabaseReady()
                                id = database.deviceRoleQueries.lastInsertRowId().executeAsOneAwait()
                            }

                            if (permissionIds != oldPermissionIds) {
                                val addedPermissions = permissionIds.filterNot { item -> oldPermissionIds.contains(item) }
                                val removedPermissions = oldPermissionIds.filterNot { item -> permissionIds.contains(item) }
                                for (permissionId in addedPermissions) {
                                    database.deviceRoleDevicePermissionQueries.insert(id, permissionId)
                                        .awaitDatabaseReady()
                                }
                                if (removedPermissions.isNotEmpty()) {
                                    database.deviceRoleDevicePermissionQueries
                                        .deleteByRoleIdAndDevicePermissionIds(id, removedPermissions)
                                        .awaitDatabaseReady()
                                }
                            }

                            SyncSnapshotChangeNotifier.onRoleConfigurationChanged()

                            database.deviceRoleQueries.selectById(id).executeAsOneOrNullAwait()?.let { item ->
                                onSave(
                                    DeviceRole(
                                        id = item.id,
                                        name = item.name,
                                        comment = item.comment,
                                        sortOrder = item.sortOrder,
                                        permissionCount = database.deviceRoleDevicePermissionQueries
                                            .queryCountByDeviceRoleId(id)
                                            .executeAsOneAwait()
                                    )
                                )
                            }

                            navigator.pop()
                        }
                    },
                    icon = { Icon(Icons.Default.Done, contentDescription = null) },
                    text = { Text(AppStrings.ui_save) }
                )
            }
        ) { item ->
            BoxWithConstraints(Modifier.padding(item)) {
                val columnCount = calculateGridColumnCount(maxWidth, maxHeight)
                LazyVerticalGrid(columns = GridCells.Fixed(columnCount)) {
                    item(span = { GridItemSpan(columnCount) }) {
                        Column(Modifier.padding(horizontal = 16.dp)) {
                            TextField(
                                value = name,
                                onValueChange = setName,
                                label = { Text(AppStrings.ui_character_name) },
                                modifier = Modifier.fillMaxWidth(),
                                isError = name.isEmpty(),
                                trailingIcon = {
                                    if (name.isNotEmpty()) {
                                        IconButton({ setName("") }) {
                                            Icon(Icons.Default.Close, null)
                                        }
                                    }
                                },
                                supportingText = {
                                    if (name.isEmpty()) {
                                        Text(AppStrings.ui_role_name_cannot_empty)
                                    }
                                }
                            )
                            Spacer(modifier = Modifier.height(16.dp))
                            TextField(
                                value = comment,
                                onValueChange = setComment,
                                label = { Text(AppStrings.ui_remarks) },
                                modifier = Modifier.fillMaxWidth(),
                                trailingIcon = {
                                    if (comment.isNotEmpty()) {
                                        IconButton({ setComment("") }) {
                                            Icon(Icons.Default.Close, null)
                                        }
                                    }
                                },
                            )
                            Spacer(modifier = Modifier.height(16.dp))
                        }
                    }

                    item(span = { GridItemSpan(columnCount) }) {
                        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
                            Row(
                                Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(AppStrings.ui_permissions)
                                TextButton(
                                    onClick = { navigator.push(DevicePermissionScreen()) }
                                ) {
                                    Icon(Icons.Default.Add, null)
                                    Spacer(Modifier.width(4.dp))
                                    Text(AppStrings.ui_add_new_permissions)
                                }
                            }
                            Text(
                                AppStrings.ui_click_permissions_below_select,
                                style = typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(bottom = 8.dp)
                            )
                        }
                    }

                    itemsIndexed(
                        permissionState.permissions,
                        key = { _, permission -> permission.id }) { index, permission ->
                        PermissionItem(
                            isChecked = permissionIds.contains(permission.id),
                            permission = permission,
                            onToggleStatus = { new ->
                                scope.launch {
                                    permissionState.update(new, index)
                                }
                            },
                            onClick = {
                                if (permissionIds.contains(permission.id)) {
                                    permissionIds.remove(permission.id)
                                } else {
                                    permissionIds.add(permission.id)
                                }
                            }
                        )
                    }
                }
            }
        }
    }

    @Composable
    fun PermissionItem(
        isChecked: Boolean,
        permission: DevicePermission,
        onToggleStatus: (DevicePermission) -> Unit,
        onClick: () -> Unit,
    ) {
        ListItem(
            leadingContent = if (isChecked) {
                { Icon(Icons.Default.Done, null) }
            } else null,
            headlineContent = {
                Text(
                    text = permission.path,
                    style = typography.titleMedium
                )
            },
            supportingContent = {
                FlowRow {
                    FilterChip(
                        selected = permission.useAll,
                        enabled = false,
                        onClick = { onToggleStatus(permission.copy(useAll = !permission.useAll)) },
                        shape = RoundedCornerShape(25.dp),
                        modifier = Modifier.padding(horizontal = 4.dp)
                            .align(Alignment.CenterVertically),
                        label = { Text(AppStrings.ui_apply_children) }
                    )
                    FilterChip(
                        selected = permission.read,
                        enabled = false,
                        onClick = { onToggleStatus(permission.copy(read = !permission.read)) },
                        shape = RoundedCornerShape(25.dp),
                        modifier = Modifier.padding(horizontal = 4.dp)
                            .align(Alignment.CenterVertically),
                        label = { Text(AppStrings.ui_read) }
                    )
                    FilterChip(
                        selected = permission.write,
                        enabled = false,
                        onClick = { onToggleStatus(permission.copy(write = !permission.write)) },
                        shape = RoundedCornerShape(25.dp),
                        modifier = Modifier.padding(horizontal = 4.dp)
                            .align(Alignment.CenterVertically),
                        label = { Text(AppStrings.ui_write) }
                    )
                    FilterChip(
                        selected = permission.remove,
                        enabled = false,
                        onClick = { onToggleStatus(permission.copy(remove = !permission.remove)) },
                        shape = RoundedCornerShape(25.dp),
                        modifier = Modifier.padding(horizontal = 4.dp)
                            .align(Alignment.CenterVertically),
                        label = { Text(AppStrings.ui_delete) }
                    )
                    FilterChip(
                        selected = permission.rename,
                        enabled = false,
                        onClick = { onToggleStatus(permission.copy(rename = !permission.rename)) },
                        shape = RoundedCornerShape(25.dp),
                        modifier = Modifier.padding(horizontal = 4.dp)
                            .align(Alignment.CenterVertically),
                        label = { Text(AppStrings.ui_rename) }
                    )
                }
            },
            modifier = Modifier.clickable(onClick = onClick)
        )
    }

    private fun isNotSave(name: String, comment: String): Boolean {
        return if (isEdit) {
            name != role!!.name || comment != role.comment || permissionIds.toList() != oldPermissionIds.toList()
        } else {
            name.isNotEmpty() || comment.isNotEmpty() || permissionIds.isNotEmpty()
        }
    }
}
