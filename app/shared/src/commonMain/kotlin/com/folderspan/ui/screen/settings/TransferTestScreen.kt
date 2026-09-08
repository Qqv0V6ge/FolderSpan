package com.folderspan.ui.screen.settings

import com.folderspan.utils.FileAccessPermission
import strings.AppStrings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.folderspan.data.StatusEnum
import com.folderspan.data.file.FileProtocol
import com.folderspan.data.file.FileSimpleInfo
import com.folderspan.data.main.device.Device
import com.folderspan.ui.components.grid.GridList
import com.folderspan.ui.components.scaffold.AppScaffold
import com.folderspan.ui.state.file.FileState
import com.folderspan.ui.state.main.DeviceState
import com.folderspan.ui.state.main.Task
import com.folderspan.ui.state.main.TaskType
import com.folderspan.utils.FileUtils
import com.folderspan.utils.PathUtils
import com.folderspan.ui.navigation.AppScreenRoute
import com.folderspan.ui.navigation.LocalAppNavigator
import com.folderspan.ui.navigation.currentOrThrow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.koin.compose.koinInject

/**
 * 设置 -> 开发者设置 -> 传输测试
 */
class TransferTestScreen : AppScreenRoute {
    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    override fun Content() {
        val navigator = LocalAppNavigator.currentOrThrow
        val deviceState = koinInject<DeviceState>()
        val fileState = koinInject<FileState>()
        val snackbarHostState = remember { SnackbarHostState() }
        val scope = rememberCoroutineScope()

        val localSeparator = remember { PathUtils.getPathSeparator() }
        val defaultLocalTargetDirectory = remember {
            joinTransferTestPath(PathUtils.getCachePath(), "transfer-test", localSeparator)
        }
        val devices = deviceState.devices.toList()
        val selectedDeviceIds = remember { mutableStateListOf<String>() }
        val selectedDeviceIdSnapshot = selectedDeviceIds.toSet()
        val selectedDevices = devices.filter { device -> device.id in selectedDeviceIdSnapshot }

        var localSourcePaths by rememberSaveable { mutableStateOf("") }
        var remoteSourcePaths by rememberSaveable { mutableStateOf("") }
        val remoteTargetDirectories = remember { mutableStateMapOf<String, String>() }
        var localTargetDirectory by rememberSaveable { mutableStateOf(defaultLocalTargetDirectory) }
        var pendingAction by remember { mutableStateOf<TransferTestAction?>(null) }
        var isSubmitting by remember { mutableStateOf(false) }

        LaunchedEffect(devices.map { device -> device.id }) {
            selectedDeviceIds.removeAll { id -> devices.none { device -> device.id == id } }
            remoteTargetDirectories.keys.removeAll { id -> devices.none { device -> device.id == id } }
        }

        AppScaffold(
            topBar = {
                TopAppBar(
                    title = { Text(AppStrings.ui_transmission_test) },
                    navigationIcon = {
                        IconButton(onClick = navigator::pop) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null)
                        }
                    }
                )
            },
            snackbarHost = { SnackbarHost(snackbarHostState) }
        ) { padding ->
            GridList(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
            ) {
                item(span = { GridItemSpan(maxLineSpan) }) {
                    SectionTitle(AppStrings.ui_equipment, includeTopPadding = false)
                }
                if (devices.isEmpty()) {
                    item(span = { GridItemSpan(maxLineSpan) }) {
                        Text(
                            text = AppStrings.ui_no_connected_devices_yet,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                        )
                    }
                } else {
                    devices.forEach { device ->
                        item(key = device.id, span = { GridItemSpan(maxLineSpan) }) {
                            val checked = device.id in selectedDeviceIdSnapshot
                            ListItem(
                                headlineContent = {
                                    Text(device.name.ifBlank { device.id }, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                },
                                supportingContent = {
                                    Text(
                                        text = "${device.type.name} · ${device.id}",
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                },
                                trailingContent = {
                                    Checkbox(
                                        checked = checked,
                                        onCheckedChange = { selected ->
                                            updateDeviceSelection(selectedDeviceIds, device.id, selected)
                                        }
                                    )
                                },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        updateDeviceSelection(selectedDeviceIds, device.id, !checked)
                                    }
                            )
                        }
                    }
                }

                item(span = { GridItemSpan(maxLineSpan) }) {
                    HorizontalDivider(Modifier.padding(vertical = 8.dp))
                }
                item(span = { GridItemSpan(maxLineSpan) }) {
                    SectionTitle(AppStrings.ui_path)
                }
                item(span = { GridItemSpan(maxLineSpan) }) {
                    TransferTextField(
                        value = localSourcePaths,
                        onValueChange = { localSourcePaths = it },
                        label = AppStrings.ui_local_source_file_path,
        supportingText = AppStrings.ui_one_path_per_line_separated_commas_directory_will_traversed
                    )
                }
                item(span = { GridItemSpan(maxLineSpan) }) {
                    TransferTextField(
                        value = remoteSourcePaths,
                        onValueChange = { remoteSourcePaths = it },
                        label = AppStrings.ui_device_source_delete_path,
        supportingText = AppStrings.ui_one_remote_path_per_line_separated_commas_directory_will
                    )
                }
                item(span = { GridItemSpan(maxLineSpan) }) {
                    SectionTitle(AppStrings.ui_device_target_directory)
                }
                if (selectedDevices.isEmpty()) {
                    item(span = { GridItemSpan(maxLineSpan) }) {
                        Text(
                            text = AppStrings.ui_after_selecting_device_you_can_set_separate_copy_target,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                        )
                    }
                } else {
                    selectedDevices.forEach { device ->
                        item(key = "target-${device.id}", span = { GridItemSpan(maxLineSpan) }) {
                            SingleLineTextField(
                                value = remoteTargetDirectories[device.id].orEmpty(),
                                onValueChange = { value -> remoteTargetDirectories[device.id] = value },
                                label = AppStrings.ui_arg0_target_directory.format(arg0 = device.name.ifBlank { device.id }),
                                supportingText = AppStrings.ui_used_when_copying_local_files_this_device
                            )
                        }
                    }
                }
                item(span = { GridItemSpan(maxLineSpan) }) {
                    SingleLineTextField(
                        value = localTargetDirectory,
                        onValueChange = { localTargetDirectory = it },
                        label = AppStrings.ui_local_target_directory,
                        supportingText = AppStrings.ui_used_when_downloading_device_files_local_local_deletion_will
                    )
                }

                item(span = { GridItemSpan(maxLineSpan) }) {
                    HorizontalDivider(Modifier.padding(vertical = 8.dp))
                }
                item(span = { GridItemSpan(maxLineSpan) }) {
                    SectionTitle(AppStrings.ui_operation)
                }
                item(span = { GridItemSpan(maxLineSpan) }) {
                    Column(
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 8.dp)
                    ) {
                        Button(
                            onClick = { pendingAction = TransferTestAction.CopyLocalToDevices },
                            enabled = !isSubmitting,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(AppStrings.ui_copy_local_files_device)
                        }
                        Button(
                            onClick = { pendingAction = TransferTestAction.CopyDevicesToLocal },
                            enabled = !isSubmitting,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(AppStrings.ui_copy_device_files_local)
                        }
                        OutlinedButton(
                            onClick = { pendingAction = TransferTestAction.DeleteDeviceFiles },
                            enabled = !isSubmitting,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(AppStrings.ui_delete_device_files)
                        }
                        OutlinedButton(
                            onClick = { pendingAction = TransferTestAction.DeleteLocalTargets },
                            enabled = !isSubmitting,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(AppStrings.ui_delete_local_test_target)
                        }
                    }
                }
            }
        }

        pendingAction?.let { action ->
            ConfirmTransferTestDialog(
                action = action,
                selectedDeviceCount = selectedDevices.size,
                onDismiss = { pendingAction = null },
                onConfirm = {
                    pendingAction = null
                    scope.launch {
                        isSubmitting = true
                        val message = runCatching {
                            executeTransferTestAction(
                                action = action,
                                selectedDevices = selectedDevices,
                                localSourcePaths = localSourcePaths,
                                remoteSourcePaths = remoteSourcePaths,
                                remoteTargetDirectories = remoteTargetDirectories.toMap(),
                                localTargetDirectory = localTargetDirectory,
                                fileState = fileState,
                            )
                        }.getOrElse { error ->
                            error.message?.ifBlank { null } ?: AppStrings.ui_test_task_submission_failed
                        }
                        isSubmitting = false
                        snackbarHostState.showSnackbar(message)
                    }
                }
            )
        }
    }
}

