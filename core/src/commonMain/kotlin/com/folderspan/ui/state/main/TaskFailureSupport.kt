package com.folderspan.ui.state.main

import strings.AppStrings

import com.folderspan.exception.AuthorityException
import com.folderspan.exception.TimeoutException
import io.ktor.client.plugins.*
import kotlinx.coroutines.CancellationException

internal open class TaskEndpointUnavailableException(
    message: String,
) : IllegalStateException(message)

internal class DeviceEndpointUnavailableException(
    message: String,
) : TaskEndpointUnavailableException(message)

internal class ShareSessionUnavailableException(
    message: String = AppStrings.message_task_share_session_expired,
) : TaskEndpointUnavailableException(message)

internal fun Throwable?.isTaskLevelTransferFailure(): Boolean {
    var current = this
    while (current != null) {
        if (current is TaskEndpointUnavailableException) return true
        current = current.cause
    }
    return false
}

internal fun buildBatchTaskFailureMessage(
    operation: String,
    failureCount: Int,
    firstFailedPath: String?,
    firstError: String?,
): String {
    val normalizedCount = failureCount.coerceAtLeast(1)
    val normalizedPath = firstFailedPath?.trim().orEmpty()
    val normalizedError = firstError?.toExplicitPermissionFailureMessage()?.trim().orEmpty()
    return buildString {
        append(AppStrings.ui_batch)
        append(operation)
        append(AppStrings.ui_partial_failure)
        append(normalizedCount)
        append(AppStrings.ui_entries_failed)
        if (normalizedPath.isNotEmpty()) {
            append(AppStrings.ui_first_failed_item)
            append(normalizedPath)
        }
        if (normalizedError.isNotEmpty()) {
            append(AppStrings.task_error_suffix)
            append(normalizedError)
        }
    }
}

internal fun Throwable?.toTaskFailureMessage(fallback: String): String {
    val defaultMessage = fallback.ifBlank { AppStrings.message_task_operation_failed }
    return when (this) {
        is CancellationException -> message?.takeIf { it.isNotBlank() } ?: AppStrings.message_task_cancelled
        is DeviceEndpointUnavailableException ->
            message?.takeIf { it.isNotBlank() } ?: AppStrings.message_task_device_offline
        is ShareSessionUnavailableException ->
            message?.takeIf { it.isNotBlank() } ?: AppStrings.message_task_share_session_expired
        is TaskEndpointUnavailableException -> message?.takeIf { it.isNotBlank() } ?: defaultMessage
        is HttpRequestTimeoutException -> AppStrings.message_task_request_timeout
        is TimeoutException -> message.takeIf { it.isNotBlank() } ?: AppStrings.message_task_request_timeout
        is AuthorityException -> message
            ?.takeIf { it.isNotBlank() }
            ?.toExplicitPermissionFailureMessage()
            ?: AppStrings.message_task_permission_denied
        else -> (this?.message?.takeIf { it.isNotBlank() } ?: defaultMessage)
            .toExplicitPermissionFailureMessage()
    }
}

internal fun String?.toExplicitPermissionFailureMessage(): String {
    val normalized = this?.trim().orEmpty()
    if (normalized.isBlank()) return normalized
    if (!normalized.isPermissionFailureMessage()) return normalized
    if (
        normalized.startsWith(AppStrings.legacy_permission_insufficient_prefix) ||
        normalized.startsWith("Permission denied:", ignoreCase = true)
    ) {
        return normalized
    }

    val reason = normalized.trimEnd('。', '.', '；', ';')
    return AppStrings.message_task_permission_denied_with_detail.format(detail = reason)
}

private fun String.isPermissionFailureMessage(): Boolean {
    return contains(AppStrings.message_task_permission_denied) ||
        contains(AppStrings.ui_insufficient_permissions) ||
        contains(AppStrings.message_task_permission_denied) ||
        contains(AppStrings.ui_insufficient_permissions) ||
        contains(AppStrings.legacy_permission_unauthorized_marker) ||
        contains(AppStrings.ui_unauthorized) ||
        contains("AccessDeniedException") ||
        contains("Permission denied", ignoreCase = true) ||
        contains("Operation not permitted", ignoreCase = true) ||
        contains("Read-only file system", ignoreCase = true)
}

