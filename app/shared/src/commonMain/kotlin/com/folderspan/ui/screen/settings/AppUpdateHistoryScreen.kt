package com.folderspan.ui.screen.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.folderspan.extensions.timestampToYMDHM
import com.folderspan.openUrl
import com.folderspan.pro.di.AppUpdateRuntime
import com.folderspan.pro.domain.model.AppUpdate
import com.folderspan.pro.domain.model.AppUpdateHistorySnapshot
import com.folderspan.ui.components.error.ErrorConnection
import com.folderspan.ui.components.error.ErrorEmptyData
import com.folderspan.ui.components.pagestate.LoadMoreOnNearEnd
import com.folderspan.ui.components.pagestate.PageAppendState
import com.folderspan.ui.components.pagestate.PageErrorState
import com.folderspan.ui.components.pagestate.PageErrorType
import com.folderspan.ui.components.pagestate.PageRefreshState
import com.folderspan.ui.components.pagestate.PageStateLayout
import com.folderspan.ui.components.pagestate.PageViewState
import com.folderspan.ui.components.pagestate.pageAppendFooter
import com.folderspan.ui.components.pagestate.resolvePageViewState
import com.folderspan.ui.components.scaffold.AppScaffold
import com.folderspan.ui.navigation.AppScreenRoute
import com.folderspan.ui.navigation.LocalAppNavigator
import com.folderspan.ui.navigation.currentOrThrow
import com.folderspan.utils.currentAppVersion
import com.folderspan.utils.httpOrHttpsUrlOrNull
import kotlinx.coroutines.launch
import org.koin.compose.koinInject
import strings.AppStrings

private val HistorySpacingMd = 16.dp
private val HistorySpacingLg = 24.dp
private val HistoryTouchTarget = 48.dp
private val HistoryListPaneMinWidth = 280.dp
private val HistoryListPaneMaxWidth = 420.dp

internal fun shouldUseAppUpdateHistorySplitPane(maxWidth: Dp): Boolean = maxWidth >= 600.dp

internal fun AppUpdateHistorySnapshot.toPageViewState(): PageViewState =
    resolvePageViewState(
        isLoading = isRefreshing && items.isEmpty() && errorMessage == null,
        errorState = errorMessage?.takeIf { items.isEmpty() && it.isNotBlank() }?.let { message ->
            PageErrorState(PageErrorType.Connection, message)
        },
        isEmpty = items.isEmpty(),
        emptyMessage = AppStrings.settings_about_software_history_empty,
    )

internal fun AppUpdateHistorySnapshot.toPageRefreshState(): PageRefreshState = when {
    isRefreshing && items.isNotEmpty() -> PageRefreshState.Refreshing
    !errorMessage.isNullOrBlank() && items.isNotEmpty() && !isLoadingMore -> {
        PageRefreshState.Error(errorMessage)
    }
    else -> PageRefreshState.Idle
}

internal fun AppUpdateHistorySnapshot.toPageAppendState(): PageAppendState = when {
    items.isEmpty() -> PageAppendState.Idle
    isLoadingMore -> PageAppendState.Loading
    else -> PageAppendState.Idle
}

class AppUpdateHistoryScreen : AppScreenRoute {
    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    override fun Content() {
        val navigator = LocalAppNavigator.currentOrThrow
        val runtime = koinInject<AppUpdateRuntime>()
        val history by runtime.history.collectAsState()
        val channel by runtime.selectedChannel.collectAsState()
        val scope = rememberCoroutineScope()
        var selected by remember { mutableStateOf<AppUpdate?>(null) }

        LaunchedEffect(channel) {
            selected = null
            runtime.refreshHistory()
        }

        AppScaffold(
            topBar = {
                TopAppBar(
                    title = { Text(AppStrings.settings_about_software_history) },
                    navigationIcon = {
                        IconButton({ navigator.pop() }) {
                            Icon(Icons.AutoMirrored.Default.ArrowBack, contentDescription = null)
                        }
                    },
                )
            },
        ) { padding ->
            AppUpdateHistoryContent(
                snapshot = history,
                currentVersion = currentAppVersion(),
                selected = selected,
                onSelect = { selected = it },
                onRetry = { scope.launch { runtime.refreshHistory() } },
                onLoadMore = { scope.launch { runtime.loadMoreHistory() } },
                onOpenLink = { url -> openUrl(url) },
                onDismissDetail = { selected = null },
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
            )
        }
    }
}

@Composable
internal fun AppUpdateHistoryContent(
    snapshot: AppUpdateHistorySnapshot,
    currentVersion: String,
    selected: AppUpdate?,
    onSelect: (AppUpdate) -> Unit,
    onRetry: () -> Unit,
    onLoadMore: () -> Unit,
    onOpenLink: (String) -> Unit,
    onDismissDetail: () -> Unit,
    modifier: Modifier = Modifier,
) {
    BoxWithConstraints(modifier = modifier.testTag("about-software-history-screen")) {
        val splitPane = shouldUseAppUpdateHistorySplitPane(maxWidth)
        val notesMaxHeight = if (maxHeight < 480.dp) 160.dp else 320.dp
        if (splitPane) {
            val listPaneWidth = (maxWidth * 0.4f).coerceIn(
                HistoryListPaneMinWidth,
                HistoryListPaneMaxWidth,
            )
            Row(modifier = Modifier.fillMaxSize()) {
                AppUpdateHistoryListPane(
                    snapshot = snapshot,
                    currentVersion = currentVersion,
                    selected = selected,
                    onSelect = onSelect,
                    onRetry = onRetry,
                    onLoadMore = onLoadMore,
                    modifier = Modifier
                        .width(listPaneWidth)
                        .fillMaxHeight(),
                )
                VerticalDivider(
                    modifier = Modifier.fillMaxHeight(),
                    color = MaterialTheme.colorScheme.outlineVariant,
                )
                AppUpdateHistoryDetailPane(
                    update = selected,
                    onOpenLink = onOpenLink,
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight(),
                )
            }
        } else {
            AppUpdateHistoryListPane(
                snapshot = snapshot,
                currentVersion = currentVersion,
                selected = selected,
                onSelect = onSelect,
                onRetry = onRetry,
                onLoadMore = onLoadMore,
                modifier = Modifier.fillMaxSize(),
            )
            val update = selected
            if (update != null) {
                AboutSoftwareUpdateDialog(
                    update = update,
                    openLink = httpOrHttpsUrlOrNull(update.link),
                    notesMaxHeight = notesMaxHeight,
                    onOpenLink = onOpenLink,
                    onDismiss = onDismissDetail,
                    title = update.version.ifBlank { update.title },
                )
            }
        }
    }
}