private enum class TransferTestAction(
    val title: String,
    val warning: String,
) {
    CopyLocalToDevices(
        title = AppStrings.ui_copy_local_files_device,
        warning = AppStrings.ui_local_directory_traversed_copy_task_created_each_selected_device
    ),
    CopyDevicesToLocal(
        title = AppStrings.ui_copy_device_files_local,
        warning = AppStrings.ui_device_directory_will_traversed_local_copy_task_will_created
    ),
    DeleteDeviceFiles(
        title = AppStrings.ui_delete_device_files,
        warning = AppStrings.ui_device_directory_will_traversed_expanded_entries_each_selected_device
    ),
    DeleteLocalTargets(
        title = AppStrings.ui_delete_local_test_target,
        warning = AppStrings.ui_test_targets_derived_device_remote_source_path_local_target
    )
}

@Composable
private fun SectionTitle(
    text: String,
    includeTopPadding: Boolean = true,
) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(
            start = 16.dp,
            top = if (includeTopPadding) 12.dp else 0.dp,
            end = 16.dp,
            bottom = 12.dp,
        )
    )
}

@Composable
private fun TransferTextField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    supportingText: String,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label) },
        supportingText = { Text(supportingText) },
        minLines = 3,
        maxLines = 6,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp)
    )
}

