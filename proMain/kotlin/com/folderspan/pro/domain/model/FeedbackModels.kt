package com.folderspan.pro.domain.model

const val MAX_FEEDBACK_ATTACHMENT_BYTES: Long = 10L * 1024L * 1024L
val SUPPORTED_FEEDBACK_ATTACHMENT_EXTENSIONS = listOf("jpg", "jpeg", "png", "webp", "pdf", "txt", "log")

data class FeedbackTransferProgress(
    val transferredBytes: Long,
    val totalBytes: Long? = null,
)

typealias FeedbackTransferProgressCallback = suspend (FeedbackTransferProgress) -> Unit

data class FeedbackCategory(
    val key: String,
    val label: String,
)

enum class FeedbackType(val wireValue: String) {
    Feedback("feedback"),
    Suggestion("suggestion"),
}

data class FeedbackDraft(
    val type: FeedbackType = FeedbackType.Feedback,
    val category: String? = null,
    val content: String,
    val contact: String? = null,
    val appVersion: String,
    val platform: String,
)

data class FeedbackUpdate(
    val uuid: String,
    val type: FeedbackType? = null,
    val category: String? = null,
    val content: String,
    val contact: String? = null,
    val appVersion: String,
    val platform: String,
)

data class FeedbackListQuery(
    val type: String? = null,
    val platform: String? = null,
    val startTimeEpochSeconds: Long? = null,
    val endTimeEpochSeconds: Long? = null,
    val page: Int = 1,
    val pageSize: Int = 20,
)

data class FeedbackSummary(
    val id: Long,
    val uuid: String,
    val type: String,
    val category: String,
    val content: String,
    val contact: String,
    val status: String,
    val statusNote: String,
    val appVersion: String,
    val platform: String,
    val createdAt: String,
    val updatedAt: String,
    val priority: String,
    val dueAt: String?,
    val unreadCount: Int,
    val version: Int,
)

data class FeedbackPage(
    val items: List<FeedbackSummary>,
    val total: Int,
)

data class FeedbackEvent(
    val uuid: String,
    val actorType: String,
    val actorName: String,
    val eventType: String,
    val fromStatus: String,
    val toStatus: String,
    val message: String,
    val createdAt: String,
)

data class FeedbackAttachment(
    val uuid: String,
    val originalName: String,
    val contentType: String,
    val size: Long,
    val createdAt: String,
)

data class FeedbackCase(
    val feedback: FeedbackSummary,
    val events: List<FeedbackEvent>,
    val attachments: List<FeedbackAttachment>,
    val canSupplement: Boolean,
    val canWithdraw: Boolean,
    val caseType: String,
)

data class FeedbackOperationResult(
    val success: Boolean,
    val status: String,
    val version: Int,
)

data class FeedbackUpload(
    val fileName: String,
    val contentType: String,
    val bytes: ByteArray,
) {
    override fun equals(other: Any?): Boolean =
        other is FeedbackUpload &&
            fileName == other.fileName &&
            contentType == other.contentType &&
            bytes.contentEquals(other.bytes)

    override fun hashCode(): Int = 31 * (31 * fileName.hashCode() + contentType.hashCode()) + bytes.contentHashCode()
}

data class FeedbackDownload(
    val fileName: String?,
    val contentType: String?,
    val bytes: ByteArray,
) {
    override fun equals(other: Any?): Boolean =
        other is FeedbackDownload &&
            fileName == other.fileName &&
            contentType == other.contentType &&
            bytes.contentEquals(other.bytes)

    override fun hashCode(): Int = 31 * (31 * fileName.hashCode() + contentType.hashCode()) + bytes.contentHashCode()
}
