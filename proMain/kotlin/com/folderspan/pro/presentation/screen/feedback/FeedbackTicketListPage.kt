package com.folderspan.pro.presentation.screen.feedback

import strings.AppStrings

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Devices
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.folderspan.pro.domain.model.FeedbackSummary
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
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Instant

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FeedbackTicketListPage(
    state: FeedbackTicketListUiState,
    initialTicketUuid: String? = null,
    onNavigateBack: () -> Unit,
    onSignIn: () -> Unit,
    onRefresh: () -> Unit,
    onLoadMore: () -> Unit,
    onUpdateFilters: (String?, String?, Long?, Long?) -> Unit,
    onSubmitFeedback: () -> Unit,
    ticketDetail: @Composable (
        uuid: String,
        modifier: Modifier,
        onDeleted: () -> Unit,
    ) -> Unit,
) {
    val filterSheet = LocalFeedbackTicketFilterSheet.current
    val filterSelection = FeedbackTicketFilterSelection(
        type = state.typeFilter,
        platform = state.platformFilter,
        startTimeEpochSeconds = state.startTimeEpochSeconds,
        endTimeEpochSeconds = state.endTimeEpochSeconds,
    )
    var showFilterSheet by remember { mutableStateOf(false) }

    if (showFilterSheet && filterSheet != null) {
        filterSheet.Content(
            selection = filterSelection,
            onApply = { selection ->
                onUpdateFilters(
                    selection.type,
                    selection.platform,
                    selection.startTimeEpochSeconds,
                    selection.endTimeEpochSeconds,
                )
            },
            onDismiss = { showFilterSheet = false },
        )
    }

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val expanded = maxWidth >= 900.dp
        val useFixedMasterPane = maxWidth >= 1_200.dp
        var selectedUuid by remember(initialTicketUuid) {
            mutableStateOf(initialTicketUuid)
        }
        var preserveInitialSelection by remember(initialTicketUuid) {
            mutableStateOf(initialTicketUuid != null)
        }
        LaunchedEffect(state.items, state.isLoading, expanded, initialTicketUuid) {
            val currentUuid = selectedUuid
            when {
                currentUuid == null && expanded && !state.isLoading -> {
                    selectedUuid = state.items.firstOrNull()?.uuid
                }
                currentUuid != null &&
                    !(preserveInitialSelection && currentUuid == initialTicketUuid) &&
                    !state.isLoading &&
                    state.items.none { it.uuid == currentUuid } -> {
                    selectedUuid = state.items.firstOrNull()?.uuid.takeIf { expanded }
                }
            }
        }
        val showingCompactDetail = !expanded && selectedUuid != null

        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text("${AppStrings.ui_feedback_tickets}(${state.total})") },
                    navigationIcon = {
                        IconButton(
                            onClick = {
                                if (showingCompactDetail) {
                                    preserveInitialSelection = false
                                    selectedUuid = null
                                } else {
                                    onNavigateBack()
                                }
                            },
                        ) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = AppStrings.ui_return)
                        }
                    },
                    actions = {
                        if (state.isSignedIn && !showingCompactDetail) {
                            BadgedBox(
                                badge = {
                                    if (filterSelection.activeCount > 0) {
                                        Badge { Text(filterSelection.activeCount.toString()) }
                                    }
                                },
                            ) {
                                IconButton(
                                    onClick = { showFilterSheet = true },
                                    enabled = filterSheet != null,
                                ) {
                                    Icon(
                                        Icons.Filled.Tune,
                                        contentDescription = AppStrings.ui_feedback_filter_tickets,
                                    )
                                }
                            }
                            IconButton(onClick = onRefresh, enabled = !state.isLoading) {
                                Icon(Icons.Filled.Refresh, contentDescription = AppStrings.ui_reload)
                            }
                        }
                    },
                )
            },
        ) { padding ->
            if (!state.isSignedIn) {
                Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                    Column(
                        modifier = Modifier.fillMaxWidth().padding(24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(16.dp),
                    ) {
                        Text(AppStrings.ui_feedback_my_tickets, style = MaterialTheme.typography.headlineSmall)
                        Text(AppStrings.ui_feedback_sign_in_to_view, style = MaterialTheme.typography.bodyLarge)
                        Button(onClick = onSignIn) { Text(AppStrings.ui_feedback_sign_in) }
                    }
                }
                return@Scaffold
            }

            Box(Modifier.fillMaxSize().padding(padding)) {
            val listPane: @Composable (Modifier) -> Unit = { modifier ->
                val listState = rememberLazyListState()
                val pageViewState = state.toPageViewState()
                val appendState = state.toPageAppendState()
                LoadMoreOnNearEnd(
                    state = listState,
                    enabled = pageViewState is PageViewState.Content && appendState is PageAppendState.Idle,
                    onLoadMore = onLoadMore,
                )
                PageStateLayout(
                    state = pageViewState,
                    modifier = modifier,
                    refreshState = state.toPageRefreshState(),
                    onRefresh = onRefresh,
                    onRetry = onRefresh,
                    onRetryRefresh = onRefresh,
                    empty = {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(12.dp),
                            modifier = Modifier.padding(24.dp),
                        ) {
                            ErrorEmptyData(message = AppStrings.ui_feedback_no_tickets)
                            FilledTonalButton(onClick = onSubmitFeedback) {
                                Text(AppStrings.ui_feedback_submit)
                            }
                        }
                    },
                ) {
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        state = listState,
                        contentPadding = PaddingValues(bottom = 8.dp),
                    ) {
                        items(
                            items = state.items,
                            key = { it.uuid },
                            contentType = { "feedback-ticket" },
                        ) { ticket ->
                            FeedbackTicketRow(
                                ticket = ticket,
                                selected = ticket.uuid == selectedUuid,
                                onClick = {
                                    preserveInitialSelection = false
                                    selectedUuid = ticket.uuid
                                },
                            )
                            HorizontalDivider(modifier = Modifier.padding(start = 16.dp))
                        }
                        pageAppendFooter(
                            state = appendState,
                            onRetry = onLoadMore,
                        )
                    }
                }
            }

                if (expanded) {
                    Row(Modifier.fillMaxSize()) {
                        if (useFixedMasterPane) {
                            listPane(Modifier.width(520.dp).fillMaxHeight())
                        } else {
                            listPane(Modifier.weight(0.44f).fillMaxHeight())
                        }
                        VerticalDivider()
                        val detailUuid = selectedUuid ?: state.items.firstOrNull()?.uuid
                        if (detailUuid == null) {
                            Box(
                                modifier = Modifier.weight(1f).fillMaxHeight(),
                                contentAlignment = Alignment.Center,
                            ) {
                                Text(AppStrings.ui_feedback_no_tickets)
                            }
                        } else {
                            ticketDetail(
                                detailUuid,
                                Modifier.weight(1f).fillMaxHeight(),
                            ) {
                                preserveInitialSelection = false
                                selectedUuid = state.items.firstOrNull { it.uuid != detailUuid }?.uuid
                            }
                        }
                    }
                } else if (showingCompactDetail) {
                    ticketDetail(
                        selectedUuid.orEmpty(),
                        Modifier.fillMaxSize(),
                    ) {
                        preserveInitialSelection = false
                        selectedUuid = null
                    }
                } else {
                    listPane(Modifier.fillMaxSize())
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun FeedbackTicketRow(
    ticket: FeedbackSummary,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val containerColor = when {
        selected -> MaterialTheme.colorScheme.secondaryContainer
        ticket.unreadCount > 0 -> MaterialTheme.colorScheme.surfaceContainerHigh
        else -> MaterialTheme.colorScheme.surface
    }
    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(containerColor)
            .clickable(onClick = onClick),
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = AppStrings.ui_feedback_type_category_arg0_arg1.format(
                        arg0 = feedbackTypeLabel(ticket.type),
                        arg1 = feedbackCategoryLabel(ticket.category),
                    ),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.weight(1f),
                )
                if (ticket.unreadCount > 0) {
                    Badge(
                        modifier = Modifier.semantics {
                            contentDescription = AppStrings.ui_feedback_unread_arg0.format(
                                arg0 = ticket.unreadCount.toString(),
                            )
                        },
                    ) {
                        Text(ticket.unreadCount.coerceAtMost(99).toString())
                    }
                    Spacer(Modifier.width(8.dp))
                }
                Icon(Icons.Filled.ChevronRight, contentDescription = null)
            }

            Text(
                text = ticket.content.ifBlank { AppStrings.ui_feedback_ticket_detail },
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )

            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                val statusColors = feedbackStatusTagColors(ticket.status)
                FeedbackTicketTag(
                    label = feedbackStatusLabel(ticket.status),
                    containerColor = statusColors.container,
                    contentColor = statusColors.content,
                )
                val priorityColors = feedbackPriorityTagColors(ticket.priority)
                FeedbackTicketTag(
                    label = AppStrings.ui_feedback_priority_arg0.format(
                        arg0 = feedbackPriorityLabel(ticket.priority),
                    ),
                    containerColor = priorityColors.container,
                    contentColor = priorityColors.content,
                )
            }

            if (ticket.statusNote.isNotBlank()) {
                Surface(
                    color = MaterialTheme.colorScheme.surfaceContainerHighest,
                    shape = MaterialTheme.shapes.small,
                ) {
                    Text(
                        text = AppStrings.ui_feedback_status_note_arg0.format(arg0 = ticket.statusNote),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 8.dp),
                    )
                }
            }

            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Filled.Devices,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(16.dp),
                )
                Spacer(Modifier.width(6.dp))
                Text(
                    text = safeFeedbackServerLabel(ticket.platform),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.weight(1f))
                Text(
                    text = AppStrings.ui_feedback_updated_arg0.format(
                        arg0 = formatFeedbackDateTime(ticket.updatedAt),
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
internal fun FeedbackTicketTag(
    label: String,
    containerColor: Color,
    contentColor: Color,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier,
        color = containerColor,
        contentColor = contentColor,
        shape = CircleShape,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
        )
    }
}

