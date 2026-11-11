package com.folderspan.ui.screen.sync

import androidx.compose.foundation.clickable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.folderspan.extensions.formatFileSize
import com.folderspan.ui.components.pagestate.*
import com.folderspan.ui.components.scaffold.AppScaffold
import com.folderspan.ui.navigation.AppScreenRoute
import com.folderspan.ui.navigation.LocalAppNavigator
import com.folderspan.ui.navigation.currentOrThrow
import com.folderspan.ui.state.main.*
import com.folderspan.utils.WindowSizeClass
import com.folderspan.utils.calculateWindowSizeClass
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.koin.compose.koinInject
import strings.AppStrings

class SyncRunPreviewScreen(private val taskId: Long, private val pendingDeletionsOnly: Boolean = false) : AppScreenRoute {
    override val routeKey: String get() = "SyncRunPreview:$taskId:$pendingDeletionsOnly"

    @Composable
    override fun Content() {
        val syncState = koinInject<SyncState>()
        val navigator = LocalAppNavigator.currentOrThrow
        val scope = rememberCoroutineScope()
        var preview by remember { mutableStateOf<SyncRunPreview?>(null) }
        var revision by remember { mutableIntStateOf(0) }
        var loading by remember { mutableStateOf(true) }
        var error by remember { mutableStateOf<PageErrorState?>(null) }
        var busy by remember { mutableStateOf(false) }
        var conditionConfirmation by remember { mutableStateOf<SyncConditionConfirmation?>(null) }
        var actionError by remember { mutableStateOf<String?>(null) }
        var filter by remember { mutableStateOf<SyncPreviewAction?>(null) }
        var conflictsOnly by remember { mutableStateOf(false) }
        var rows by remember { mutableStateOf<List<SyncPreviewItem>>(emptyList()) }
        var rowsLoading by remember { mutableStateOf(true) }
        var rowsError by remember { mutableStateOf<PageErrorState?>(null) }
        var append by remember { mutableStateOf<PageAppendState>(PageAppendState.Idle) }

        LaunchedEffect(syncState) {
            try {
                awaitCancellation()
            } finally {
                preview?.token?.let { token ->
                    withContext(NonCancellable) {
                        withTimeout(5_000) { syncState.discardSyncPreview(token) }
                    }
                }
            }
        }
        LaunchedEffect(taskId, pendingDeletionsOnly, revision) {
            loading = true
            error = null
            actionError = null
            preview?.let { syncState.discardSyncPreview(it.token) }
            preview = null
            try {
                preview = syncState.createSyncPreview(taskId, pendingDeletionsOnly).getOrThrow()
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) { error = failure.toPageErrorState() }
            finally { loading = false }
        }
        val token = preview?.token
        val count = preview?.filteredCount(filter, conflictsOnly) ?: 0
        LaunchedEffect(token, filter, conflictsOnly, revision) {
            rows = emptyList()
            rowsLoading = token != null
            rowsError = null
            append = PageAppendState.Idle
            if (token == null) return@LaunchedEffect
            try {
                rows = syncState.syncPreviewPage(token, action = filter, conflictsOnly = conflictsOnly)
                append = if (rows.size >= count) PageAppendState.End else PageAppendState.Idle
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) { rowsError = failure.toPageErrorState() }
            finally { rowsLoading = false }
        }
        val loadMore: () -> Unit = {
            if (token != null && !rowsLoading && append !is PageAppendState.Loading && rows.size < count) {
                val requestedFilter = filter
                val requestedConflicts = conflictsOnly
                val offset = rows.size
                append = PageAppendState.Loading
                scope.launch {
                    try {
                        val page = syncState.syncPreviewPage(token, offset, action = requestedFilter, conflictsOnly = requestedConflicts)
                        if (preview?.token == token && filter == requestedFilter && conflictsOnly == requestedConflicts && rows.size == offset) {
                            rows = rows + page
                            append = if (page.isEmpty() || rows.size >= count) PageAppendState.End else PageAppendState.Idle
                        }
                    } catch (cancelled: CancellationException) { throw cancelled }
                    catch (failure: Exception) {
                        if (preview?.token == token && filter == requestedFilter && conflictsOnly == requestedConflicts) {
                            append = PageAppendState.Error(failure.message)
                        }
                    }
                }
            }
        }
        val confirm: (Boolean) -> Unit = { ignore ->
            if (!busy && token != null) scope.launch {
                busy = true
                actionError = null
                syncState.confirmSyncPreview(token, ignoreConditions = ignore).fold(
                    onSuccess = { preview = null; navigator.pop(); navigator.push(SyncRunDetailScreen(taskId)) },
                    onFailure = { actionError = it.message; busy = false },
                )
            }
        }
        conditionConfirmation?.let { confirmation ->
            SyncConditionOverrideDialog(confirmation.conditions, onConfirm = {
                conditionConfirmation = null
                confirmation.run()
            }, onDismiss = { conditionConfirmation = null })
        }
        SyncRunPreviewPage(
            preview = preview, pageState = resolvePageViewState(loading, error), busy = busy,
            rows = rows, rowsState = resolvePageViewState(rowsLoading, rowsError, rows.isEmpty(), AppStrings.sync_preview_empty_group),
            appendState = append, filter = filter, conflictsOnly = conflictsOnly, actionError = actionError,
            onFilter = { action, conflicts -> filter = action; conflictsOnly = conflicts }, onLoadMore = loadMore,
            onRefresh = { if (!busy) revision++ },
            onCancel = {
                if (!busy) scope.launch {
                    busy = true
                    preview?.let { syncState.discardSyncPreview(it.token) }
                    preview = null
                    navigator.pop()
                }
            },
            onConfirm = {
                val missing = syncState.getTask(taskId)?.let(syncState::unmetRunConditions).orEmpty()
                if (missing.isEmpty()) confirm(false)
                else conditionConfirmation = SyncConditionConfirmation(missing) { confirm(true) }
            },
            onReject = {
                if (!busy && token != null) scope.launch {
                    busy = true
                    actionError = null
                    try {
                        val runId = preview?.pendingRunId ?: 0
                        check(syncState.rejectPendingSyncDeletions(taskId, runId)) { AppStrings.sync_pending_deletions_changed }
                        preview = null
                        navigator.pop()
                    } catch (cancelled: CancellationException) { throw cancelled }
                    catch (failure: Exception) { actionError = failure.message; busy = false }
                }
            },
        )
    }
}