@Composable
private fun SingleLineTextField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    supportingText: String,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label) },
        supportingText = { Text(supportingText) },
        singleLine = true,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp)
    )
}

@Composable
private fun ConfirmTransferTestDialog(
    action: TransferTestAction,
    selectedDeviceCount: Int,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(action.title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(action.warning)
                Text(
                    text = AppStrings.ui_arg0_devices_selected.format(arg0 = (selectedDeviceCount).toString()),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(AppStrings.ui_confirm)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(AppStrings.ui_cancel)
            }
        }
    )
}

private fun updateDeviceSelection(
    selectedDeviceIds: MutableList<String>,
    deviceId: String,
    selected: Boolean,
) {
    if (selected) {
        if (deviceId !in selectedDeviceIds) {
            selectedDeviceIds.add(deviceId)
        }
    } else {
        selectedDeviceIds.remove(deviceId)
    }
}

private data class TransferTraversalEntry(
    val source: FileSimpleInfo,
    val relativePath: String,
)

private suspend fun executeTransferTestAction(
    action: TransferTestAction,
    selectedDevices: List<Device>,
    localSourcePaths: String,
    remoteSourcePaths: String,
    remoteTargetDirectories: Map<String, String>,
    localTargetDirectory: String,
    fileState: FileState,
): String {
    return when (action) {
        TransferTestAction.CopyLocalToDevices -> copyLocalFilesToDevices(
            selectedDevices = selectedDevices,
            localSourcePaths = localSourcePaths,
            remoteTargetDirectories = remoteTargetDirectories,
            fileState = fileState,
        )

        TransferTestAction.CopyDevicesToLocal -> copyDeviceFilesToLocal(
            selectedDevices = selectedDevices,
            remoteSourcePaths = remoteSourcePaths,
            localTargetDirectory = localTargetDirectory,
            fileState = fileState,
        )

        TransferTestAction.DeleteDeviceFiles -> deleteDeviceFiles(
            selectedDevices = selectedDevices,
            remoteSourcePaths = remoteSourcePaths,
            fileState = fileState,
        )

        TransferTestAction.DeleteLocalTargets -> deleteLocalTargets(
            selectedDevices = selectedDevices,
            remoteSourcePaths = remoteSourcePaths,
            localTargetDirectory = localTargetDirectory,
            fileState = fileState,
        )
    }
}

