package com.folderspan.pro.presentation.screen.feedback

import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.lifecycle.viewmodel.compose.viewModel
import com.folderspan.pro.core.datastore.SessionManager
import com.folderspan.pro.domain.repository.FeedbackRepository
import com.folderspan.pro.domain.usecase.FeedbackSessionService
import strings.AppStrings

@Composable
fun FeedbackHomeRoute(
    viewModelKey: String? = null,
    repository: FeedbackRepository,
    sessionService: FeedbackSessionService,
    onNavigateBack: () -> Unit,
    onOpenTickets: () -> Unit,
    onUnauthorized: () -> Unit,
) {
    val viewModel = viewModel(key = viewModelKey) {
        FeedbackFormViewModel(repository, sessionService, onUnauthorized)
    }
    val state by viewModel.state.collectAsState()
    val session by SessionManager.session.collectAsState()

    LaunchedEffect(session?.accessToken) { viewModel.onSessionChanged(session) }
    LaunchedEffect(viewModel) { viewModel.loadCategories() }

    FeedbackHomePage(
        state = state,
        onNavigateBack = onNavigateBack,
        onOpenTickets = onOpenTickets,
        onTypeChange = viewModel::onTypeChange,
        onCategoryChange = viewModel::onCategoryChange,
        onContentChange = viewModel::onContentChange,
        onContactChange = viewModel::onContactChange,
        onRetryCategories = { viewModel.loadCategories(force = true) },
        onSubmit = viewModel::submit,
        onSubmitAnother = viewModel::resetSubmission,
    )
}

@Composable
fun FeedbackTicketListRoute(
    initialTicketUuid: String? = null,
    viewModelKey: String? = null,
    sessionService: FeedbackSessionService,
    onNavigateBack: () -> Unit,
    onSignIn: () -> Unit,
    onUnauthorized: () -> Unit,
) {
    val viewModel = viewModel(key = viewModelKey) { FeedbackTicketListViewModel(sessionService, onUnauthorized) }
    val state by viewModel.state.collectAsState()
    val session by SessionManager.session.collectAsState()

    LaunchedEffect(session?.accessToken) {
        viewModel.onSessionChanged(session)
        if (session != null) viewModel.load(refresh = true)
    }

    FeedbackTicketListPage(
        state = state,
        initialTicketUuid = initialTicketUuid,
        onNavigateBack = onNavigateBack,
        onSignIn = onSignIn,
        onRefresh = { viewModel.load(refresh = true) },
        onLoadMore = { viewModel.load(refresh = false) },
        onUpdateFilters = { type, platform, start, end ->
            viewModel.updateFilters(type, platform, start, end)
        },
        onSubmitFeedback = onNavigateBack,
        ticketDetail = { uuid, modifier, onDeleted ->
            FeedbackTicketDetailPaneRoute(
                uuid = uuid,
                viewModelKey = embeddedFeedbackDetailViewModelKey(viewModelKey, uuid),
                sessionService = sessionService,
                onDeleted = {
                    onDeleted()
                    viewModel.load(refresh = true)
                },
                onTicketChanged = { viewModel.load(refresh = true) },
                onUnauthorized = onUnauthorized,
                modifier = modifier,
            )
        },
    )
}

@Composable
private fun FeedbackTicketDetailPaneRoute(
    uuid: String,
    viewModelKey: String,
    sessionService: FeedbackSessionService,
    onDeleted: () -> Unit,
    onTicketChanged: () -> Unit,
    onUnauthorized: () -> Unit,
    modifier: Modifier,
) {
    val viewModel = viewModel(key = viewModelKey) {
        FeedbackTicketDetailViewModel(uuid, sessionService, onUnauthorized)
    }
    val state by viewModel.state.collectAsState()
    val session by SessionManager.session.collectAsState()
    val attachmentPicker = LocalFeedbackAttachmentPicker.current
    val destinationPicker = LocalFeedbackDownloadDestinationPicker.current
    val attachmentTransferSaver = LocalFeedbackAttachmentTransferSaver.current
    val currentOnDeleted by rememberUpdatedState(onDeleted)
    val currentOnTicketChanged by rememberUpdatedState(onTicketChanged)

    LaunchedEffect(viewModel, session?.accessToken) {
        viewModel.onSessionChanged(session)
        if (session != null) viewModel.load()
    }
    LaunchedEffect(viewModel) {
        viewModel.effect.collect { effect ->
            when (effect) {
                FeedbackDetailEffect.Changed -> currentOnTicketChanged()
                FeedbackDetailEffect.Deleted -> currentOnDeleted()
            }
        }
    }

    val onUploadAttachment = {
        val picker = attachmentPicker
        if (picker == null) {
            viewModel.reportMessage(AppStrings.ui_feedback_attachment_type_unsupported)
        } else {
            picker.select { result ->
                result
                    .mapCatching { upload -> upload?.let { validateFeedbackUpload(it).getOrThrow() } }
                    .fold(
                        onSuccess = { upload -> if (upload != null) viewModel.upload(upload) },
                        onFailure = { viewModel.reportMessage(it.message) },
                    )
            }
        }
    }
    val onDownloadAttachment = { attachmentUuid: String, originalName: String ->
        viewModel.beginDownloadDestinationSelection(attachmentUuid, originalName)
        val picker = destinationPicker
        val saver = attachmentTransferSaver
        if (picker == null || saver == null) {
            viewModel.cancelDownloadDestinationSelection()
            viewModel.reportMessage(AppStrings.ui_feedback_attachment_save_failed)
        } else {
            picker.select(originalName) { result ->
                result.fold(
                    onSuccess = { destination ->
                        if (destination == null) viewModel.cancelDownloadDestinationSelection()
                        else viewModel.confirmDownloadDestination(destination, saver)
                    },
                    onFailure = { error ->
                        viewModel.cancelDownloadDestinationSelection()
                        viewModel.reportMessage(error.message)
                    },
                )
            }
        }
    }

    FeedbackTicketDetailPane(
        state = state,
        onRefresh = { viewModel.load(force = true) },
        onEditContentChange = viewModel::onEditContentChange,
        onUpdate = viewModel::update,
        onDelete = viewModel::delete,
        onSupplementContentChange = viewModel::onSupplementContentChange,
        onSupplement = viewModel::supplement,
        onWithdrawalReasonChange = viewModel::onWithdrawalReasonChange,
        onWithdraw = viewModel::withdraw,
        onUploadAttachment = onUploadAttachment,
        onDownloadAttachment = onDownloadAttachment,
        onDeleteAttachment = viewModel::deleteAttachment,
        onCancelOperation = viewModel::cancelOperation,
        onRetryUploadAttachment = viewModel::retryUploadAttachment,
        modifier = modifier,
    )
}

private fun embeddedFeedbackDetailViewModelKey(parentKey: String?, uuid: String): String =
    "${parentKey ?: "feedback-ticket-list"}:detail:$uuid"
