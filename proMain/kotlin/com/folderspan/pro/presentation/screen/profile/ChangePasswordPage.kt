package com.folderspan.pro.presentation.screen.profile

import strings.AppStrings

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.folderspan.pro.core.ui.components.AuthStatusTone
import com.folderspan.pro.core.ui.components.ProSnackbarEffect
import com.folderspan.pro.core.ui.components.ProSnackbarHost
import com.folderspan.pro.core.ui.components.proSnackbarPrompt

@Composable
fun ChangePasswordPage(
    modifier: Modifier = Modifier,
    state: ChangePasswordUiState,
    onNavigateBack: () -> Unit = {},
    onOldPasswordChange: (String) -> Unit = {},
    onNewPasswordChange: (String) -> Unit = {},
    onConfirmPasswordChange: (String) -> Unit = {},
    onOldPasswordVisibilityToggle: () -> Unit = {},
    onNewPasswordVisibilityToggle: () -> Unit = {},
    onConfirmPasswordVisibilityToggle: () -> Unit = {},
    onSubmit: () -> Unit = {},
    onLogout: () -> Unit = {},
) {
    val snackbarHostState = remember { SnackbarHostState() }
    val snackbarPrompt = proSnackbarPrompt(
        message = state.pageErrorMessage,
        tone = AuthStatusTone.Error,
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
            title = AppStrings.ui_change_password,
            isFormPage = true,
            onNavigateBack = onNavigateBack,
            onLogout = onLogout
        ) { requestLogout ->
            EditorPageContent(
                isLoading = false,
                hasContent = true,
                loadingTitle = "",
                fallbackTitle = "",
                fallbackDescription = "",
                onRetry = {},
                onLogout = requestLogout,
                onRefresh = null,
            ) {
                PasswordEditorCard(
                    isSubmitting = state.isSubmitting,
                    oldPassword = state.oldPassword,
                    newPassword = state.newPassword,
                    confirmPassword = state.confirmPassword,
                    oldPasswordVisible = state.oldPasswordVisible,
                    newPasswordVisible = state.newPasswordVisible,
                    confirmPasswordVisible = state.confirmPasswordVisible,
                    oldPasswordFieldError = state.oldPasswordFieldError,
                    newPasswordFieldError = state.newPasswordFieldError,
                    confirmPasswordFieldError = state.confirmPasswordFieldError,
                    onOldPasswordChange = onOldPasswordChange,
                    onNewPasswordChange = onNewPasswordChange,
                    onConfirmPasswordChange = onConfirmPasswordChange,
                    onOldPasswordVisibilityToggle = onOldPasswordVisibilityToggle,
                    onNewPasswordVisibilityToggle = onNewPasswordVisibilityToggle,
                    onConfirmPasswordVisibilityToggle = onConfirmPasswordVisibilityToggle,
                    onSubmit = onSubmit
                )
            }
        }
        ProSnackbarHost(
            hostState = snackbarHostState,
            modifier = Modifier.align(Alignment.BottomCenter),
        )
    }
}