private suspend fun copyLocalFilesToDevices(
    selectedDevices: List<Device>,
    localSourcePaths: String,
    remoteTargetDirectories: Map<String, String>,
    fileState: FileState,
): String {
    val devices = requireSelectedDevices(selectedDevices)
    val sourceEntries = resolveLocalSources(localSourcePaths, includeNonEmptyDirectories = true)
    val directoryEntries = sourceEntries.filter { entry -> entry.source.isDirectory }
    val fileEntries = sourceEntries.filterNot { entry -> entry.source.isDirectory }

    var count = 0
    for (device in devices) {
        val targetDirectory = requirePathValue(
            remoteTargetDirectories[device.id].orEmpty(),
            AppStrings.ui_arg0_target_directory.format(arg0 = device.name.ifBlank { device.id })
        )
        createDeviceDirectories(
            device = device,
            paths = directoryEntries.map { entry ->
                joinTransferTestPath(targetDirectory, entry.relativePath, device.pathSeparator)
            }
        )
        for ((source, relativePath) in fileEntries) {
            val targetPath = joinTransferTestPath(targetDirectory, relativePath, device.pathSeparator)
            val destination = source.withCopy(
                name = source.name,
                isDirectory = source.isDirectory,
                size = source.size,
                path = targetPath,
                protocol = FileProtocol.Device,
                protocolId = device.id,
            )
            fileState.enqueueCopyFile(copyTask(source), source, destination)
            count++
        }
    }
    return AppStrings.ui_arg0_copy_device_tasks_submitted.format(arg0 = (count).toString())
}

private suspend fun copyDeviceFilesToLocal(
    selectedDevices: List<Device>,
    remoteSourcePaths: String,
    localTargetDirectory: String,
    fileState: FileState,
): String {
    val devices = requireSelectedDevices(selectedDevices)
    val remotePaths = requirePathList(remoteSourcePaths, AppStrings.ui_device_source_path)
    val localDirectory = requirePathValue(localTargetDirectory, AppStrings.ui_local_target_directory)
    val localSeparator = PathUtils.getPathSeparator()
    withContext(Dispatchers.Default) {
        PathUtils.createDirectoryIfNotExists(FileAccessPermission.Allowed, localDirectory)
    }

    val operations = mutableListOf<Pair<FileSimpleInfo, FileSimpleInfo>>()
    for (device in devices) {
        val deviceDirectory = joinTransferTestPath(
            localDirectory,
            sanitizePathSegment(device.name.ifBlank { device.id }),
            localSeparator
        )
        withContext(Dispatchers.Default) {
            PathUtils.createDirectoryIfNotExists(FileAccessPermission.Allowed, deviceDirectory)
        }
        val sourceEntries = resolveDeviceSources(device, remotePaths, includeNonEmptyDirectories = true)
        val directoryEntries = sourceEntries.filter { entry -> entry.source.isDirectory }
        val fileEntries = sourceEntries.filterNot { entry -> entry.source.isDirectory }
        withContext(Dispatchers.Default) {
            directoryEntries.forEach { entry ->
                PathUtils.createDirectoryIfNotExists(FileAccessPermission.Allowed, joinTransferTestPath(deviceDirectory, entry.relativePath, localSeparator))
            }
        }
        for ((source, relativePath) in fileEntries) {
            val targetPath = joinTransferTestPath(deviceDirectory, relativePath, localSeparator)
            val destination = source.withCopy(
                path = targetPath,
                protocol = FileProtocol.Local,
                protocolId = "",
            )
            operations.add(source to destination)
        }
    }

    operations.forEach { (source, destination) ->
        fileState.enqueueCopyFile(copyTask(source), source, destination)
    }
    return AppStrings.ui_arg0_copy_local_tasks_submitted.format(arg0 = (operations.size).toString())
}

