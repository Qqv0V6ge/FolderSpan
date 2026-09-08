package com.folderspan.pro.presentation.screen.feedback

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.folderspan.pro.core.common.ApiResult
import com.folderspan.pro.core.datastore.AuthSession
import com.folderspan.pro.domain.model.FeedbackTransferProgress
import com.folderspan.pro.domain.model.FeedbackUpdate
import com.folderspan.pro.domain.model.FeedbackUpload
import com.folderspan.pro.domain.usecase.FeedbackSessionService
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

sealed interface FeedbackDetailEffect {
    data object Changed : FeedbackDetailEffect
    data object Deleted : FeedbackDetailEffect
}

class FeedbackTicketDetailViewModel(
    uuid: String,
    private val sessionService: FeedbackSessionService,
    private val onUnauthorized: () -> Unit,
    private val metadata: FeedbackClientMetadata = FeedbackClientMetadata(),
) : ViewModel() {
    private val _state = MutableStateFlow(FeedbackTicketDetailUiState(uuid = uuid))
    val state: StateFlow<FeedbackTicketDetailUiState> = _state
    private val _effect = MutableSharedFlow<FeedbackDetailEffect>(extraBufferCapacity = 1)
    val effect: SharedFlow<FeedbackDetailEffect> = _effect
    private var session: AuthSession? = null
    private var loadJob: Job? = null
    private var operationJob: Job? = null
    private var attachmentTransferJob: Job? = null
    private var retryableUpload: FeedbackUpload? = null

    fun onSessionChanged(session: AuthSession?) {
        this.session = session
    }

    fun load(force: Boolean = false, retainedMessage: String? = null) {
        if (_state.value.isLoading) return
        if (!force && _state.value.ticket != null) return
        loadJob?.cancel()
        loadJob = viewModelScope.launch {
            _state.update { it.copy(isLoading = true, errorMessage = null, refreshMessage = retainedMessage) }
            when (val result = sessionService.detail(_state.value.uuid, session, onUnauthorized)) {
                is ApiResult.Success -> {
                    _state.update {
                        it.copy(
                            ticket = result.data,
                            editContent = result.data.feedback.content,
                            errorMessage = null,
                        )
                    }
                    if (result.data.feedback.unreadCount > 0) markReadInBackground()
                }
                is ApiResult.Failure -> _state.update { state ->
                    if (state.ticket == null) state.copy(errorMessage = result.message)
                    else state.copy(refreshMessage = retainedMessage ?: result.message)
                }
            }
            _state.update { it.copy(isLoading = false) }
        }
    }

    fun onEditContentChange(value: String) = _state.update { it.copy(editContent = value.take(MaxFeedbackContentLength)) }
    fun onSupplementContentChange(value: String) = _state.update {
        it.copy(supplementContent = value.take(MaxFeedbackSupplementLength))
    }
    fun onWithdrawalReasonChange(value: String) = _state.update {
        it.copy(withdrawalReason = value.take(MaxFeedbackWithdrawalReasonLength))
    }

    fun update() = runOperation(
        operation = FeedbackDetailOperation.Update,
        block = { current ->
            val ticket = current.ticket?.feedback
            val error = feedbackContentValidationMessage(current.editContent)
            when {
                ticket == null -> ApiResult.Failure("")
                error != null -> ApiResult.Failure(error)
                else -> sessionService.update(
                    FeedbackUpdate(
                        uuid = current.uuid,
                        content = current.editContent,
                        appVersion = metadata.appVersion,
                        platform = metadata.platform,
                        category = ticket.category,
                    ),
                    session,
                    onUnauthorized,
                )
            }
        },
    )

    fun delete() = runOperation(
        operation = FeedbackDetailOperation.Delete,
        refreshAfterSuccess = false,
        block = { current -> sessionService.delete(current.uuid, session, onUnauthorized) },
        onSuccess = { _effect.tryEmit(FeedbackDetailEffect.Deleted) },
    )

    fun supplement() = runOperation(
        operation = FeedbackDetailOperation.Supplement,
        block = { current ->
            val content = current.supplementContent.trim()
            if (content.isEmpty()) ApiResult.Failure(strings.AppStrings.ui_feedback_supplement_required)
            else sessionService.supplement(current.uuid, content, session, onUnauthorized)
        },
        onSuccess = { _state.update { it.copy(supplementContent = "") } },
    )

    fun withdraw() = runOperation(
        operation = FeedbackDetailOperation.Withdraw,
        block = { current ->
            sessionService.withdraw(
                current.uuid,
                current.withdrawalReason.trim().takeIf(String::isNotEmpty),
                session,
                onUnauthorized,
            )
        },
    )

    fun upload(upload: FeedbackUpload) {
        if (!canStartAttachmentTransfer()) return
        retryableUpload = upload
        _state.update {
            it.copy(
                refreshMessage = null,
                attachmentTransfer = FeedbackAttachmentTransferState(
                    kind = FeedbackAttachmentTransferKind.Upload,
                    fileName = upload.fileName,
                    phase = FeedbackAttachmentTransferPhase.Preparing,
                    totalBytes = upload.bytes.size.toLong(),
                ),
            )
        }
        startAttachmentTransfer {
            updateTransferPhase(FeedbackAttachmentTransferPhase.Transferring)
            val publish = TransferProgressPublisher(::publishTransferProgress)
            when (
                val result = sessionService.upload(
                    _state.value.uuid,
                    upload,
                    session,
                    onUnauthorized,
                    publish::emit,
                )
            ) {
                is ApiResult.Success -> {
                    completeTransfer(upload.bytes.size.toLong())
                    retryableUpload = null
                    _effect.tryEmit(FeedbackDetailEffect.Changed)
                    load(force = true)
                }
                is ApiResult.Failure -> failTransfer(result.message)
            }
        }
    }

    fun retryUploadAttachment() {
        val transfer = _state.value.attachmentTransfer ?: return
        if (
            transfer.kind != FeedbackAttachmentTransferKind.Upload ||
            transfer.phase != FeedbackAttachmentTransferPhase.Failed
        ) {
            return
        }
        upload(retryableUpload ?: return)
    }

    fun deleteAttachment(attachmentUuid: String) = runOperation(
        operation = FeedbackDetailOperation.DeleteAttachment,
        block = { current ->
            sessionService.deleteAttachment(current.uuid, attachmentUuid, session, onUnauthorized)
        },
    )

    fun beginDownloadDestinationSelection(attachmentUuid: String, fallbackFileName: String?) {
        if (!canStartAttachmentTransfer()) return
        retryableUpload = null
        val safeName = sanitizeFeedbackAttachmentFileName(fallbackFileName) ?: "feedback-attachment.bin"
        _state.update {
            it.copy(
                refreshMessage = null,
                attachmentTransfer = FeedbackAttachmentTransferState(
                    kind = FeedbackAttachmentTransferKind.Download,
                    attachmentUuid = attachmentUuid,
                    fileName = safeName,
                    phase = FeedbackAttachmentTransferPhase.SelectingDestination,
                ),
            )
        }
    }

    fun cancelDownloadDestinationSelection() {
        val transfer = _state.value.attachmentTransfer ?: return
        if (transfer.phase != FeedbackAttachmentTransferPhase.SelectingDestination) return
        _state.update { it.copy(attachmentTransfer = null) }
    }

    fun confirmDownloadDestination(
        destination: FeedbackDownloadDestination,
        saver: FeedbackAttachmentTransferSaver,
    ) {
        val pending = _state.value.attachmentTransfer ?: return
        if (pending.phase != FeedbackAttachmentTransferPhase.SelectingDestination) return
        _state.update {
            it.copy(
                attachmentTransfer = pending.copy(
                    destination = destination,
                    phase = FeedbackAttachmentTransferPhase.Preparing,
                ),
            )
        }
        startAttachmentTransfer {
            updateTransferPhase(FeedbackAttachmentTransferPhase.Transferring)
            val downloadPublisher = TransferProgressPublisher(::publishTransferProgress)
            when (
                val result = sessionService.download(
                    uuid = _state.value.uuid,
                    attachmentUuid = pending.attachmentUuid.orEmpty(),
                    session = session,
                    onUnauthorized = onUnauthorized,
                    onProgress = downloadPublisher::emit,
                )
            ) {
                is ApiResult.Failure -> failTransfer(result.message)
                is ApiResult.Success -> {
                    val safeName = sanitizeFeedbackAttachmentFileName(result.data.fileName)
                        ?: sanitizeFeedbackAttachmentFileName(pending.fileName)
                        ?: "feedback-attachment.bin"
                    val download = result.data.copy(fileName = safeName)
                    updateTransferPhase(
                        phase = FeedbackAttachmentTransferPhase.Saving,
                        fileName = safeName,
                        transferredBytes = 0L,
                        totalBytes = download.bytes.size.toLong(),
                    )
                    val savePublisher = TransferProgressPublisher(::publishTransferProgress)
                    saver.save(destination, download, savePublisher::emit).fold(
                        onSuccess = { saved ->
                            if (saved) {
                                completeTransfer(download.bytes.size.toLong())
                                reportMessage(strings.AppStrings.ui_feedback_attachment_saved)
                            } else {
                                failTransfer(strings.AppStrings.ui_feedback_attachment_save_failed)
                            }
                        },
                        onFailure = { error ->
                            failTransfer(
                                error.message ?: strings.AppStrings.ui_feedback_attachment_save_failed,
                            )
                        },
                    )
                }
            }
        }
    }

    fun cancelAttachmentTransfer() {
        attachmentTransferJob?.cancel()
        attachmentTransferJob = null
        if (_state.value.attachmentTransfer?.kind == FeedbackAttachmentTransferKind.Upload) {
            retryableUpload = null
        }
        _state.update { state ->
            val transfer = state.attachmentTransfer
            if (transfer?.isActive == true) {
                state.copy(
                    attachmentTransfer = transfer.copy(
                        phase = FeedbackAttachmentTransferPhase.Cancelled,
                        cancelled = true,
                    ),
                )
            } else {
                state
            }
        }
    }

    fun cancelOperation() {
        cancelAttachmentTransfer()
        operationJob?.cancel()
        operationJob = null
        loadJob?.cancel()
        _state.update { it.copy(operation = null, isLoading = false) }
    }

    fun reportMessage(message: String?) {
        _state.update { it.copy(refreshMessage = message?.takeIf(String::isNotBlank)) }
    }

    private fun markReadInBackground() {
        if (_state.value.operation != null || _state.value.attachmentTransfer?.isActive == true) return
        viewModelScope.launch {
            _state.update { it.copy(operation = FeedbackDetailOperation.MarkRead) }
            val result = sessionService.markRead(_state.value.uuid, session, onUnauthorized)
            if (result is ApiResult.Success) {
                _state.update { state ->
                    state.copy(ticket = state.ticket?.copy(feedback = state.ticket.feedback.copy(unreadCount = 0)))
                }
                _effect.tryEmit(FeedbackDetailEffect.Changed)
            }
            _state.update { it.copy(operation = null) }
        }
    }

    private fun <T> runOperation(
        operation: FeedbackDetailOperation,
        refreshAfterSuccess: Boolean = true,
        refreshAfterFailure: Boolean = true,
        block: suspend (FeedbackTicketDetailUiState) -> ApiResult<T>,
        onSuccess: (T) -> Unit = {},
    ) {
        if (_state.value.operation != null || _state.value.attachmentTransfer?.isActive == true) return
        _state.update { it.copy(operation = operation, refreshMessage = null) }
        val job = viewModelScope.launch(start = CoroutineStart.LAZY) {
            try {
                when (val result = block(_state.value)) {
                    is ApiResult.Success -> {
                        onSuccess(result.data)
                        if (refreshAfterSuccess) {
                            _effect.tryEmit(FeedbackDetailEffect.Changed)
                            load(force = true)
                        }
                    }
                    is ApiResult.Failure -> _state.update {
                        it.copy(refreshMessage = result.message.takeIf(String::isNotBlank))
                    }.also {
                        if (refreshAfterFailure && _state.value.ticket != null) {
                            load(force = true, retainedMessage = result.message.takeIf(String::isNotBlank))
                        }
                    }
                }
            } finally {
                val currentJob = currentCoroutineContext()[Job]
                if (operationJob === currentJob) {
                    operationJob = null
                    _state.update { it.copy(operation = null) }
                }
            }
        }
        operationJob = job
        job.start()
    }

    private fun canStartAttachmentTransfer(): Boolean =
        _state.value.operation == null && _state.value.attachmentTransfer?.isActive != true

    private fun startAttachmentTransfer(block: suspend () -> Unit) {
        val job = viewModelScope.launch(start = CoroutineStart.LAZY) {
            try {
                block()
            } catch (error: CancellationException) {
                _state.update { state ->
                    val transfer = state.attachmentTransfer
                    if (transfer?.isActive == true) {
                        state.copy(
                            attachmentTransfer = transfer.copy(
                                phase = FeedbackAttachmentTransferPhase.Cancelled,
                                cancelled = true,
                            ),
                        )
                    } else {
                        state
                    }
                }
            } catch (error: Throwable) {
                failTransfer(error.message ?: strings.AppStrings.ui_operation_failed)
            } finally {
                val currentJob = currentCoroutineContext()[Job]
                if (attachmentTransferJob === currentJob) attachmentTransferJob = null
            }
        }
        attachmentTransferJob = job
        job.start()
    }

    private fun updateTransferPhase(
        phase: FeedbackAttachmentTransferPhase,
        fileName: String? = null,
        transferredBytes: Long? = null,
        totalBytes: Long? = null,
    ) {
        _state.update { state ->
            val transfer = state.attachmentTransfer ?: return@update state
            state.copy(
                attachmentTransfer = transfer.copy(
                    phase = phase,
                    fileName = fileName ?: transfer.fileName,
                    transferredBytes = transferredBytes ?: transfer.transferredBytes,
                    totalBytes = totalBytes ?: transfer.totalBytes,
                ),
            )
        }
    }

    private fun publishTransferProgress(progress: FeedbackTransferProgress) {
        _state.update { state ->
            val transfer = state.attachmentTransfer ?: return@update state
            val transferred = progress.transferredBytes.coerceAtLeast(transfer.transferredBytes)
            val total = progress.totalBytes ?: transfer.totalBytes
            state.copy(
                attachmentTransfer = transfer.copy(
                    transferredBytes = total?.let { transferred.coerceAtMost(it) } ?: transferred,
                    totalBytes = total,
                ),
            )
        }
    }

    private fun completeTransfer(totalBytes: Long) {
        _state.update { state ->
            val transfer = state.attachmentTransfer ?: return@update state
            state.copy(
                attachmentTransfer = transfer.copy(
                    phase = FeedbackAttachmentTransferPhase.Completed,
                    transferredBytes = totalBytes,
                    totalBytes = totalBytes,
                    completed = true,
                ),
            )
        }
    }

    private fun failTransfer(message: String) {
        _state.update { state ->
            val transfer = state.attachmentTransfer ?: return@update state
            state.copy(
                refreshMessage = message.takeIf(String::isNotBlank),
                attachmentTransfer = transfer.copy(
                    phase = FeedbackAttachmentTransferPhase.Failed,
                    failureMessage = message,
                ),
            )
        }
    }
}

private class TransferProgressPublisher(
    private val publish: (FeedbackTransferProgress) -> Unit,
) {
    private var lastPublishedBytes = -1L

    suspend fun emit(progress: FeedbackTransferProgress) {
        val transferred = progress.transferredBytes.coerceAtLeast(0L)
        if (transferred < lastPublishedBytes) return
        val terminal = progress.totalBytes?.let { transferred >= it } == true
        if (
            lastPublishedBytes < 0L ||
            terminal ||
            transferred - lastPublishedBytes >= 256L * 1024L
        ) {
            lastPublishedBytes = transferred
            publish(progress.copy(transferredBytes = transferred))
        }
    }
}

internal const val MaxFeedbackSupplementLength = 4_000
internal const val MaxFeedbackWithdrawalReasonLength = 1_000
