package com.folderspan.pro.presentation.screen.feedback

import strings.AppStrings

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.folderspan.pro.core.ui.components.AuthStatusTone
import com.folderspan.pro.core.ui.components.ProSnackbarEffect
import com.folderspan.pro.core.ui.components.ProSnackbarHost
import com.folderspan.pro.core.ui.components.proSnackbarPrompt
import com.folderspan.pro.core.ui.components.showProSnackbar
import com.folderspan.pro.domain.model.FeedbackAttachment
import com.folderspan.pro.domain.model.FeedbackEvent
import com.folderspan.ui.components.pagestate.PageErrorState
import com.folderspan.ui.components.pagestate.PageErrorType
import com.folderspan.ui.components.pagestate.PageStateLayout
import com.folderspan.ui.components.pagestate.resolvePageViewState
import kotlinx.coroutines.launch

@Composable
internal fun FeedbackTicketDetailPane(
    state: FeedbackTicketDetailUiState,
    onRefresh: () -> Unit,
    onEditContentChange: (String) -> Unit,
    onUpdate: () -> Unit,
    onDelete: () -> Unit,
    onSupplementContentChange: (String) -> Unit,
    onSupplement: () -> Unit,
    onWithdrawalReasonChange: (String) -> Unit,
    onWithdraw: () -> Unit,
    onUploadAttachment: () -> Unit,
    onDownloadAttachment: (String, String) -> Unit,
    onDeleteAttachment: (String) -> Unit = {},
    onCancelOperation: () -> Unit,
    onRetryUploadAttachment: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    var confirmDelete by remember { mutableStateOf(false) }
    var confirmWithdraw by remember { mutableStateOf(false) }
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val busy = state.operation != null || state.attachmentTransfer?.isActive == true
    val snackbarTone = if (state.refreshMessage == AppStrings.ui_feedback_attachment_saved) {
        AuthStatusTone.Success
    } else {
        AuthStatusTone.Error
    }
    ProSnackbarEffect(
        hostState = snackbarHostState,
        prompt = proSnackbarPrompt(state.refreshMessage, tone = snackbarTone),
    )

    Box(
        modifier = modifier.testTag(FeedbackTicketDetailPaneTestTag),
        contentAlignment = Alignment.TopCenter,
    ) {
        PageStateLayout(
            state = resolvePageViewState(
                isLoading = state.isLoading && state.ticket == null,
                errorState = state.errorMessage?.takeIf { state.ticket == null }?.let { message ->
                    PageErrorState(PageErrorType.General, message)
                },
                isEmpty = state.ticket == null,
                emptyMessage = AppStrings.ui_feedback_ticket_detail,
            ),
            modifier = Modifier.fillMaxSize(),
            onRetry = onRefresh,
        ) {
            FeedbackTicketDetailContent(
                state = state,
                busy = busy,
                onEditContentChange = onEditContentChange,
                onUpdate = onUpdate,
                onRequestDelete = { confirmDelete = true },
                onSupplementContentChange = onSupplementContentChange,
                onSupplement = onSupplement,
                onRequestWithdraw = { confirmWithdraw = true },
                onUploadAttachment = onUploadAttachment,
                onDownloadAttachment = onDownloadAttachment,
                onRequestDeleteAttachment = { attachment ->
                    scope.launch {
                        val result = snackbarHostState.showProSnackbar(
                            message = AppStrings.ui_feedback_confirm_delete_attachment_message_arg0.format(
                                arg0 = attachment.originalName.ifBlank { AppStrings.ui_feedback_attachments },
                            ),
                            tone = AuthStatusTone.Error,
                            actionLabel = AppStrings.ui_delete,
                            withDismissAction = true,
                        )
                        if (result == SnackbarResult.ActionPerformed) {
                            onDeleteAttachment(attachment.uuid)
                        }
                    }
                },
                onCancelOperation = onCancelOperation,
                onRetryUploadAttachment = onRetryUploadAttachment,
                modifier = Modifier.fillMaxSize(),
            )
        }
        ProSnackbarHost(
            hostState = snackbarHostState,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .testTag(FeedbackTicketDetailSnackbarTestTag),
        )
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { if (!busy) confirmDelete = false },
            title = { Text(AppStrings.ui_feedback_confirm_delete_title) },
            text = { Text(AppStrings.ui_feedback_confirm_delete_message) },
            confirmButton = {
                TextButton(onClick = { confirmDelete = false; onDelete() }, enabled = !busy) {
                    Text(AppStrings.ui_feedback_delete, color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text(AppStrings.ui_cancel) } },
        )
    }

    if (confirmWithdraw) {
        AlertDialog(
            onDismissRequest = { if (!busy) confirmWithdraw = false },
            title = { Text(AppStrings.ui_feedback_confirm_withdraw_title) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(AppStrings.ui_feedback_confirm_withdraw_message)
                    OutlinedTextField(
                        value = state.withdrawalReason,
                        onValueChange = onWithdrawalReasonChange,
                        label = { Text(AppStrings.ui_feedback_withdrawal_reason_optional) },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = { confirmWithdraw = false; onWithdraw() }, enabled = !busy) {
                    Text(AppStrings.ui_feedback_withdraw)
                }
            },
            dismissButton = { TextButton(onClick = { confirmWithdraw = false }) { Text(AppStrings.ui_cancel) } },
        )
    }

}

