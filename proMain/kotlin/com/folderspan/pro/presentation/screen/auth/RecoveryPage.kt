package com.folderspan.pro.presentation.screen.auth

import strings.AppStrings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Email
import androidx.compose.material.icons.filled.Password
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.folderspan.pro.core.ui.components.AuthStatusTone
import com.folderspan.pro.core.ui.components.ProSnackbarEffect
import com.folderspan.pro.core.ui.components.ProSnackbarHost
import com.folderspan.pro.core.ui.components.proSnackbarPrompt
import com.folderspan.pro.presentation.component.*

@Composable
fun RecoveryPage(
    modifier: Modifier = Modifier,
    state: RecoveryUiState,
    onEvent: (RecoveryEvent) -> Unit,
    onNavigateBack: () -> Unit = {},
    onNavigateToLogin: () -> Unit = {},
) {
    val snackbarHostState = remember { SnackbarHostState() }
    val snackbarPrompt = proSnackbarPrompt(
        message = state.errorMessage,
        tone = AuthStatusTone.Error,
    ) ?: proSnackbarPrompt(
        message = state.successMessage,
        tone = AuthStatusTone.Success,
    ) ?: proSnackbarPrompt(
        message = state.infoMessage,
        tone = AuthStatusTone.Info,
    )
    ProSnackbarEffect(
        hostState = snackbarHostState,
        prompt = snackbarPrompt,
    )

    Box(modifier = modifier.fillMaxSize()) {
        FeatureFormScaffold(
            modifier = Modifier.fillMaxSize(),
            scrollThreshold = 860.dp,
            navigationIcon = {
                val formEnabled = !state.isSendingCode && !state.isResettingPassword
                FeatureFormBackButton(
                    onClick = onNavigateBack,
                    enabled = formEnabled,
                )
            },
        ) { contentModifier ->
            RecoveryFormSection(
                modifier = contentModifier,
                email = state.email,
                verificationCode = state.verificationCode,
                newPassword = state.newPassword,
                confirmPassword = state.confirmPassword,
                passwordVisible = state.passwordVisible,
                confirmPasswordVisible = state.confirmPasswordVisible,
                hasRequestedCode = state.hasRequestedCode,
                isSendingCode = state.isSendingCode,
                isResettingPassword = state.isResettingPassword,
                emailFieldError = state.emailFieldError,
                verificationCodeFieldError = state.verificationCodeFieldError,
                newPasswordFieldError = state.newPasswordFieldError,
                confirmPasswordFieldError = state.confirmPasswordFieldError,
                onEmailChange = { onEvent(RecoveryEvent.EmailChanged(it)) },
                onVerificationCodeChange = { onEvent(RecoveryEvent.VerificationCodeChanged(it)) },
                onNewPasswordChange = { onEvent(RecoveryEvent.NewPasswordChanged(it)) },
                onConfirmPasswordChange = { onEvent(RecoveryEvent.ConfirmPasswordChanged(it)) },
                onPasswordVisibilityToggle = { onEvent(RecoveryEvent.TogglePasswordVisibility) },
                onConfirmPasswordVisibilityToggle = { onEvent(RecoveryEvent.ToggleConfirmPasswordVisibility) },
                onSendVerificationCode = { onEvent(RecoveryEvent.SendVerificationCode) },
                onSubmitResetPassword = { onEvent(RecoveryEvent.SubmitReset) },
                onNavigateToLogin = onNavigateToLogin,
            )
        }
        ProSnackbarHost(
            hostState = snackbarHostState,
            modifier = Modifier.align(Alignment.BottomCenter),
        )
    }
}

@Composable
private fun RecoveryFormSection(
    modifier: Modifier = Modifier,
    email: String,
    verificationCode: String,
    newPassword: String,
    confirmPassword: String,
    passwordVisible: Boolean,
    confirmPasswordVisible: Boolean,
    hasRequestedCode: Boolean,
    isSendingCode: Boolean,
    isResettingPassword: Boolean,
    emailFieldError: String?,
    verificationCodeFieldError: String?,
    newPasswordFieldError: String?,
    confirmPasswordFieldError: String?,
    onEmailChange: (String) -> Unit,
    onVerificationCodeChange: (String) -> Unit,
    onNewPasswordChange: (String) -> Unit,
    onConfirmPasswordChange: (String) -> Unit,
    onPasswordVisibilityToggle: () -> Unit,
    onConfirmPasswordVisibilityToggle: () -> Unit,
    onSendVerificationCode: () -> Unit,
    onSubmitResetPassword: () -> Unit,
    onNavigateToLogin: () -> Unit,
) {
    val formEnabled = !isSendingCode && !isResettingPassword

    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(18.dp),
    ) {
        FeatureFormHeader(
            title = if (hasRequestedCode) AppStrings.ui_change_password else AppStrings.ui_retrieve_password,
            description = if (hasRequestedCode) {
                AppStrings.ui_enter_the_verification_code_sent_to_your_email_and_set_a_new_sign_in_password
            } else {
                AppStrings.ui_enter_your_registered_email_to_receive_a_verification_code_then_set_a_new
            },
        )

        if (!hasRequestedCode) {
            FeatureTextField(
                label = AppStrings.ui_email,
                value = email,
                onValueChange = onEmailChange,
                placeholder = "name@example.com",
                keyboardType = KeyboardType.Email,
                leadingIcon = Icons.Filled.Email,
                errorMessage = emailFieldError,
                enabled = formEnabled,
            )

            FeatureSubmitButton(
                text = if (isSendingCode) AppStrings.auth_sending_verification_code else AppStrings.ui_send_verification_code,
                onClick = onSendVerificationCode,
                enabled = formEnabled,
            )

            FeatureHintPanel(
                text = AppStrings.ui_verification_code_will_sent_email_address_you_used_when,
                iconSize = 16.dp,
            )
        }

        if (hasRequestedCode) {
            FeatureTextField(
                label = AppStrings.ui_verification_code,
                value = verificationCode,
                onValueChange = onVerificationCodeChange,
                placeholder = AppStrings.ui_enter_6_digit_verification_code,
                keyboardType = KeyboardType.Number,
                leadingIcon = Icons.Filled.Password,
                errorMessage = verificationCodeFieldError,
                enabled = formEnabled,
            )

            FeaturePasswordField(
                label = AppStrings.ui_new_password,
                placeholder = AppStrings.ui_least_8_digits,
                password = newPassword,
                passwordVisible = passwordVisible,
                onPasswordChange = onNewPasswordChange,
                onPasswordVisibilityToggle = onPasswordVisibilityToggle,
                errorMessage = newPasswordFieldError,
                enabled = formEnabled,
            )

            FeaturePasswordField(
                label = AppStrings.ui_confirm_new_password,
                placeholder = AppStrings.ui_enter_new_password_again,
                password = confirmPassword,
                passwordVisible = confirmPasswordVisible,
                onPasswordChange = onConfirmPasswordChange,
                onPasswordVisibilityToggle = onConfirmPasswordVisibilityToggle,
                errorMessage = confirmPasswordFieldError,
                enabled = formEnabled,
            )

            FeatureHintPanel(
                text = AppStrings.ui_password_recommended_8_20_characters_long_must_contain_letters,
                iconSize = 16.dp,
            )

            FeatureSubmitButton(
                text = if (isResettingPassword) AppStrings.ui_resetting else AppStrings.ui_reset_password,
                onClick = onSubmitResetPassword,
                enabled = formEnabled,
            )
        }

        FeatureFullWidthTextButton(
            text = AppStrings.ui_return_login,
            onClick = onNavigateToLogin,
            enabled = formEnabled,
        )
    }
}