private suspend fun deleteDeviceFiles(
    selectedDevices: List<Device>,
    remoteSourcePaths: String,
    fileState: FileState,
): String {
    val devices = requireSelectedDevices(selectedDevices)
    val remotePaths = requirePathList(remoteSourcePaths, AppStrings.ui_device_deletion_path)

    val targets = mutableListOf<FileSimpleInfo>()
    for (device in devices) {
        targets.addAll(
            resolveDeviceSources(device, remotePaths, includeNonEmptyDirectories = true)
                .map { entry -> entry.source }
                .sortedWith(deleteOrderComparator(device.pathSeparator))
        )
    }

    targets.forEach { target ->
        fileState.deleteFile(deleteTask(target), target)
    }
    return AppStrings.ui_arg0_device_removal_tasks_submitted.format(arg0 = (targets.size).toString())
}

private suspend fun deleteLocalTargets(
    selectedDevices: List<Device>,
    remoteSourcePaths: String,
    localTargetDirectory: String,
    fileState: FileState,
): String {
    val devices = requireSelectedDevices(selectedDevices)
    val remotePaths = requirePathList(remoteSourcePaths, AppStrings.ui_device_source_path)
    val localDirectory = requirePathValue(localTargetDirectory, AppStrings.ui_local_target_directory)
    val localSeparator = PathUtils.getPathSeparator()

    val targets = mutableListOf<FileSimpleInfo>()
    for (device in devices) {
        val deviceDirectory = joinTransferTestPath(
            localDirectory,
            sanitizePathSegment(device.name.ifBlank { device.id }),
            localSeparator
        )
        val remoteEntries = resolveDeviceSources(device, remotePaths, includeNonEmptyDirectories = true)
        withContext(Dispatchers.Default) {
            for ((_, relativePath) in remoteEntries) {
                val localPath = joinTransferTestPath(deviceDirectory, relativePath, localSeparator)
                FileUtils.getFile(FileAccessPermission.Allowed, localPath).getOrNull()?.let { file ->
                    targets.add(file.withCopy(protocol = FileProtocol.Local, protocolId = ""))
                }
            }
        }
    }

    if (targets.isEmpty()) {
        throw IllegalArgumentException(AppStrings.transfer_no_local_delete_target)
    }

    targets.forEach { target ->
        fileState.deleteFile(deleteTask(target), target)
    }
    return AppStrings.ui_arg0_local_deletion_tasks_submitted.format(arg0 = (targets.size).toString())
}

private suspend fun resolveLocalSources(
    rawPaths: String,
    includeNonEmptyDirectories: Boolean,
): List<TransferTraversalEntry> {
    val paths = requirePathList(rawPaths, AppStrings.ui_local_source_path)
    return buildList {
        for (path in paths) {
            val source = withContext(Dispatchers.Default) {
                FileUtils.getFile(FileAccessPermission.Allowed, path).getOrElse { error ->
                    throw IllegalArgumentException(
                        AppStrings.transfer_local_file_query_failed.format(
                            path = path,
                            detail = error.message.orEmpty(),
                        ),
                    )
                }.withCopy(
                    protocol = FileProtocol.Local,
                    protocolId = "",
                )
            }
            addAll(expandLocalSource(source, includeNonEmptyDirectories))
        }
    }
}

private suspend fun resolveDeviceSources(
    device: Device,
    remotePaths: List<String>,
    includeNonEmptyDirectories: Boolean,
): List<TransferTraversalEntry> {
    return buildList {
        for (remotePath in remotePaths) {
            val source = device.files.get(remotePath).getOrElse { error ->
                throw IllegalArgumentException(
                    AppStrings.transfer_device_query_failed.format(
                        device = device.name.ifBlank { device.id },
                        path = remotePath,
                        detail = error.message.orEmpty(),
                    ),
                )
            }.withCopy(
                protocol = FileProtocol.Device,
                protocolId = device.id,
            )
            addAll(expandDeviceSource(device, source, includeNonEmptyDirectories))
        }
    }
}

