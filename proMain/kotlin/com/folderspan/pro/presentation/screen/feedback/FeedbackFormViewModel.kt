package com.folderspan.pro.presentation.screen.feedback

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.folderspan.pro.core.common.ApiResult
import com.folderspan.pro.core.datastore.AuthSession
import com.folderspan.pro.domain.model.FeedbackDraft
import com.folderspan.pro.domain.model.FeedbackType
import com.folderspan.pro.domain.model.FeedbackUpload
import com.folderspan.pro.domain.repository.FeedbackRepository
import com.folderspan.pro.domain.usecase.FeedbackSessionService
import com.folderspan.utils.ErrorLogFeedbackPayload
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import strings.AppStrings

class FeedbackFormViewModel(
    private val repository: FeedbackRepository,
    private val sessionService: FeedbackSessionService,
    private val onUnauthorized: () -> Unit,
    private val metadata: FeedbackClientMetadata = FeedbackClientMetadata(),
) : ViewModel() {
    private val _state = MutableStateFlow(FeedbackFormUiState())
    val state: StateFlow<FeedbackFormUiState> = _state
    private var session: AuthSession? = null
    private var pendingLogUpload: FeedbackUpload? = null

    init {
        ErrorLogFeedbackLaunch.consume()?.let(::applyLaunchPayload)
    }

    fun applyLaunchPayload(payload: ErrorLogFeedbackPayload) {
        val summary = payload.summary.trim().ifBlank { AppStrings.notification_error_log_body }
        pendingLogUpload = FeedbackUpload(
            fileName = "folderspan-error.log",
            contentType = "text/plain",
            bytes = payload.logBytes,
        )
        _state.update {
            it.copy(
                type = FeedbackType.Feedback,
                content = summary.take(MaxFeedbackContentLength),
                submittedReference = null,
                formErrorMessage = null,
                contentErrorMessage = null,
            )
        }
    }

    fun onSessionChanged(session: AuthSession?) {
        this.session = session
        _state.update { it.copy(isSignedIn = session != null) }
    }

    fun loadCategories(force: Boolean = false) {
        if (_state.value.isLoadingCategories) return
        if (!force && _state.value.categories.isNotEmpty()) return
        viewModelScope.launch {
            _state.update { it.copy(isLoadingCategories = true, categoryErrorMessage = null) }
            when (val result = repository.categories()) {
                is ApiResult.Success -> _state.update { current ->
                    val selected = current.selectedCategory
                        ?.takeIf { key -> result.data.any { it.key == key } }
                    current.copy(categories = result.data, selectedCategory = selected)
                }
                is ApiResult.Failure -> _state.update { it.copy(categoryErrorMessage = result.message) }
            }
            _state.update { it.copy(isLoadingCategories = false) }
        }
    }

    fun onTypeChange(value: FeedbackType) = _state.update {
        it.copy(type = value, submittedReference = null, formErrorMessage = null)
    }

    fun onCategoryChange(value: String?) = _state.update {
        it.copy(selectedCategory = value, submittedReference = null, formErrorMessage = null)
    }

    fun onContentChange(value: String) = _state.update {
        it.copy(
            content = value.take(MaxFeedbackContentLength),
            contentErrorMessage = null,
            submittedReference = null,
            formErrorMessage = null,
        )
    }

    fun onContactChange(value: String) = _state.update {
        it.copy(contact = value.take(MaxFeedbackContactLength), submittedReference = null, formErrorMessage = null)
    }

    fun resetSubmission() = _state.update {
        it.copy(submittedReference = null, formErrorMessage = null, contentErrorMessage = null)
    }

    fun submit() {
        val snapshot = _state.value
        if (snapshot.isSubmitting) return
        val contentError = feedbackContentValidationMessage(snapshot.content)
        if (contentError != null) {
            _state.update { it.copy(contentErrorMessage = contentError) }
            return
        }

        _state.update { it.copy(isSubmitting = true, formErrorMessage = null, submittedReference = null) }
        viewModelScope.launch {
            val draft = FeedbackDraft(
                type = snapshot.type,
                category = snapshot.selectedCategory,
                content = snapshot.content,
                contact = snapshot.contact,
                appVersion = metadata.appVersion,
                platform = metadata.platform,
            )
            when (val result = sessionService.submit(draft, session, onUnauthorized)) {
                is ApiResult.Success -> {
                    val upload = pendingLogUpload
                    pendingLogUpload = null
                    if (upload != null && upload.bytes.isNotEmpty() && session != null) {
                        sessionService.upload(result.data, upload, session, onUnauthorized)
                    }
                    _state.update {
                        it.copy(
                            content = "",
                            submittedReference = result.data,
                            contentErrorMessage = null,
                        )
                    }
                }
                is ApiResult.Failure -> _state.update { it.copy(formErrorMessage = result.message) }
            }
            _state.update { it.copy(isSubmitting = false) }
        }
    }
}

internal fun feedbackContentValidationMessage(content: String): String? = when {
    content.isBlank() -> AppStrings.ui_feedback_content_required
    content.length > MaxFeedbackContentLength -> AppStrings.ui_feedback_content_too_long
    else -> null
}

internal const val MaxFeedbackContentLength = 5_000
internal const val MaxFeedbackContactLength = 200