internal fun TaskState.resolveTaskFailureMessage(
    task: Task,
    preferredPath: String? = null,
    error: Throwable? = null,
    fallback: String,
): String {
    val meaningful = preferredPath?.let { path -> getMeaningfulResult(task, path) } ?: getMeaningfulResult(task)
    return error.toTaskFailureMessage(meaningful ?: fallback)
}

fun shouldHideTaskResultPath(message: String): Boolean {
    val normalized = message.trim()
    return normalized.isNotEmpty() && (
        normalized == AppStrings.message_task_device_offline ||
            normalized == AppStrings.message_task_cancelled ||
            normalized.startsWith(AppStrings.message_task_request_timeout) ||
            normalized.endsWith(AppStrings.message_task_share_session_expired) ||
            normalized == AppStrings.message_task_device_offline ||
            normalized == AppStrings.message_task_cancelled ||
            normalized.startsWith(AppStrings.ui_request_timeout) ||
            normalized.endsWith(AppStrings.ui_disconnected) ||
            normalized.endsWith(AppStrings.message_task_share_session_expired)
        )
}

fun normalizeTaskResultDisplay(path: String, message: String): Pair<String, String> {
    val normalizedPath = path.trim()
    val normalizedMessage = normalizeRepeatedTaskResultMessage(message.trim())
    return if (normalizedPath.isNotEmpty() && shouldHideTaskResultPath(normalizedMessage)) {
        "" to normalizedMessage
    } else {
        normalizedPath to normalizedMessage
    }
}

private fun normalizeRepeatedTaskResultMessage(message: String): String {
    val packingPrefix = AppStrings.ui_packing
    if (!message.startsWith(packingPrefix)) return message
    val packedPath = message.removePrefix(packingPrefix).trim()
    return if (packedPath.isNotEmpty()) packingPrefix else message
}

fun formatTaskResultDisplay(path: String, message: String): String {
    val (displayPath, displayMessage) = normalizeTaskResultDisplay(path, message)
    return if (displayPath.isNotEmpty()) {
        "$displayPath: $displayMessage"
    } else {
        displayMessage
    }
}

internal fun String.isTransientTaskResultMessage(): Boolean {
    val message = trim()
    if (message.isEmpty()) return true
    val transientPrefixes = listOf(
        AppStrings.legacy_task_start_marker,
        AppStrings.task_transient_progressive_prefix,
        AppStrings.ui_copy_progress,
        AppStrings.ui_upload_progress,
        AppStrings.ui_download_progress,
        AppStrings.ui_transfer_progress,
        AppStrings.ui_downgrading_long_term_downloads,
        AppStrings.ui_pause_request,
        AppStrings.ui_suspended,
        AppStrings.ui_requesting_remote,
        AppStrings.ui_copying_within_device,
        AppStrings.ui_restored,
        AppStrings.ui_remote_pause_request_failed,
        AppStrings.ui_remote_continued_request_failed,
        AppStrings.ui_replacement,
        AppStrings.ui_retrying,
        AppStrings.ui_copy_completed,
        AppStrings.ui_synchronization_completed,
    )
    val legacyChineseTransientPrefixes = listOf(
        AppStrings.legacy_task_start_marker,
        AppStrings.task_transient_progressive_prefix,
        AppStrings.ui_wait,
        AppStrings.ui_copy_progress,
        AppStrings.ui_upload_progress,
        AppStrings.ui_download_progress,
        AppStrings.ui_transfer_progress,
        AppStrings.ui_pause_request,
        AppStrings.ui_suspended,
        AppStrings.ui_restored,
        AppStrings.ui_try_again,
    )
    return transientPrefixes.any { prefix -> message.startsWith(prefix) } ||
        legacyChineseTransientPrefixes.any { prefix -> message.startsWith(prefix) }
}
