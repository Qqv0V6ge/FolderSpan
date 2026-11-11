package com.folderspan.ui.screen.file

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.staggeredgrid.LazyVerticalStaggeredGrid
import androidx.compose.foundation.lazy.staggeredgrid.StaggeredGridCells
import androidx.compose.foundation.lazy.staggeredgrid.StaggeredGridItemSpan
import androidx.compose.foundation.lazy.staggeredgrid.items
import androidx.compose.foundation.lazy.staggeredgrid.rememberLazyStaggeredGridState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.Computer
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Smartphone
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.folderspan.data.file.FileProtocol
import com.folderspan.extensions.formatFileSize
import com.folderspan.ui.components.file.FileIcon
import com.folderspan.ui.components.dialog.PathSelectorDialog
import com.folderspan.ui.components.dialog.FileWarningOperationDialog
import com.folderspan.ui.components.scaffold.AppScaffold
import com.folderspan.ui.components.grid.GridListFabPadding
import com.folderspan.ui.navigation.AppScreenRoute
import com.folderspan.ui.navigation.LocalAppNavigator
import com.folderspan.ui.navigation.currentOrThrow
import com.folderspan.ui.state.file.*
import com.folderspan.ui.state.main.Notification
import com.folderspan.ui.state.main.NotificationState
import com.folderspan.ui.state.main.NotificationDisplayOptions
import org.koin.compose.koinInject
import kotlin.time.Clock
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import strings.AppStrings