internal fun SyncRunPreview.filteredCount(action: SyncPreviewAction?, conflictsOnly: Boolean): Int = when {
    conflictsOnly -> conflictItemCount
    action != null -> counts[action]?.count ?: 0
    else -> itemCount
}

internal fun syncPreviewSummaryWidth(width: WindowSizeClass): Dp? = when (width) {
    WindowSizeClass.Compact -> null
    WindowSizeClass.Medium -> 260.dp
    WindowSizeClass.Expanded -> 320.dp
    WindowSizeClass.Large -> 360.dp
    WindowSizeClass.ExtraLarge -> 400.dp
}

internal fun syncModeLabel(mode: SyncMode): String = when (mode) {
    SyncMode.Update -> AppStrings.sync_mode_update
    SyncMode.Mirror -> AppStrings.sync_mode_mirror
    SyncMode.BackupThenClean -> AppStrings.sync_mode_backup_clean
    SyncMode.Bidirectional -> AppStrings.sync_mode_bidirectional
}

private fun previewActionLabel(action: SyncPreviewAction): String = when (action) {
    SyncPreviewAction.CreateDirectory -> AppStrings.sync_preview_create_directory
    SyncPreviewAction.Copy -> AppStrings.sync_preview_copy
    SyncPreviewAction.ConflictCopy -> AppStrings.sync_preview_conflict_copy
    SyncPreviewAction.Overwrite -> AppStrings.sync_preview_overwrite
    SyncPreviewAction.Delete -> AppStrings.sync_preview_delete
    SyncPreviewAction.Recycle -> AppStrings.sync_preview_recycle
    SyncPreviewAction.CleanEmptyDirectory -> AppStrings.sync_preview_empty_directory
    SyncPreviewAction.CleanSource -> AppStrings.sync_preview_clean_source
    SyncPreviewAction.Unchanged -> AppStrings.sync_preview_unchanged
    SyncPreviewAction.Skipped -> AppStrings.sync_preview_skipped
    SyncPreviewAction.HandledWithParent -> AppStrings.sync_preview_parent
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SyncRunPreviewPage(
    preview: SyncRunPreview?, pageState: PageViewState, busy: Boolean,
    rows: List<SyncPreviewItem>, rowsState: PageViewState, appendState: PageAppendState,
    filter: SyncPreviewAction?, conflictsOnly: Boolean, actionError: String?,
    onFilter: (SyncPreviewAction?, Boolean) -> Unit, onLoadMore: () -> Unit,
    onRefresh: () -> Unit, onCancel: () -> Unit, onConfirm: () -> Unit, onReject: () -> Unit,
    modifier: Modifier = Modifier, separatingPaneGap: Dp = 0.dp,
) {
    var showItems by rememberSaveable(preview?.token) { mutableStateOf(false) }
    var emptyConfirmed by rememberSaveable(preview?.token) { mutableStateOf(false) }
    AppScaffold(
        topBar = {
            TopAppBar(title = { Text(AppStrings.sync_preview_title) }, navigationIcon = {
                IconButton(onClick = onCancel, enabled = !busy) {
                    Icon(Icons.AutoMirrored.Default.ArrowBack, AppStrings.sync_preview_cancel)
                }
            }, actions = {
                IconButton(onClick = onRefresh, enabled = !busy && pageState !is PageViewState.Loading) {
                    Icon(Icons.Default.Refresh, AppStrings.sync_preview_refresh)
                }
            })
        },
    ) { padding ->
        PageStateLayout(pageState, modifier.fillMaxSize().padding(padding), onRetry = onRefresh) {
            if (preview != null) BoxWithConstraints(Modifier.fillMaxSize()) {
                val summaryWidth = syncPreviewSummaryWidth(calculateWindowSizeClass(maxWidth, maxHeight))
                val overview: @Composable (Modifier) -> Unit = { paneModifier ->
                    PreviewOverview(preview, busy, emptyConfirmed, actionError, paneModifier,
                        onEmptyConfirmed = { emptyConfirmed = it },
                        onFilter = { action, conflicts -> onFilter(action, conflicts); showItems = true },
                        onConfirm = onConfirm, onCancel = onCancel, onReject = onReject)
                }
                val detail: @Composable (Modifier) -> Unit = { paneModifier ->
                    PreviewItems(preview, rows, rowsState, appendState, filter, conflictsOnly, paneModifier,
                        onLoadMore = onLoadMore, onRetry = onRefresh,
                        onBack = if (summaryWidth == null) ({ showItems = false }) else null)
                }
                if (summaryWidth == null) {
                    if (showItems) detail(Modifier.fillMaxSize()) else overview(Modifier.fillMaxSize())
                } else Row(Modifier.fillMaxSize()) {
                    overview(Modifier.width(summaryWidth).fillMaxHeight())
                    if (separatingPaneGap > 0.dp) Spacer(Modifier.width(separatingPaneGap)) else VerticalDivider()
                    detail(Modifier.weight(1f).fillMaxHeight())
                }
            }
        }
    }
}

@Composable
private fun PreviewOverview(
    preview: SyncRunPreview, busy: Boolean, emptyConfirmed: Boolean, error: String?, modifier: Modifier,
    onEmptyConfirmed: (Boolean) -> Unit, onFilter: (SyncPreviewAction?, Boolean) -> Unit,
    onConfirm: () -> Unit, onCancel: () -> Unit, onReject: () -> Unit,
) {
    LazyColumn(modifier.testTag("sync-preview-overview")) {
        item("heading") {
            ListItem(headlineContent = { Text(preview.taskName.ifBlank { AppStrings.ui_unnamed_sync }) },
                supportingContent = { Text(syncModeLabel(preview.mode)) })
            Text(AppStrings.sync_preview_keep_changed, Modifier.padding(horizontal = 16.dp), style = MaterialTheme.typography.bodySmall)
            if (preview.requiresFirstConfirmation) PreviewNotice(AppStrings.sync_preview_first)
            if (preview.unexpectedlyEmptyScopes.isNotEmpty()) {
                PreviewNotice(AppStrings.sync_preview_empty_warning)
                preview.unexpectedlyEmptyScopes.forEach { (participant, path) ->
                    Text("${preview.participants[participant] ?: participant}: $path", Modifier.padding(horizontal = 16.dp))
                }
                ListItem(modifier = Modifier.testTag("sync-preview-empty-confirm").toggleable(emptyConfirmed,
                    enabled = !busy, role = Role.Checkbox, onValueChange = onEmptyConfirmed),
                    headlineContent = { Text(AppStrings.sync_preview_empty_confirm) }, leadingContent = {
                        Checkbox(emptyConfirmed, onCheckedChange = null, enabled = !busy)
                    })
            }
            if (!preview.recycleEnabled && preview.mode in listOf(SyncMode.Mirror, SyncMode.Bidirectional)) PreviewNotice(AppStrings.sync_preview_no_recycle)
            if (preview.mode == SyncMode.BackupThenClean) PreviewNotice(AppStrings.sync_preview_cleanup_warning)
            HorizontalDivider(Modifier.padding(top = 12.dp))
        }
        item("all") { PreviewGroupRow(AppStrings.sync_preview_all, preview.itemCount, null) { onFilter(null, false) } }
        itemsIndexed(preview.counts.entries.toList(), key = { _, item -> "group:${item.key}" }) { _, (action, count) ->
            PreviewGroupRow(previewActionLabel(action), count.count, count.bytes) { onFilter(action, false) }
        }
        if (preview.conflictCount > 0) item("conflicts") {
            ListItem(modifier = Modifier.clickable { onFilter(null, true) }, headlineContent = { Text(AppStrings.sync_preview_conflicts) },
                supportingContent = { Text(AppStrings.sync_preview_conflict_count.format(arg0 = preview.conflictCount.toString(), arg1 = preview.conflictItemCount.toString())) })
        }
        item("participants-heading") {
            HorizontalDivider()
            PreviewNotice(if (preview.fixedRecommendedTarget) AppStrings.sync_fixed_recommended_target_hint else AppStrings.sync_preview_fixed_targets)
        }
        itemsIndexed(preview.participants.entries.toList(), key = { _, participant -> "participant:${participant.key}" }) { _, (id, label) ->
            ListItem(headlineContent = { Text(label) }, supportingContent = {
                Column { preview.participantPaths[id].orEmpty().forEach { Text(it) } }
            })
        }
        item("actions") {
            Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(AppStrings.sync_preview_read_bytes.format(arg0 = preview.comparisonReadBytes.formatFileSize()), style = MaterialTheme.typography.bodySmall)
                if (error != null) Text(error, color = MaterialTheme.colorScheme.error)
                Button(onConfirm, enabled = !busy && (preview.unexpectedlyEmptyScopes.isEmpty() || emptyConfirmed),
                    modifier = Modifier.fillMaxWidth().testTag("sync-preview-confirm")) { Text(AppStrings.sync_preview_confirm) }
                if (preview.pendingDeletionsOnly) OutlinedButton(onReject, enabled = !busy, modifier = Modifier.fillMaxWidth()) { Text(AppStrings.sync_preview_reject) }
                TextButton(onCancel, enabled = !busy, modifier = Modifier.fillMaxWidth()) { Text(AppStrings.sync_preview_cancel) }
            }
        }
    }
}