@Composable
private fun AppUpdateHistoryListPane(
    snapshot: AppUpdateHistorySnapshot,
    currentVersion: String,
    selected: AppUpdate?,
    onSelect: (AppUpdate) -> Unit,
    onRetry: () -> Unit,
    onLoadMore: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val listState = rememberLazyListState()
    val appendState = snapshot.toPageAppendState()
    LoadMoreOnNearEnd(
        state = listState,
        enabled = snapshot.hasMore && appendState is PageAppendState.Idle,
        onLoadMore = onLoadMore,
        prefetchDistance = 3,
    )
    PageStateLayout(
        state = snapshot.toPageViewState(),
        modifier = modifier.fillMaxSize(),
        refreshState = snapshot.toPageRefreshState(),
        onRefresh = onRetry,
        onRetryRefresh = onRetry,
        loading = {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center,
            ) {
                CircularProgressIndicator(
                    modifier = Modifier
                        .size(28.dp)
                        .testTag("about-software-history-loading"),
                    strokeWidth = 3.dp,
                )
            }
        },
        error = { error ->
            ErrorConnection(
                message = error.message ?: AppStrings.settings_about_software_history_failed,
                modifier = Modifier.testTag("about-software-history-error"),
                actionLabel = AppStrings.ui_reload,
                onAction = onRetry,
            )
        },
        empty = { message ->
            ErrorEmptyData(
                message = message ?: AppStrings.settings_about_software_history_empty,
                modifier = Modifier.testTag("about-software-history-empty"),
            )
        },
    ) {
        LazyColumn(
            state = listState,
            modifier = Modifier
                .fillMaxSize()
                .testTag("about-software-history-list"),
            contentPadding = PaddingValues(bottom = HistorySpacingMd),
        ) {
            items(
                items = snapshot.items,
                key = { item -> "${item.id}-${item.version}-${item.publishedAtEpochMillis}" },
            ) { update ->
                val isSelected = selected?.id == update.id &&
                    selected.version == update.version &&
                    selected.publishedAtEpochMillis == update.publishedAtEpochMillis
                AppUpdateHistoryRow(
                    update = update,
                    currentVersion = currentVersion,
                    selected = isSelected,
                    onClick = { onSelect(update) },
                )
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            }
            pageAppendFooter(
                state = appendState,
                onRetry = onLoadMore,
            )
        }
    }
}

@Composable
private fun AppUpdateHistoryRow(
    update: AppUpdate,
    currentVersion: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val headline = update.version.ifBlank { update.title }
    ListItem(
        headlineContent = { Text(headline) },
        supportingContent = {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                if (update.title.isNotBlank() && update.title != headline) {
                    Text(
                        text = update.title,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                if (update.publishedAtEpochMillis > 0L) {
                    Text(update.publishedAtEpochMillis.timestampToYMDHM())
                }
                if (update.version.isNotBlank() && update.version == currentVersion) {
                    Text(
                        text = AppStrings.settings_about_software_history_current,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            }
        },
        trailingContent = {
            Icon(Icons.Filled.ChevronRight, contentDescription = null)
        },
        colors = ListItemDefaults.colors(
            containerColor = if (selected) {
                MaterialTheme.colorScheme.secondaryContainer
            } else {
                MaterialTheme.colorScheme.surface
            },
        ),
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = HistoryTouchTarget)
            .clickable(onClick = onClick)
            .testTag("about-software-history-item-${update.id}")
            .semantics {
                role = Role.Button
                this.selected = selected
            },
    )
}

@Composable
private fun AppUpdateHistoryDetailPane(
    update: AppUpdate?,
    onOpenLink: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (update == null) {
        Box(
            modifier = modifier.padding(
                start = HistorySpacingLg,
                end = HistorySpacingLg,
                bottom = HistorySpacingLg,
            ),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = AppStrings.settings_about_software_history_select,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        return
    }
    val notes = aboutSoftwareChangelogLines(update.content)
    val openLink = httpOrHttpsUrlOrNull(update.link)
    Column(
        modifier = modifier
            .verticalScroll(rememberScrollState())
            .padding(
                start = HistorySpacingLg,
                end = HistorySpacingLg,
                bottom = HistorySpacingLg,
            ),
        verticalArrangement = Arrangement.spacedBy(HistorySpacingMd),
    ) {
        Text(
            text = update.version.ifBlank { update.title },
            style = MaterialTheme.typography.titleLarge,
            modifier = Modifier.semantics { heading() },
        )
        AboutSoftwareUpdateNotes(
            update = update,
            notes = notes,
            modifier = Modifier.fillMaxWidth(),
        )
        if (openLink != null) {
            Button(
                onClick = { onOpenLink(openLink) },
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = HistoryTouchTarget)
                    .testTag("about-software-history-open-link"),
            ) {
                Text(AppStrings.settings_about_software_open_link)
            }
        }
    }
}
