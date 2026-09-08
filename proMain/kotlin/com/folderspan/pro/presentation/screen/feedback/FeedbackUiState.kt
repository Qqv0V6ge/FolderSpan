package com.folderspan.pro.presentation.screen.feedback

import com.folderspan.pro.domain.model.FeedbackCase
import com.folderspan.pro.domain.model.FeedbackCategory
import com.folderspan.pro.domain.model.FeedbackSummary
import com.folderspan.pro.domain.model.FeedbackType

data class FeedbackClientMetadata(
    val appVersion: String = "1.0.0",
    val platform: String = runtimeFeedbackPlatform(),
)

internal expect fun runtimeFeedbackPlatform(): String

data class FeedbackFormUiState(
    val type: FeedbackType = FeedbackType.Feedback,
    val categories: List<FeedbackCategory> = emptyList(),
    val selectedCategory: String? = null,
    val content: String = "",
    val contact: String = "",
    val isSignedIn: Boolean = false,
    val isLoadingCategories: Boolean = false,
    val isSubmitting: Boolean = false,
    val categoryErrorMessage: String? = null,
    val formErrorMessage: String? = null,
    val contentErrorMessage: String? = null,
    val submittedReference: String? = null,
)

data class FeedbackTicketListUiState(
    val isSignedIn: Boolean = false,
    val items: List<FeedbackSummary> = emptyList(),
    val total: Int = 0,
    val typeFilter: String? = null,
    val platformFilter: String? = null,
    val startTimeEpochSeconds: Long? = null,
    val endTimeEpochSeconds: Long? = null,
    val page: Int = 0,
    val hasMore: Boolean = false,
    val isLoading: Boolean = false,
    val isLoadingMore: Boolean = false,
    val errorMessage: String? = null,
    val refreshMessage: String? = null,
)

enum class FeedbackDetailOperation {
    MarkRead,
    Update,
    Delete,
    Supplement,
    Withdraw,
    Upload,
    DeleteAttachment,
    Download,
}

enum class FeedbackAttachmentTransferKind {
    Upload,
    Download,
}

enum class FeedbackAttachmentTransferPhase {
    SelectingDestination,
    Preparing,
    Transferring,
    Saving,
    Completed,
    Cancelled,
    Failed,
}

data class FeedbackAttachmentTransferState(
    val kind: FeedbackAttachmentTransferKind,
    val attachmentUuid: String? = null,
    val fileName: String,
    val phase: FeedbackAttachmentTransferPhase,
    val transferredBytes: Long = 0L,
    val totalBytes: Long? = null,
    val destination: FeedbackDownloadDestination? = null,
    val cancelled: Boolean = false,
    val completed: Boolean = false,
    val failureMessage: String? = null,
) {
    val isActive: Boolean
        get() = phase in setOf(
            FeedbackAttachmentTransferPhase.SelectingDestination,
            FeedbackAttachmentTransferPhase.Preparing,
            FeedbackAttachmentTransferPhase.Transferring,
            FeedbackAttachmentTransferPhase.Saving,
        )
}

data class FeedbackTicketDetailUiState(
    val uuid: String,
    val ticket: FeedbackCase? = null,
    val isLoading: Boolean = false,
    val operation: FeedbackDetailOperation? = null,
    val errorMessage: String? = null,
    val refreshMessage: String? = null,
    val editContent: String = "",
    val supplementContent: String = "",
    val withdrawalReason: String = "",
    val attachmentTransfer: FeedbackAttachmentTransferState? = null,
)