internal data class FeedbackTicketTagColors(
    val container: Color,
    val content: Color,
)

@Composable
internal fun feedbackStatusTagColors(status: String): FeedbackTicketTagColors = when (status.lowercase()) {
    "waiting_user", "rejected" -> FeedbackTicketTagColors(
        MaterialTheme.colorScheme.errorContainer,
        MaterialTheme.colorScheme.onErrorContainer,
    )
    "resolved", "closed" -> FeedbackTicketTagColors(
        MaterialTheme.colorScheme.primaryContainer,
        MaterialTheme.colorScheme.onPrimaryContainer,
    )
    "in_progress", "processing" -> FeedbackTicketTagColors(
        MaterialTheme.colorScheme.tertiaryContainer,
        MaterialTheme.colorScheme.onTertiaryContainer,
    )
    else -> FeedbackTicketTagColors(
        MaterialTheme.colorScheme.secondaryContainer,
        MaterialTheme.colorScheme.onSecondaryContainer,
    )
}

@Composable
internal fun feedbackPriorityTagColors(priority: String): FeedbackTicketTagColors = when (priority.lowercase()) {
    "urgent", "high" -> FeedbackTicketTagColors(
        MaterialTheme.colorScheme.errorContainer,
        MaterialTheme.colorScheme.onErrorContainer,
    )
    else -> FeedbackTicketTagColors(
        MaterialTheme.colorScheme.surfaceContainerHighest,
        MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

internal fun FeedbackTicketListUiState.toPageViewState(): PageViewState = resolvePageViewState(
    isLoading = isLoading && items.isEmpty(),
    errorState = errorMessage?.takeIf { items.isEmpty() }?.let { message ->
        PageErrorState(PageErrorType.General, message)
    },
    isEmpty = items.isEmpty(),
    emptyMessage = AppStrings.ui_feedback_no_tickets,
)

internal fun FeedbackTicketListUiState.toPageRefreshState(): PageRefreshState = when {
    isLoading && items.isNotEmpty() -> PageRefreshState.Refreshing
    refreshMessage != null && items.isNotEmpty() -> PageRefreshState.Error(refreshMessage)
    else -> PageRefreshState.Idle
}

internal fun FeedbackTicketListUiState.toPageAppendState(): PageAppendState = when {
    items.isEmpty() -> PageAppendState.Idle
    isLoadingMore -> PageAppendState.Loading
    errorMessage != null -> PageAppendState.Error(errorMessage)
    !hasMore -> PageAppendState.End
    else -> PageAppendState.Idle
}

internal fun safeFeedbackServerLabel(value: String): String = value
    .trim()
    .take(80)
    .replace('_', ' ')
    .ifBlank { AppStrings.ui_unknown }

internal fun formatFeedbackDateTime(
    value: String,
    timeZone: TimeZone = TimeZone.currentSystemDefault(),
): String {
    val source = value.trim()
    if (source.isEmpty()) return AppStrings.ui_unknown

    return runCatching {
        val localDateTime = Instant.parse(source).toLocalDateTime(timeZone)
        buildString {
            append(localDateTime.year)
            append('-')
            append((localDateTime.month.ordinal + 1).toString().padStart(2, '0'))
            append('-')
            append(localDateTime.day.toString().padStart(2, '0'))
            append(' ')
            append(localDateTime.hour.toString().padStart(2, '0'))
            append(':')
            append(localDateTime.minute.toString().padStart(2, '0'))
            append(':')
            append(localDateTime.second.toString().padStart(2, '0'))
        }
    }.getOrElse {
        safeFeedbackServerLabel(source)
    }
}

internal fun feedbackStatusLabel(value: String): String = when (value.lowercase()) {
    "submitted", "pending", "open" -> AppStrings.ui_feedback_status_submitted
    "triaged" -> AppStrings.ui_feedback_status_triaged
    "in_progress", "processing" -> AppStrings.ui_feedback_status_in_progress
    "waiting_user" -> AppStrings.ui_feedback_status_waiting_user
    "resolved" -> AppStrings.ui_feedback_status_resolved
    "closed" -> AppStrings.ui_feedback_status_closed
    "withdrawn" -> AppStrings.ui_feedback_status_withdrawn
    "rejected" -> AppStrings.ui_feedback_status_rejected
    else -> safeFeedbackServerLabel(value)
}

internal fun feedbackPriorityLabel(value: String): String = when (value.lowercase()) {
    "urgent" -> AppStrings.ui_feedback_priority_urgent
    "high" -> AppStrings.ui_feedback_priority_high
    "normal", "medium" -> AppStrings.ui_feedback_priority_normal
    "low" -> AppStrings.ui_feedback_priority_low
    else -> safeFeedbackServerLabel(value)
}

internal fun feedbackCategoryLabel(value: String): String = when (value.lowercase()) {
    "bug" -> AppStrings.ui_feedback_category_bug
    "feature" -> AppStrings.ui_feedback_category_feature
    "ui" -> AppStrings.ui_feedback_category_ui
    "performance" -> AppStrings.ui_feedback_category_performance
    "compatibility" -> AppStrings.ui_feedback_category_compatibility
    "content" -> AppStrings.ui_feedback_category_content
    "other" -> AppStrings.ui_feedback_category_other
    else -> safeFeedbackServerLabel(value)
}

internal fun feedbackTypeLabel(value: String): String = when (value.lowercase()) {
    "feedback" -> AppStrings.ui_feedback
    "suggestion" -> AppStrings.ui_suggestion
    else -> safeFeedbackServerLabel(value)
}

internal fun feedbackEventLabel(value: String): String = when (value.lowercase()) {
    "created", "submitted" -> AppStrings.ui_feedback_event_created
    "status_changed", "status_change" -> AppStrings.ui_feedback_event_status_changed
    "replied", "reply", "commented" -> AppStrings.ui_feedback_event_replied
    "supplemented", "supplement" -> AppStrings.ui_feedback_event_supplemented
    "updated" -> AppStrings.ui_feedback_event_updated
    "withdrawn", "withdraw" -> AppStrings.ui_feedback_event_withdrawn
    "attachment_uploaded", "uploaded_attachment" -> AppStrings.ui_feedback_event_attachment_uploaded
    else -> safeFeedbackServerLabel(value)
}
