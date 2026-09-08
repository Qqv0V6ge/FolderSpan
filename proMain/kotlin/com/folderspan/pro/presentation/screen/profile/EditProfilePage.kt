package com.folderspan.pro.presentation.screen.profile

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.folderspan.pro.core.ui.components.AuthStatusTone
import com.folderspan.pro.core.ui.components.ProSnackbarEffect
import com.folderspan.pro.core.ui.components.ProSnackbarHost
import com.folderspan.pro.core.ui.components.proSnackbarPrompt
import strings.AppStrings

@Composable
fun EditProfilePage(
    modifier: Modifier = Modifier,
    state: EditProfileUiState,
    onNavigateBack: () -> Unit = {},
    onRefresh: () -> Unit = {},
    onNameChange: (String) -> Unit = {},
    onAvatarPictureOutcome: (AvatarPictureOutcome) -> Unit = {},
    onRequestAvatarRemoval: () -> Unit = {},
    onConfirmAvatarRemoval: () -> Unit = {},
    onDeclineAvatarRemoval: () -> Unit = {},
    onRetryAvatarAction: () -> Unit = {},
    onSignatureChange: (String) -> Unit = {},
    onSubmit: () -> Unit = {},
    onLogout: () -> Unit = {},
) {
    val snackbarHostState = remember { SnackbarHostState() }
    val snackbarPrompt = proSnackbarPrompt(
        message = state.pageErrorMessage,
        tone = AuthStatusTone.Error,
    ) ?: proSnackbarPrompt(
        message = state.avatarFeedback?.text,
        tone = state.avatarFeedback?.tone ?: AuthStatusTone.Info,
    ) ?: proSnackbarPrompt(
        message = state.feedback?.text,
        tone = state.feedback?.tone ?: AuthStatusTone.Info,
    )
    ProSnackbarEffect(
        hostState = snackbarHostState,
        prompt = snackbarPrompt,
    )

    Box(modifier = modifier.fillMaxSize()) {
        AccountEditorScaffold(
            modifier = Modifier.fillMaxSize(),
            title = AppStrings.ui_modify_information,
            isFormPage = true,
            onNavigateBack = onNavigateBack,
            onLogout = onLogout
        ) { requestLogout ->
            EditorPageContent(
                isLoading = state.isLoading,
                hasContent = state.profile != null,
                loadingTitle = AppStrings.ui_loading_data,
                fallbackTitle = AppStrings.ui_unable_read_data_moment,
                fallbackDescription = AppStrings.ui_currently_unable_enter_data_editing_please_reload_first,
                onRetry = onRefresh,
                onLogout = requestLogout
            ) {
                val currentProfile = state.profile
                if (currentProfile != null) {
                    ProfileEditorCard(
                        isSubmitting = state.isSubmitting,
                        isAvatarSubmitting = state.isAvatarSubmitting,
                        profile = currentProfile,
                        name = state.name,
                        signature = state.signature,
                        avatarFeedback = state.avatarFeedback,
                        canRetryAvatarAction = state.canRetryAvatarAction,
                        nameFieldError = state.nameFieldError,
                        onNameChange = onNameChange,
                        onAvatarPictureOutcome = onAvatarPictureOutcome,
                        onRequestAvatarRemoval = onRequestAvatarRemoval,
                        onRetryAvatarAction = onRetryAvatarAction,
                        onSignatureChange = onSignatureChange,
                        onSubmit = onSubmit
                    )
                }
            }
        }
        ProSnackbarHost(
            hostState = snackbarHostState,
            modifier = Modifier.align(Alignment.BottomCenter),
        )
        if (state.isAvatarRemovalConfirmationVisible) {
            AlertDialog(
                onDismissRequest = onDeclineAvatarRemoval,
                title = { Text(AppStrings.ui_profile_avatar_remove_confirm_title) },
                text = { Text(AppStrings.ui_profile_avatar_remove_confirm_message) },
                confirmButton = {
                    Button(onClick = onConfirmAvatarRemoval) {
                        Text(AppStrings.ui_profile_avatar_remove)
                    }
                },
                dismissButton = {
                    OutlinedButton(onClick = onDeclineAvatarRemoval) {
                        Text(AppStrings.ui_cancel)
                    }
                },
            )
        }
    }
}