private suspend fun expandLocalSource(
    source: FileSimpleInfo,
    includeNonEmptyDirectories: Boolean,
): List<TransferTraversalEntry> {
    return expandSource(
        root = source,
        separator = PathUtils.getPathSeparator(),
        includeNonEmptyDirectories = includeNonEmptyDirectories,
        listChildren = { directory ->
            withContext(Dispatchers.Default) {
                PathUtils.getFileAndFolder(FileAccessPermission.Allowed, directory.path).getOrElse { error ->
                    throw IllegalArgumentException(
                        AppStrings.transfer_local_directory_walk_failed.format(
                            path = directory.path,
                            detail = error.message.orEmpty(),
                        ),
                    )
                }.map { entry ->
                    entry.withCopy(protocol = FileProtocol.Local, protocolId = "")
                }
            }
        }
    )
}

private suspend fun createDeviceDirectories(
    device: Device,
    paths: List<String>,
) {
    val distinctPaths = paths
        .map { path -> path.trim() }
        .filter { path -> path.isNotEmpty() }
        .distinct()
        .sortedBy { path -> pathDepth(path, device.pathSeparator) }
    if (distinctPaths.isEmpty()) return
    val result = device.files.createFolders(distinctPaths)
    result.getOrElse { error ->
        throw IllegalArgumentException(
            AppStrings.transfer_device_create_directory_failed.format(
                device = device.name.ifBlank { device.id },
                detail = error.message.orEmpty(),
            ),
        )
    }.forEachIndexed { index, item ->
        if (item.isFailure || !item.getOrDefault(false)) {
            val path = distinctPaths.getOrElse(index) { "" }
            val message = item.exceptionOrNull()?.message ?: AppStrings.ui_creation_failed
            throw IllegalArgumentException(
                AppStrings.transfer_device_create_directory_at_path_failed.format(
                    device = device.name.ifBlank { device.id },
                    path = path,
                    detail = message,
                ),
            )
        }
    }
}

private suspend fun expandDeviceSource(
    device: Device,
    source: FileSimpleInfo,
    includeNonEmptyDirectories: Boolean,
): List<TransferTraversalEntry> {
    return expandSource(
        root = source,
        separator = device.pathSeparator,
        includeNonEmptyDirectories = includeNonEmptyDirectories,
        listChildren = { directory ->
            device.paths.getList(directory.path).getOrElse { error ->
                throw IllegalArgumentException(
                    AppStrings.transfer_device_directory_walk_failed.format(
                        device = device.name.ifBlank { device.id },
                        path = directory.path,
                        detail = error.message.orEmpty(),
                    )
                )
            }.map { entry ->
                entry.withCopy(protocol = FileProtocol.Device, protocolId = device.id)
            }
        }
    )
}

private suspend fun expandSource(
    root: FileSimpleInfo,
    separator: String,
    includeNonEmptyDirectories: Boolean,
    listChildren: suspend (FileSimpleInfo) -> List<FileSimpleInfo>,
): List<TransferTraversalEntry> {
    val normalizedSeparator = separator.ifBlank { "/" }
    if (!root.isDirectory) {
        return listOf(TransferTraversalEntry(root, pathName(root.path, normalizedSeparator)))
    }

    val rootName = pathName(root.path, normalizedSeparator)
    val pendingDirectories = ArrayDeque<FileSimpleInfo>()
    val discovered = mutableListOf<TransferTraversalEntry>()
    val directoryEntries = mutableListOf<TransferTraversalEntry>()
    val nonEmptyDirectories = mutableSetOf<String>()

    pendingDirectories.add(root)
    while (pendingDirectories.isNotEmpty()) {
        val directory = pendingDirectories.removeFirst()
        val children = listChildren(directory)
        if (children.isNotEmpty()) {
            nonEmptyDirectories.add(normalizeTransferPath(directory.path, normalizedSeparator))
        }
        for (child in children) {
            val relativePath = joinTransferTestPath(
                rootName,
                child.path.relativePathFrom(root.path, normalizedSeparator).ifBlank { child.name },
                normalizedSeparator
            )
            val entry = TransferTraversalEntry(child, relativePath)
            if (child.isDirectory) {
                directoryEntries.add(entry)
                pendingDirectories.add(child)
            } else {
                discovered.add(entry)
            }
        }
    }

    val emptyDirectories = directoryEntries.filter { entry ->
        includeNonEmptyDirectories ||
            normalizeTransferPath(entry.source.path, normalizedSeparator) !in nonEmptyDirectories
    }
    return emptyDirectories + discovered
}