@Composable
private fun PreviewNotice(message: String) {
    Text(message, Modifier.padding(horizontal = 16.dp, vertical = 8.dp), style = MaterialTheme.typography.bodyMedium)
}

@Composable
private fun PreviewGroupRow(label: String, count: Int, bytes: Long?, onClick: () -> Unit) {
    ListItem(modifier = Modifier.clickable(onClick = onClick), headlineContent = { Text(label) }, supportingContent = {
        Text(if (bytes == null) count.toString() else AppStrings.sync_preview_count.format(arg0 = count.toString(), arg1 = bytes.formatFileSize()))
    })
}

@Composable
private fun PreviewItems(
    preview: SyncRunPreview, rows: List<SyncPreviewItem>, state: PageViewState, appendState: PageAppendState,
    filter: SyncPreviewAction?, conflictsOnly: Boolean, modifier: Modifier,
    onLoadMore: () -> Unit, onRetry: () -> Unit, onBack: (() -> Unit)?,
) {
    val listState = rememberLazyListState()
    LaunchedEffect(preview.token, filter, conflictsOnly) { listState.scrollToItem(0) }
    LoadMoreOnNearEnd(listState, appendState is PageAppendState.Idle, onLoadMore)
    Column(modifier.testTag("sync-preview-items")) {
        ListItem(headlineContent = { Text(if (conflictsOnly) AppStrings.sync_preview_conflicts else filter?.let(::previewActionLabel) ?: AppStrings.sync_preview_all) },
            leadingContent = if (onBack != null) ({
                IconButton(onBack) { Icon(Icons.AutoMirrored.Default.ArrowBack, AppStrings.sync_preview_back_summary) }
            }) else null)
        HorizontalDivider()
        PageStateLayout(state, Modifier.weight(1f), onRetry = onRetry) {
            LazyColumn(Modifier.fillMaxSize().testTag("sync-preview-row-list"), state = listState) {
                itemsIndexed(rows, key = { index, _ -> "${preview.token}:$filter:$conflictsOnly:$index" }, contentType = { _, _ -> "preview-item" }) { _, row ->
                    ListItem(headlineContent = { Text(row.relativePath) }, supportingContent = {
                        Column {
                            Text(listOfNotNull(previewActionLabel(row.action), preview.participants[row.participant], row.size.formatFileSize(),
                                AppStrings.sync_preview_conflict_tag.takeIf { row.conflict }).joinToString(" · "))
                            row.sourceParticipant?.let { source ->
                                Text(AppStrings.sync_preview_source.format(arg0 = "${preview.participants[source] ?: source}: ${row.sourceRelativePath}"))
                            }
                        }
                    })
                }
                pageAppendFooter(appendState, onRetry = onLoadMore)
            }
        }
    }
}
