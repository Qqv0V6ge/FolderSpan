package com.folderspan.ui.components

import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult

internal suspend fun SnackbarHostState.showLatestSnackbar(
    message: String,
    actionLabel: String? = null,
    withDismissAction: Boolean = false,
    duration: SnackbarDuration = SnackbarDuration.Short,
): SnackbarResult {
    currentSnackbarData?.dismiss()
    return showSnackbar(message, actionLabel, withDismissAction, duration)
}

internal suspend fun SnackbarHostState.confirmSnackbarAction(
    message: String,
    actionLabel: String,
    onConfirm: suspend () -> Unit,
) {
    if (
        showLatestSnackbar(
            message = message,
            actionLabel = actionLabel,
            withDismissAction = true,
        ) == SnackbarResult.ActionPerformed
    ) {
        onConfirm()
    }
}