@Composable
private fun FeedbackTicketDetailContent(
    state: FeedbackTicketDetailUiState,
    busy: Boolean,
    onEditContentChange: (String) -> Unit,
    onUpdate: () -> Unit,
    onRequestDelete: () -> Unit,
    onSupplementContentChange: (String) -> Unit,
    onSupplement: () -> Unit,
    onRequestWithdraw: () -> Unit,
    onUploadAttachment: () -> Unit,
    onDownloadAttachment: (String, String) -> Unit,
    onRequestDeleteAttachment: (FeedbackAttachment) -> Unit,
    onCancelOperation: () -> Unit,
    onRetryUploadAttachment: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val ticket = state.ticket ?: return
    BoxWithConstraints(modifier, contentAlignment = Alignment.TopCenter) {
        val expanded = maxWidth >= 1_000.dp
        val contentSpacing = if (expanded) 28.dp else 16.dp
        val listState = rememberLazyListState()
        val scope = rememberCoroutineScope()
        val primaryTransferKey = if (expanded) "workspace" else "attachments"
        val primaryTransferVisible by remember(listState, primaryTransferKey) {
            derivedStateOf {
                listState.layoutInfo.visibleItemsInfo.any { item -> item.key == primaryTransferKey }
            }
        }
        LazyColumn(
            modifier = Modifier.fillMaxSize().testTag(FeedbackTicketDetailContentTestTag),
            state = listState,
            contentPadding = PaddingValues(
                start = contentSpacing,
                end = contentSpacing,
                bottom = contentSpacing,
            ),
            verticalArrangement = Arrangement.spacedBy(if (expanded) 22.dp else 16.dp),
        ) {
            if (state.operation != null) {
                item(key = "busy") {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        LinearProgressIndicator(Modifier.weight(1f))
                        if (state.operation != FeedbackDetailOperation.MarkRead) {
                            TextButton(onClick = onCancelOperation) { Text(AppStrings.ui_cancel) }
                        }
                    }
                }
            }
            item(key = "summary", contentType = "detail-hero") {
                FeedbackDetailSummary(state = state, expanded = expanded)
            }

            if (expanded) {
                item(key = "workspace", contentType = "detail-workspace") {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(22.dp),
                        verticalAlignment = Alignment.Top,
                    ) {
                        Column(
                            modifier = Modifier.weight(1.12f),
                            verticalArrangement = Arrangement.spacedBy(22.dp),
                        ) {
                            FeedbackTimeline(ticket.events)
                            FeedbackAttachments(
                                attachments = ticket.attachments,
                                busy = busy,
                                transfer = state.attachmentTransfer,
                                onUpload = onUploadAttachment,
                                onDownload = onDownloadAttachment,
                                onDelete = onRequestDeleteAttachment,
                                onCancelTransfer = onCancelOperation,
                                onRetryUpload = onRetryUploadAttachment,
                            )
                        }
                        Column(
                            modifier = Modifier.weight(0.88f),
                            verticalArrangement = Arrangement.spacedBy(22.dp),
                        ) {
                            FeedbackEditSection(
                                value = state.editContent,
                                busy = busy,
                                onValueChange = onEditContentChange,
                                onUpdate = onUpdate,
                                onDelete = onRequestDelete,
                            )
                            if (ticket.canSupplement) {
                                FeedbackSupplementSection(
                                    value = state.supplementContent,
                                    busy = busy,
                                    onValueChange = onSupplementContentChange,
                                    onSubmit = onSupplement,
                                )
                            }
                            if (ticket.canWithdraw) {
                                FeedbackWithdrawAction(
                                    busy = busy,
                                    onWithdraw = onRequestWithdraw,
                                )
                            }
                        }
                    }
                }
            } else {
                item(key = "timeline", contentType = "detail-section") {
                    FeedbackTimeline(ticket.events)
                }
                item(key = "edit", contentType = "detail-section") {
                    FeedbackEditSection(
                        value = state.editContent,
                        busy = busy,
                        onValueChange = onEditContentChange,
                        onUpdate = onUpdate,
                        onDelete = onRequestDelete,
                    )
                }
                item(key = "attachments", contentType = "detail-section") {
                    FeedbackAttachments(
                        attachments = ticket.attachments,
                        busy = busy,
                        transfer = state.attachmentTransfer,
                        onUpload = onUploadAttachment,
                        onDownload = onDownloadAttachment,
                        onDelete = onRequestDeleteAttachment,
                        onCancelTransfer = onCancelOperation,
                        onRetryUpload = onRetryUploadAttachment,
                    )
                }
                if (ticket.canSupplement) {
                    item(key = "supplement", contentType = "detail-section") {
                        FeedbackSupplementSection(
                            value = state.supplementContent,
                            busy = busy,
                            onValueChange = onSupplementContentChange,
                            onSubmit = onSupplement,
                        )
                    }
                }
                if (ticket.canWithdraw) {
                    item(key = "withdraw", contentType = "detail-action") {
                        FeedbackWithdrawAction(
                            busy = busy,
                            onWithdraw = onRequestWithdraw,
                        )
                    }
                }
            }
        }
        val transfer = state.attachmentTransfer
        if (transfer?.isActive == true && !primaryTransferVisible) {
            FeedbackTransferBottomStrip(
                transfer = transfer,
                onClick = {
                    val leadingItems = if (state.operation != null) 1 else 0
                    val targetIndex = leadingItems + if (expanded) 1 else 3
                    scope.launch { listState.animateScrollToItem(targetIndex) }
                },
                onCancel = onCancelOperation,
                modifier = Modifier.align(Alignment.BottomCenter),
            )
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun FeedbackDetailSummary(
    state: FeedbackTicketDetailUiState,
    expanded: Boolean,
    modifier: Modifier = Modifier,
) {
    val ticket = state.ticket ?: return
    val feedback = ticket.feedback
    val latestEvent = remember(ticket.events) { ticket.events.maxByOrNull(FeedbackEvent::createdAt) }
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(if (expanded) 20.dp else 16.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = AppStrings.ui_feedback_type_category_arg0_arg1.format(
                    arg0 = feedbackTypeLabel(feedback.type),
                    arg1 = feedbackCategoryLabel(feedback.category),
                ),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.weight(1f),
            )
            if (feedback.unreadCount > 0) {
                Badge(
                    modifier = Modifier.semantics {
                        contentDescription = AppStrings.ui_feedback_unread_arg0.format(
                            arg0 = feedback.unreadCount.toString(),
                        )
                    },
                ) {
                    Text(feedback.unreadCount.coerceAtMost(99).toString())
                }
            }
        }

        if (expanded) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(22.dp),
                verticalAlignment = Alignment.Top,
            ) {
                Text(
                    text = feedback.content,
                    style = MaterialTheme.typography.headlineMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.weight(1.35f).semantics { heading() },
                )
                FeedbackCurrentStatus(
                    status = feedback.status,
                    priority = feedback.priority,
                    modifier = Modifier.weight(0.65f),
                )
            }
        } else {
            Text(
                text = feedback.content,
                style = MaterialTheme.typography.headlineSmall,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.semantics { heading() },
            )
            FeedbackCurrentStatus(
                status = feedback.status,
                priority = feedback.priority,
                modifier = Modifier.fillMaxWidth(),
            )
        }

        if (feedback.statusNote.isNotBlank()) {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text(
                    text = AppStrings.ui_feedback_status_note,
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.secondary,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(feedback.statusNote, style = MaterialTheme.typography.bodyLarge)
            }
        }

        latestEvent?.let { event ->
            FeedbackLatestProgress(event)
        }

        HorizontalDivider()
        Text(
            text = AppStrings.ui_feedback_ticket_information,
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.semantics { heading() },
        )
        FlowRow(
            maxItemsInEachRow = if (expanded) 4 else 2,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            FeedbackDetailMetric(
                AppStrings.ui_feedback_platform,
                safeFeedbackServerLabel(feedback.platform),
                Modifier.weight(1f).widthIn(min = 140.dp),
            )
            FeedbackDetailMetric(
                AppStrings.ui_feedback_app_version,
                feedback.appVersion.ifBlank { AppStrings.ui_unknown },
                Modifier.weight(1f).widthIn(min = 140.dp),
            )
            FeedbackDetailMetric(
                AppStrings.ui_feedback_created,
                formatFeedbackDateTime(feedback.createdAt),
                Modifier.weight(1f).widthIn(min = 140.dp),
            )
            FeedbackDetailMetric(
                AppStrings.ui_feedback_updated,
                formatFeedbackDateTime(feedback.updatedAt),
                Modifier.weight(1f).widthIn(min = 140.dp),
            )
            feedback.dueAt?.takeIf(String::isNotBlank)?.let { dueAt ->
                FeedbackDetailMetric(
                    AppStrings.ui_feedback_due,
                    formatFeedbackDateTime(dueAt),
                    Modifier.weight(1f).widthIn(min = 140.dp),
                )
            }
            feedback.contact.takeIf(String::isNotBlank)?.let { contact ->
                FeedbackDetailMetric(
                    AppStrings.ui_feedback_contact,
                    contact,
                    Modifier.weight(1f).widthIn(min = 140.dp),
                )
            }
        }
    }
}