private fun requireSelectedDevices(devices: List<Device>): List<Device> {
    if (devices.isEmpty()) {
        throw IllegalArgumentException(AppStrings.transfer_select_connected_device)
    }
    return devices
}

private fun requirePathList(raw: String, label: String): List<String> {
    val paths = raw
        .split('\n', ',')
        .map { path -> path.trim() }
        .filter { path -> path.isNotEmpty() }
        .distinct()
    if (paths.isEmpty()) {
        throw IllegalArgumentException(
            AppStrings.validation_field_required.format(field = label),
        )
    }
    return paths
}

private fun requirePathValue(path: String, label: String): String {
    val value = path.trim()
    if (value.isEmpty()) {
        throw IllegalArgumentException(
            AppStrings.validation_field_required.format(field = label),
        )
    }
    return value
}

private fun copyTask(source: FileSimpleInfo): Task {
    return Task(
        taskType = TaskType.Copy,
        status = StatusEnum.LOADING,
        values = mapOf("path" to source.path),
        protocol = source.protocol,
        protocolId = source.protocolId,
    )
}

private fun deleteTask(target: FileSimpleInfo): Task {
    return Task(
        taskType = TaskType.Delete,
        status = StatusEnum.LOADING,
        values = mapOf("path" to target.path),
        protocol = target.protocol,
        protocolId = target.protocolId,
    )
}

private fun joinTransferTestPath(
    directory: String,
    name: String,
    separator: String,
): String {
    val separatorValue = separator.ifBlank { "/" }
    val separatorChar = separatorValue.firstOrNull() ?: '/'
    val normalizedDirectory = directory.trim().trimEnd(separatorChar)
    val normalizedName = name.trim().trimStart(separatorChar)
    return if (normalizedDirectory.isBlank()) {
        separatorValue + normalizedName
    } else {
        normalizedDirectory + separatorValue + normalizedName
    }
}

private fun pathName(path: String, separator: String): String {
    val separatorChar = separator.ifBlank { "/" }.firstOrNull() ?: '/'
    val normalized = path.trim().trimEnd('/', '\\', separatorChar)
    val name = normalized.substringAfterLast('/').substringAfterLast('\\').substringAfterLast(separatorChar)
    return name.ifBlank { "transfer-test-item" }
}

private fun String.relativePathFrom(rootPath: String, separator: String): String {
    val separatorValue = separator.ifBlank { "/" }
    val separatorChar = separatorValue.firstOrNull() ?: '/'
    val normalizedRoot = rootPath.trim().trimEnd(separatorChar, '/', '\\')
    val normalizedPath = trim().trimStart(separatorChar, '/', '\\')
    val rootPrefix = normalizedRoot.trimStart(separatorChar, '/', '\\')
    return normalizedPath
        .removePrefix(rootPrefix)
        .trimStart(separatorChar, '/', '\\')
}

private fun normalizeTransferPath(path: String, separator: String): String {
    val separatorChar = separator.ifBlank { "/" }.firstOrNull() ?: '/'
    return path.trim().trimEnd(separatorChar, '/', '\\')
}

private fun deleteOrderComparator(separator: String): Comparator<FileSimpleInfo> {
    return compareByDescending<FileSimpleInfo> { file ->
        pathDepth(file.path, separator)
    }.thenByDescending { file -> file.path }
}

private fun pathDepth(path: String, separator: String): Int {
    return normalizeTransferPath(path, separator)
        .split(separator.ifBlank { "/" }.firstOrNull() ?: '/', '/', '\\')
        .count { part -> part.isNotBlank() }
}

private fun sanitizePathSegment(value: String): String {
    return value
        .replace(Regex("[\\\\/:*?\"<>|]+"), "_")
        .ifBlank { "device" }
}