class CopyDestinationScreen(
    private val request: CopyDestinationRequest,
) : AppScreenRoute, AutoCloseable {
    private val initialSources = request.state.sources
    private val addedDirectories = mutableStateListOf<CopyDestination>().apply { addAll(request.state.selected.value) }
    private val removedDirectories = mutableStateListOf<CopyFileSnapshot>()
    private var accepted by mutableStateOf<List<CopyDestinationReceipt>>(emptyList())
    private var message by mutableStateOf<String?>(null)
    override val routeKey = "CopyDestination:${request.state.requestId}"
    override fun close() = request.close()

    @Composable
    override fun Content() {
        val navigator = LocalAppNavigator.currentOrThrow
        val state = request.state
        val controls by state.controls.collectAsState()
        val selected by state.selected.collectAsState()
        val sources by state.sourceSnapshots.collectAsState()
        val loads by request.discovery.loads.collectAsState()
        val scope = rememberCoroutineScope()
        val snackbar = remember { SnackbarHostState() }
        var submitting by remember { mutableStateOf(false) }
        var resolvingDirectory by remember { mutableStateOf(false) }
        var editingSources by remember { mutableStateOf(false) }
        var browsing by remember { mutableStateOf<CopyDestination?>(null) }
        val notifications = koinInject<NotificationState>()
        LaunchedEffect(loads) {
            selected.forEach { target ->
                val load = loads.firstOrNull { it.endpoint == target.directory.endpoint }
                if (load == null || load.error != null) {
                    state.updateAvailability(target.directory, load?.error ?: AppStrings.ui_target_endpoint_unavailable)
                } else if (!load.loading && target.unavailableReason != null) {
                    try {
                        request.resolveDirectory(target.directory.endpoint, target.directory.file)
                        state.updateAvailability(target.directory, null)
                    } catch (cancelled: CancellationException) { throw cancelled }
                    catch (error: Exception) { state.updateAvailability(target.directory, error.message ?: AppStrings.ui_target_unavailable) }
                }
            }
        }
        val closePage = { navigator.pop(); Unit }
        val notify: (String) -> Unit = { text -> scope.launch { snackbar.showSnackbar(text) }; Unit }
        val select: (CopyDestination) -> Unit = { destination ->
            val result = state.select(destination)
            if (result == CopyDestinationSelectionResult.Added && addedDirectories.none { it.directory.sameLocation(destination.directory) }) addedDirectories.add(destination)
            if (result != CopyDestinationSelectionResult.Added && result != CopyDestinationSelectionResult.AlreadySelected) {
                error(AppStrings.copy_destination_invalid_selection)
            }
            removedDirectories.removeAll { it.sameLocation(destination.directory) }
        }
        val warning by request.operationState.isWarningOperationDialog.collectAsState()
        if (warning) {
            FileWarningOperationDialog(request.operationState.files.filter { it.isConflict },
                onOperationTypeChange = { operation, type ->
                    val index = request.operationState.files.indexOf(operation)
                    if (index >= 0) request.operationState.files[index] = operation.withCopy(type = type)
                }, onConfirm = { request.operationState.updateWarningOperationDialog(false) },
                onCancel = { request.operationState.files.clear(); request.operationState.updateWarningOperationDialog(false) })
        }
        CopyDestinationPage(
            controls = controls, loads = loads, selected = selected,
            candidates = state.visibleDestinations((selected + loads.flatMap { it.destinations } + addedDirectories).fold(emptyList()) { known, target ->
                if (known.any { it.directory.sameLocation(target.directory) }) known else known + target
            }).filterNot { target -> removedDirectories.any { it.sameLocation(target.directory) } }
                .filter { target -> loads.any { it.endpoint == target.directory.endpoint } ||
                    selected.any { it.directory.sameLocation(target.directory) } || accepted.any { it.destination.directory.sameLocation(target.directory) } },
            sources = sources, busy = submitting || resolvingDirectory, accepted = accepted.map { it.destination }, message = message,
            snackbar = snackbar, onBack = closePage,
            onScroll = state::rememberScroll,
            onToggle = { target ->
                if (selected.any { it.directory.sameLocation(target.directory) }) state.remove(target.directory)
                else runCatching { select(target) }.onFailure { notify(it.message ?: AppStrings.copy_destination_invalid_selection) }
            },
            onToggleResource = { targets ->
                if (targets.all { target -> selected.any { it.directory.sameLocation(target.directory) } } ||
                    targets.any { target -> target.unavailableReason != null && selected.any { it.directory.sameLocation(target.directory) } }) {
                    targets.forEach { state.remove(it.directory) }
                } else {
                    val result = state.selectAll(targets)
                    if (result != CopyDestinationSelectionResult.Added && result != CopyDestinationSelectionResult.AlreadySelected) notify(AppStrings.copy_destination_invalid_selection)
                }
            },
            onRemove = { target ->
                if (!submitting && !resolvingDirectory && accepted.none { it.destination.directory.sameLocation(target.directory) }) {
                    state.remove(target.directory)
                    addedDirectories.removeAll { it.directory.sameLocation(target.directory) }
                    if (removedDirectories.none { it.sameLocation(target.directory) }) removedDirectories.add(target.directory)
                }
            },
            onAddSources = { editingSources = true },
            onRemoveSource = { source ->
                resolvingDirectory = true
                scope.launch {
                    try { request.removeSource(source); message = null }
                    catch (cancelled: CancellationException) { throw cancelled }
                    catch (error: Exception) { message = error.message ?: AppStrings.ui_source_endpoint_unavailable }
                    finally { resolvingDirectory = false }
                }
            },
            onBrowse = { browsing = it },
            onRetry = request.discovery::retry,
            onSubmit = {
                if (!submitting) {
                    submitting = true
                    scope.launch {
                        try {
                            val result = request.submit()
                            accepted = result.accepted
                            if (result.fullyAccepted) {
                                // Submission transfers ownership to Tasks; it does not mean the copy has finished.
                                notifications.upsert(Notification(
                                    id = Clock.System.now().toEpochMilliseconds(), title = AppStrings.copy_destination_title,
                                    message = AppStrings.copy_destination_submitted.format(count = result.accepted.size.toString()),
                                    displayOptions = NotificationDisplayOptions(showInBanner = true, sendSystemNotification = false),
                                ))
                                closePage()
                            } else {
                                message = AppStrings.copy_destination_partial.format(accepted = result.accepted.size.toString(), remaining = result.remaining.size.toString()) +
                                    result.error?.let { "\n$it" }.orEmpty()
                            }
                        } catch (cancelled: CancellationException) {
                            throw cancelled
                        } catch (error: Exception) {
                            message = error.message ?: AppStrings.ui_file_copy_failed
                        } finally { submitting = false }
                    }
                }
            },
        )
        if (editingSources) CopySourceFilesDialog(
            request = request, initialSources = initialSources, loads = loads,
            onDismiss = { editingSources = false },
            onConfirm = { endpoint, files ->
                editingSources = false
                resolvingDirectory = true
                scope.launch {
                    try { request.addSources(endpoint, files); message = null }
                    catch (cancelled: CancellationException) { throw cancelled }
                    catch (error: Exception) { message = error.message ?: AppStrings.ui_source_endpoint_unavailable }
                    finally { resolvingDirectory = false }
                }
            },
        )
        browsing?.let { target ->
            val desk = request.discovery.destinationDesk(target.directory.endpoint)
            if (desk != null) PathSelectorDialog(
                deskType = desk, openPath = target.directory.location.path, initialDirectory = target.directory.file,
                onDismiss = { browsing = null },
                onConfirmFiles = { files ->
                    browsing = null
                    resolvingDirectory = true
                    scope.launch {
                        try {
                            val targets = files.map { request.resolveDirectory(target.directory.endpoint, it) }
                            val result = state.selectAll(targets)
                            check(result == CopyDestinationSelectionResult.Added || result == CopyDestinationSelectionResult.AlreadySelected) {
                                AppStrings.copy_destination_invalid_selection
                            }
                            targets.forEach { destination ->
                                removedDirectories.removeAll { it.sameLocation(destination.directory) }
                                if (addedDirectories.none { it.directory.sameLocation(destination.directory) }) addedDirectories.add(destination)
                            }
                        } catch (cancelled: CancellationException) { throw cancelled }
                        catch (error: Exception) { notify(error.message ?: AppStrings.ui_target_unavailable) }
                        finally { resolvingDirectory = false }
                    }
                },
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun CopyDestinationPage(
    controls: CopyDestinationControls,
    loads: List<CopyDestinationLoad>,
    selected: List<CopyDestination>,
    candidates: List<CopyDestination>,
    sources: List<CopyFileSnapshot>,
    busy: Boolean,
    accepted: List<CopyDestination>,
    message: String?,
    snackbar: SnackbarHostState = remember { SnackbarHostState() },
    onBack: () -> Unit,
    onScroll: (Int, Int) -> Unit,
    onToggle: (CopyDestination) -> Unit,
    onToggleResource: (List<CopyDestination>) -> Unit,
    onRemove: (CopyDestination) -> Unit,
    onBrowse: (CopyDestination) -> Unit,
    onRetry: (CopyEndpointIdentity) -> Unit,
    onSubmit: () -> Unit,
    onAddSources: () -> Unit,
    onRemoveSource: (CopyFileSnapshot) -> Unit,
) {
    val pending = selected.filterNot { target -> accepted.any { it.directory.sameLocation(target.directory) } }
    val canSubmit = !busy && sources.isNotEmpty() && pending.isNotEmpty() && pending.none { it.unavailableReason != null }
    val resources = loads.filter { it.endpoint.endpoint.protocol != FileProtocol.Share }
        .distinctBy { it.endpoint }.sortedBy { it.endpoint.endpoint.protocol.ordinal }
    val gridState = rememberLazyStaggeredGridState(controls.firstVisibleItemIndex, controls.firstVisibleItemOffset)
    val resourceEndpoints = (resources.map { it.endpoint } + candidates.map { it.directory.endpoint })
        .filter { it.endpoint.protocol != FileProtocol.Share }.distinct()
    var showingSources by remember { mutableStateOf(false) }
    LaunchedEffect(gridState) {
        snapshotFlow { gridState.firstVisibleItemIndex to gridState.firstVisibleItemScrollOffset }
            .distinctUntilChanged().collect { onScroll(it.first, it.second) }
    }
    AppScaffold(
        topBar = { TopAppBar(
            title = { Text(AppStrings.copy_destination_title) },
            navigationIcon = { IconButton(onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, AppStrings.copy_destination_back) } },
            actions = {
                BadgedBox(badge = { if (!showingSources) Badge { Text(if (sources.size > 99) "99+" else sources.size.toString()) } }) {
                    IconButton({ showingSources = !showingSources }, modifier = Modifier.testTag("copy-view-sources")) {
                        Icon(if (showingSources) Icons.Default.Close else Icons.Default.Description, AppStrings.copy_destination_view_sources)
                    }
                }
            },
        ) },
        snackbarHost = { SnackbarHost(snackbar) },
        floatingActionButton = {
            // Material 3 EFAB 没有 enabled 参数，提交守卫与禁用语义保持一致。
            if (selected.isNotEmpty()) ExtendedFloatingActionButton(
                onClick = { if (canSubmit) onSubmit() },
                modifier = Modifier.testTag("copy-submit").semantics {
                    contentDescription = "${AppStrings.ui_copy}, ${AppStrings.copy_destination_selected.format(count = selected.size.toString())}"
                    if (!canSubmit) disabled()
                },
                containerColor = if (canSubmit) FloatingActionButtonDefaults.containerColor else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.12f),
                contentColor = if (canSubmit) contentColorFor(FloatingActionButtonDefaults.containerColor) else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f),
                icon = {
                    if (busy) CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
                    else Icon(Icons.Default.ContentCopy, contentDescription = null)
                },
                text = { Text("${AppStrings.ui_copy} (${selected.size})") },
            )
        },
    ) { padding ->
        LazyVerticalStaggeredGrid(
            columns = StaggeredGridCells.Adaptive(320.dp),
            state = gridState,
            modifier = Modifier.fillMaxSize().padding(padding).testTag("copy-target-list"),
            contentPadding = PaddingValues(start = 8.dp, end = 8.dp, bottom = GridListFabPadding),
            verticalItemSpacing = 8.dp,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(resourceEndpoints, key = { "resource:${it.connectionBinding}" }) { endpoint ->
                val load = resources.firstOrNull { it.endpoint == endpoint }
                val targets = candidates.filter { it.directory.endpoint == endpoint }
                val checkedCount = targets.count { target -> selected.any { it.directory.sameLocation(target.directory) } }
                val editable = targets.filterNot { target -> accepted.any { it.directory.sameLocation(target.directory) } }
                val canSelect = !busy && editable.isNotEmpty() && (checkedCount > 0 || (load?.error == null && editable.any { it.unavailableReason == null }))
                val selection = when {
                    checkedCount == 0 -> ToggleableState.Off
                    checkedCount == targets.size -> ToggleableState.On
                    else -> ToggleableState.Indeterminate
                }
                val defaultDirectory = load?.destinations?.firstOrNull()
                val browseTarget = defaultDirectory?.takeIf { root -> targets.none { it.directory.sameLocation(root.directory) } }
                    ?: targets.firstOrNull { target -> selected.any { it.directory.sameLocation(target.directory) } } ?: targets.firstOrNull()
                val name = load?.connectionName ?: targets.first().connectionName
                val color = if (checkedCount > 0) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceContainerLow
                Surface(shape = MaterialTheme.shapes.large, color = color,
                    modifier = Modifier.fillMaxWidth().testTag("copy-resource:${endpoint.connectionBinding}")) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row(Modifier.fillMaxWidth().testTag("copy-resource-header:${endpoint.connectionBinding}"),
                            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Icon(endpoint.endpoint.protocol.copyDestinationIcon(), null, Modifier.size(24.dp))
                            Text(name, Modifier.weight(1f), style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            IconButton({ browseTarget?.let(onBrowse) }, enabled = !busy && browseTarget != null,
                                modifier = Modifier.size(48.dp).testTag("copy-add-directory:${endpoint.connectionBinding}")) {
                                Icon(Icons.Default.Add, AppStrings.copy_destination_add_directory)
                            }
                            TriStateCheckbox(selection, { onToggleResource(editable) }, enabled = canSelect,
                                modifier = Modifier.size(48.dp).semantics { contentDescription = name }.testTag("copy-resource-select:${endpoint.connectionBinding}"))
                        }
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                        Text(AppStrings.copy_destination_save_locations, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        if (targets.isEmpty() && load?.loading != true && load?.error == null) {
                            Text(AppStrings.copy_destination_no_targets, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        targets.forEach { target ->
                            val checked = selected.any { it.directory.sameLocation(target.directory) }
                            val submitted = accepted.any { it.directory.sameLocation(target.directory) }
                            val isDefault = load?.destinations?.any { it.directory.sameLocation(target.directory) } == true
                            val reason = target.unavailableReason ?: load?.error
                            val canToggle = !busy && !submitted && (checked || reason == null)
                            Row(Modifier.fillMaxWidth()
                                .testTag("copy-target:${endpoint.connectionBinding}:${target.directory.location.path}"), verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                    Text(if (isDefault) AppStrings.copy_destination_home_directory else target.name.ifBlank { target.directory.location.path },
                                        style = MaterialTheme.typography.labelMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                    Text(target.directory.location.path, style = MaterialTheme.typography.bodyMedium, maxLines = 3, overflow = TextOverflow.Ellipsis)
                                    if (submitted) Text(AppStrings.copy_destination_submitted_target, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                                    else reason?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
                                }
                                IconButton({ onRemove(target) }, enabled = !busy && !submitted,
                                    modifier = Modifier.size(48.dp).testTag("copy-remove-directory:${endpoint.connectionBinding}:${target.directory.location.path}")) {
                                    Icon(Icons.Default.Delete, AppStrings.copy_destination_remove)
                                }
                                Checkbox(checked, { onToggle(target) }, enabled = canToggle, modifier = Modifier.size(48.dp)
                                    .semantics { contentDescription = target.directory.location.path }
                                    .testTag("copy-select-directory:${endpoint.connectionBinding}:${target.directory.location.path}"))
                            }
                        }
                        if (load?.loading == true) Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                            Text(AppStrings.ui_loading, style = MaterialTheme.typography.bodySmall)
                        } else if (load?.error != null) {
                            if (targets.isEmpty()) Text(load.error.orEmpty(), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
                            TextButton({ onRetry(endpoint) }, enabled = !busy) { Text(AppStrings.copy_destination_retry) }
                        }
                    }
                }
            }
            if (resourceEndpoints.isEmpty()) item("empty", span = StaggeredGridItemSpan.FullLine) {
                Text(AppStrings.copy_destination_no_targets, Modifier.padding(vertical = 16.dp),
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            message?.let { error -> item("admission-error", span = StaggeredGridItemSpan.FullLine) {
                Text(error, Modifier.fillMaxWidth().padding(vertical = 8.dp), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
            } }
        }
    }
    if (showingSources) {
        val sheetState = rememberBottomSheetState(initialValue = SheetValue.Hidden,
            enabledValues = setOf(SheetValue.Hidden, SheetValue.Expanded))
        ModalBottomSheet(onDismissRequest = { showingSources = false }, sheetState = sheetState) {
            Column(Modifier.fillMaxWidth().testTag("copy-source-sheet")) {
                Row(Modifier.fillMaxWidth().padding(start = 24.dp, end = 16.dp, bottom = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(AppStrings.copy_destination_source_files, style = MaterialTheme.typography.titleLarge)
                        Text(AppStrings.copy_destination_sources.format(count = sources.size.toString()), style = MaterialTheme.typography.bodyMedium)
                    }
                    IconButton(onAddSources, enabled = !busy && accepted.isEmpty(), modifier = Modifier.testTag("copy-add-sources")) {
                        Icon(Icons.Default.Add, AppStrings.copy_destination_add_sources)
                    }
                    IconButton({ showingSources = false }, modifier = Modifier.testTag("copy-close-sources")) { Icon(Icons.Default.Close, AppStrings.ui_close) }
                }
                HorizontalDivider()
                LazyColumn(Modifier.fillMaxWidth().weight(1f, fill = false).testTag("copy-source-list"), contentPadding = PaddingValues(bottom = 24.dp)) {
                    if (sources.isEmpty()) item {
                        Text(AppStrings.ui_no_files, Modifier.padding(24.dp))
                    }
                    if (accepted.isNotEmpty()) item {
                        Text(AppStrings.copy_destination_sources_locked, Modifier.padding(horizontal = 24.dp, vertical = 12.dp),
                            style = MaterialTheme.typography.bodyMedium)
                    }
                    message?.let { error -> item {
                        Text(error, Modifier.padding(horizontal = 24.dp, vertical = 12.dp).testTag("copy-source-error"),
                            color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
                    } }
                    items(sources) { source ->
                        val file = source.file
                        ListItem(headlineContent = { Text(file.name, maxLines = 2, overflow = TextOverflow.Ellipsis) },
                            leadingContent = { FileIcon(file) },
                            trailingContent = {
                                IconButton({ onRemoveSource(source) }, enabled = !busy && accepted.isEmpty(),
                                    modifier = Modifier.testTag("copy-remove-source:${source.endpoint.connectionBinding}:${file.path}")) {
                                    Icon(Icons.Default.Delete, AppStrings.copy_destination_remove_source)
                                }
                            },
                            supportingContent = { Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Text(file.path, maxLines = 3, overflow = TextOverflow.Ellipsis)
                                if (!file.isDirectory) Text(file.size.formatFileSize(), style = MaterialTheme.typography.bodySmall)
                            } })
                    }
                }
            }
        }
    }
}

private fun FileProtocol.copyDestinationIcon(): ImageVector = when (this) {
    FileProtocol.Local -> Icons.Default.Computer
    FileProtocol.Device -> Icons.Default.Smartphone
    FileProtocol.Network -> Icons.Default.Cloud
    FileProtocol.Share -> Icons.Default.FolderOpen
}