@Composable
private fun FeedbackCurrentStatus(
    status: String,
    priority: String,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(
            AppStrings.ui_feedback_current_status,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = feedbackStatusLabel(status),
            style = MaterialTheme.typography.headlineSmall,
            color = MaterialTheme.colorScheme.primary,
            fontWeight = FontWeight.SemiBold,
        )
        val priorityColors = feedbackPriorityTagColors(priority)
        FeedbackTicketTag(
            label = AppStrings.ui_feedback_priority_arg0.format(
                arg0 = feedbackPriorityLabel(priority),
            ),
            containerColor = priorityColors.container,
            contentColor = priorityColors.content,
        )
    }
}

@Composable
private fun FeedbackLatestProgress(
    event: FeedbackEvent,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        Text(
            text = AppStrings.ui_feedback_latest_progress,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.tertiary,
            fontWeight = FontWeight.SemiBold,
        )
        Text(
            text = feedbackEventLabel(event.eventType),
            style = MaterialTheme.typography.titleMedium,
        )
        feedbackEventStatusTransition(event)?.let { transition ->
            Text(transition, style = MaterialTheme.typography.labelLarge)
        }
        if (event.message.isNotBlank()) {
            Text(event.message, style = MaterialTheme.typography.bodyLarge)
        }
        Text(
            text = feedbackEventMetadata(event),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun FeedbackDetailMetric(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.heightIn(min = 64.dp).padding(vertical = 6.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyLarge,
            fontWeight = FontWeight.Medium,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun FeedbackEditSection(
    value: String,
    busy: Boolean,
    onValueChange: (String) -> Unit,
    onUpdate: () -> Unit,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier,
) {
    FeedbackSection(
        title = AppStrings.ui_feedback_edit,
        modifier = modifier,
    ) {
        OutlinedTextField(
            value = value,
            onValueChange = onValueChange,
            label = { Text(AppStrings.ui_feedback_content) },
            modifier = Modifier.fillMaxWidth(),
            minLines = 4,
            enabled = !busy,
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.End,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextButton(onClick = onDelete, enabled = !busy) {
                Text(AppStrings.ui_feedback_delete, color = MaterialTheme.colorScheme.error)
            }
            Spacer(Modifier.width(8.dp))
            Button(onClick = onUpdate, enabled = !busy) {
                Text(AppStrings.ui_feedback_update)
            }
        }
    }
}

@Composable
private fun FeedbackSupplementSection(
    value: String,
    busy: Boolean,
    onValueChange: (String) -> Unit,
    onSubmit: () -> Unit,
    modifier: Modifier = Modifier,
) {
    FeedbackSection(
        title = AppStrings.ui_feedback_add_supplement,
        modifier = modifier,
    ) {
        OutlinedTextField(
            value = value,
            onValueChange = onValueChange,
            placeholder = { Text(AppStrings.ui_feedback_supplement_hint) },
            modifier = Modifier.fillMaxWidth(),
            minLines = 3,
            enabled = !busy,
        )
        Button(
            onClick = onSubmit,
            enabled = !busy,
            modifier = Modifier.align(Alignment.End),
        ) {
            Text(AppStrings.ui_feedback_add_supplement)
        }
    }
}

@Composable
private fun FeedbackWithdrawAction(
    busy: Boolean,
    onWithdraw: () -> Unit,
    modifier: Modifier = Modifier,
) {
    OutlinedButton(
        onClick = onWithdraw,
        enabled = !busy,
        modifier = modifier.fillMaxWidth(),
    ) {
        Text(AppStrings.ui_feedback_withdraw)
    }
}

@Composable
private fun FeedbackTimeline(
    events: List<FeedbackEvent>,
    modifier: Modifier = Modifier,
) {
    val orderedEvents = remember(events) { events.sortedByDescending(FeedbackEvent::createdAt) }
    FeedbackSection(
        title = AppStrings.ui_feedback_timeline,
        modifier = modifier,
    ) {
        if (orderedEvents.isEmpty()) {
            Text(
                text = AppStrings.ui_feedback_no_progress,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            Column(modifier = Modifier.fillMaxWidth()) {
                orderedEvents.forEachIndexed { index, event ->
                    FeedbackTimelineEvent(
                        event = event,
                        isLatest = index == 0,
                        isLast = index == orderedEvents.lastIndex,
                    )
                }
            }
        }
    }
}

@Composable
private fun FeedbackTimelineEvent(
    event: FeedbackEvent,
    isLatest: Boolean,
    isLast: Boolean,
    modifier: Modifier = Modifier,
) {
    val nodeColor = if (isLatest) {
        MaterialTheme.colorScheme.primary
    } else {
        MaterialTheme.colorScheme.outline
    }
    Row(
        modifier = modifier.fillMaxWidth().height(IntrinsicSize.Min),
        verticalAlignment = Alignment.Top,
    ) {
        Box(
            modifier = Modifier.width(28.dp).fillMaxHeight(),
            contentAlignment = Alignment.TopCenter,
        ) {
            if (!isLast) {
                Surface(
                    modifier = Modifier.padding(top = 11.dp).width(2.dp).fillMaxHeight(),
                    color = MaterialTheme.colorScheme.outlineVariant,
                ) {}
            }
            Surface(
                modifier = Modifier.size(if (isLatest) 13.dp else 10.dp),
                color = nodeColor,
                shape = MaterialTheme.shapes.extraLarge,
                tonalElevation = if (isLatest) 3.dp else 0.dp,
            ) {}
        }

        Column(
            modifier = Modifier.weight(1f).padding(bottom = if (isLast) 0.dp else 10.dp),
            verticalArrangement = Arrangement.spacedBy(5.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = feedbackEventLabel(event.eventType),
                    style = MaterialTheme.typography.labelLarge,
                    color = if (isLatest) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.onSurface
                    },
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f),
                )
                if (isLatest) {
                    Surface(
                        color = MaterialTheme.colorScheme.primary,
                        contentColor = MaterialTheme.colorScheme.onPrimary,
                        shape = MaterialTheme.shapes.extraLarge,
                    ) {
                        Text(
                            text = AppStrings.ui_feedback_latest_progress,
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.padding(horizontal = 9.dp, vertical = 4.dp),
                        )
                    }
                }
            }
            feedbackEventStatusTransition(event)?.let { transition ->
                Text(
                    text = transition,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
            }
            if (event.message.isNotBlank()) {
                Text(
                    text = event.message,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text(
                text = feedbackEventMetadata(event),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun FeedbackAttachments(
    attachments: List<FeedbackAttachment>,
    busy: Boolean,
    transfer: FeedbackAttachmentTransferState?,
    onUpload: () -> Unit,
    onDownload: (String, String) -> Unit,
    onDelete: (FeedbackAttachment) -> Unit,
    onCancelTransfer: () -> Unit,
    onRetryUpload: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val visibleTransfer = transfer?.takeUnless {
        it.phase == FeedbackAttachmentTransferPhase.Completed
    }
    FeedbackSection(
        title = AppStrings.ui_feedback_attachments,
        modifier = modifier,
        trailing = {
            FilledTonalButton(onClick = onUpload, enabled = !busy) {
                Icon(Icons.Filled.AttachFile, contentDescription = null)
                Spacer(Modifier.width(6.dp))
                Text(AppStrings.ui_feedback_upload_attachment)
            }
        },
    ) {
        Text(
            text = AppStrings.ui_feedback_attachment_supported_types_arg0.format(
                arg0 = supportedFeedbackAttachmentExtensionsLabel(),
            ),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (visibleTransfer?.kind == FeedbackAttachmentTransferKind.Upload) {
            FeedbackAttachmentTransferProgress(
                transfer = visibleTransfer,
                onCancel = onCancelTransfer,
                onRetry = onRetryUpload,
                modifier = Modifier.testTag(FeedbackUploadTransferProgressTestTag),
            )
        }
        if (attachments.isEmpty()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag(FeedbackAttachmentEmptyStateTestTag)
                    .padding(vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Icon(
                    imageVector = Icons.Filled.AttachFile,
                    contentDescription = AppStrings.ui_feedback_attachments,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(20.dp),
                )
                Text(
                    text = AppStrings.ui_feedback_no_attachments,
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        attachments.forEachIndexed { index, attachment ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("$FeedbackAttachmentRowTestTagPrefix${attachment.uuid}")
                    .padding(vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(3.dp),
                ) {
                    Text(
                        text = attachment.originalName.ifBlank { AppStrings.ui_feedback_attachments },
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Medium,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = listOf(
                            attachment.contentType,
                            formatFeedbackByteSize(attachment.size),
                            formatFeedbackDateTime(attachment.createdAt),
                        ).filter(String::isNotBlank).joinToString(" · "),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (
                        visibleTransfer?.kind == FeedbackAttachmentTransferKind.Download &&
                        visibleTransfer.attachmentUuid == attachment.uuid
                    ) {
                        FeedbackAttachmentTransferProgress(
                            transfer = visibleTransfer,
                            onCancel = onCancelTransfer,
                            compact = true,
                            modifier = Modifier.testTag(
                                "$FeedbackDownloadTransferProgressTestTagPrefix${attachment.uuid}",
                            ),
                        )
                    }
                }
                IconButton(
                    onClick = { onDownload(attachment.uuid, attachment.originalName) },
                    enabled = !busy,
                ) {
                    Icon(
                        Icons.Filled.Download,
                        contentDescription = AppStrings.ui_feedback_download_attachment,
                    )
                }
                IconButton(
                    onClick = { onDelete(attachment) },
                    enabled = !busy,
                ) {
                    Icon(
                        Icons.Filled.Delete,
                        contentDescription = AppStrings.ui_feedback_delete_attachment,
                        tint = MaterialTheme.colorScheme.error,
                    )
                }
            }
            if (index < attachments.lastIndex) HorizontalDivider()
        }
    }
}

@Composable
private fun FeedbackAttachmentTransferProgress(
    transfer: FeedbackAttachmentTransferState,
    onCancel: () -> Unit,
    onRetry: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
    compact: Boolean = false,
) {
    val phaseLabel = feedbackAttachmentTransferPhaseLabel(transfer)
    Column(
        modifier = modifier
            .fillMaxWidth()
            .semantics {
                liveRegion = LiveRegionMode.Polite
                contentDescription = "$phaseLabel · ${transfer.fileName}"
            },
        verticalArrangement = Arrangement.spacedBy(if (compact) 4.dp else 7.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    text = transfer.fileName,
                    style = if (compact) MaterialTheme.typography.bodySmall else MaterialTheme.typography.titleSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = buildString {
                        append(phaseLabel)
                        append(" · ")
                        append(formatFeedbackTransferBytes(transfer))
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (transfer.isActive && transfer.phase != FeedbackAttachmentTransferPhase.SelectingDestination) {
                TextButton(onClick = onCancel) { Text(AppStrings.ui_cancel) }
            }
            if (transfer.phase == FeedbackAttachmentTransferPhase.Failed && onRetry != null) {
                IconButton(onClick = onRetry) {
                    Icon(
                        imageVector = Icons.Filled.Refresh,
                        contentDescription = AppStrings.ui_try_again,
                    )
                }
            }
        }
        val total = transfer.totalBytes
        if (transfer.isActive) {
            if (total != null && total > 0L) {
                LinearProgressIndicator(
                    progress = { (transfer.transferredBytes.toFloat() / total).coerceIn(0f, 1f) },
                    modifier = Modifier.fillMaxWidth(),
                )
            } else {
                LinearProgressIndicator(Modifier.fillMaxWidth())
            }
        }
    }
}

@Composable
private fun FeedbackTransferBottomStrip(
    transfer: FeedbackAttachmentTransferState,
    onClick: () -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        onClick = onClick,
        modifier = modifier
            .fillMaxWidth()
            .testTag(FeedbackTransferBottomStripTestTag),
        shape = androidx.compose.ui.graphics.RectangleShape,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        tonalElevation = 2.dp,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    transfer.fileName,
                    style = MaterialTheme.typography.labelLarge,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    "${feedbackAttachmentTransferPhaseLabel(transfer)} · ${formatFeedbackTransferBytes(transfer)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            TextButton(onClick = onCancel) { Text(AppStrings.ui_cancel) }
        }
    }
}

private fun feedbackAttachmentTransferPhaseLabel(transfer: FeedbackAttachmentTransferState): String =
    when (transfer.phase) {
        FeedbackAttachmentTransferPhase.SelectingDestination -> AppStrings.ui_feedback_transfer_selecting_destination
        FeedbackAttachmentTransferPhase.Preparing -> AppStrings.ui_feedback_transfer_preparing
        FeedbackAttachmentTransferPhase.Transferring ->
            if (transfer.kind == FeedbackAttachmentTransferKind.Upload) {
                AppStrings.ui_feedback_transfer_uploading
            } else {
                AppStrings.ui_feedback_transfer_downloading
            }
        FeedbackAttachmentTransferPhase.Saving -> AppStrings.ui_feedback_transfer_saving
        FeedbackAttachmentTransferPhase.Completed -> AppStrings.ui_feedback_transfer_completed
        FeedbackAttachmentTransferPhase.Cancelled -> AppStrings.ui_feedback_transfer_cancelled
        FeedbackAttachmentTransferPhase.Failed -> AppStrings.ui_feedback_transfer_failed
    }

private fun formatFeedbackTransferBytes(transfer: FeedbackAttachmentTransferState): String {
    val transferred = formatFeedbackByteSize(transfer.transferredBytes)
    return transfer.totalBytes?.let { total ->
        val percentage = if (total > 0L) {
            (transfer.transferredBytes * 100L / total).coerceIn(0L, 100L)
        } else {
            100L
        }
        "$transferred / ${formatFeedbackByteSize(total)} · $percentage%"
    } ?: transferred
}

@Composable
private fun FeedbackSection(
    title: String,
    modifier: Modifier = Modifier,
    trailing: @Composable RowScope.() -> Unit = {},
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.weight(1f).semantics { heading() },
            )
            trailing()
        }
        HorizontalDivider()
        content()
    }
}

private fun feedbackEventStatusTransition(event: FeedbackEvent): String? =
    if (
        event.fromStatus.isNotBlank() &&
        event.toStatus.isNotBlank() &&
        event.fromStatus != event.toStatus
    ) {
        "${feedbackStatusLabel(event.fromStatus)} → ${feedbackStatusLabel(event.toStatus)}"
    } else {
        null
    }

private fun feedbackEventMetadata(event: FeedbackEvent): String =
    listOf(event.actorName, formatFeedbackDateTime(event.createdAt))
        .filter(String::isNotBlank)
        .joinToString(" · ")

internal fun formatFeedbackByteSize(size: Long): String = when {
    size < 1_024 -> "$size B"
    size < 1_048_576 -> "${size / 1_024} KiB"
    else -> "${size / 1_048_576} MiB"
}

internal const val FeedbackTicketDetailPaneTestTag = "feedback-ticket-detail-pane"
internal const val FeedbackTicketDetailContentTestTag = "feedback-ticket-detail-content"
internal const val FeedbackTicketDetailSnackbarTestTag = "feedback-ticket-detail-snackbar"
internal const val FeedbackAttachmentEmptyStateTestTag = "feedback-attachment-empty-state"
internal const val FeedbackUploadTransferProgressTestTag = "feedback-upload-transfer-progress"
internal const val FeedbackAttachmentRowTestTagPrefix = "feedback-attachment-row-"
internal const val FeedbackDownloadTransferProgressTestTagPrefix = "feedback-download-transfer-progress-"
internal const val FeedbackTransferBottomStripTestTag = "feedback-transfer-bottom-strip"
