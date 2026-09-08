package com.folderspan.pro.presentation.screen.auth

import strings.AppStrings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Email
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
fun LoginPage(
    modifier: Modifier = Modifier,
    state: LoginUiState,
    prompt: String? = null,
    onEvent: (LoginEvent) -> Unit,
    onNavigateBack: () -> Unit = {},
    onNavigateToRegistration: () -> Unit = {},
    onNavigateToRecovery: () -> Unit = {},
) {
    val snackbarHostState = remember { SnackbarHostState() }
    val snackbarPrompt = proSnackbarPrompt(
        message = state.errorMessage,
        tone = AuthStatusTone.Error,
    ) ?: proSnackbarPrompt(
        message = state.successMessage,
        tone = AuthStatusTone.Success,
    )
    ProSnackbarEffect(
        hostState = snackbarHostState,
        prompt = snackbarPrompt,
    )

    Box(modifier = modifier.fillMaxSize()) {
        FeatureFormScaffold(
            modifier = Modifier.fillMaxSize(),
            scrollThreshold = 760.dp,
            navigationIcon = {
                FeatureFormBackButton(
                    onClick = onNavigateBack,
                    enabled = !state.isSubmitting,
                )
            },
        ) { contentModifier ->
            LoginFormSection(
                modifier = contentModifier,
                description = prompt ?: AppStrings.ui_enter_your_email_and_password_to_continue_to_your_personal_space,
                email = state.email,
                password = state.password,
                passwordVisible = state.passwordVisible,
                agreed = state.agreed,
                isSubmitting = state.isSubmitting,
                onEmailChange = { onEvent(LoginEvent.EmailChanged(it)) },
                onPasswordChange = { onEvent(LoginEvent.PasswordChanged(it)) },
                onPasswordVisibilityToggle = { onEvent(LoginEvent.TogglePasswordVisibility) },
                onAgreeChange = { onEvent(LoginEvent.AgreementChanged(it)) },
                onSubmit = { onEvent(LoginEvent.Submit) },
                onNavigateToRegistration = onNavigateToRegistration,
                onNavigateToRecovery = onNavigateToRecovery,
            )
        }
        ProSnackbarHost(
            hostState = snackbarHostState,
            modifier = Modifier.align(Alignment.BottomCenter),
        )
    }
}

@Composable
private fun LoginFormSection(
    modifier: Modifier = Modifier,
    description: String,
    email: String,
    password: String,
    passwordVisible: Boolean,
    agreed: Boolean,
    isSubmitting: Boolean,
    onEmailChange: (String) -> Unit,
    onPasswordChange: (String) -> Unit,
    onPasswordVisibilityToggle: () -> Unit,
    onAgreeChange: (Boolean) -> Unit,
    onSubmit: () -> Unit,
    onNavigateToRegistration: () -> Unit,
    onNavigateToRecovery: () -> Unit,
) {
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(18.dp),
    ) {
        FeatureFormHeader(
            title = AppStrings.ui_login_account,
            description = description,
        )

        FeatureTextField(
            label = AppStrings.ui_email,
            value = email,
            onValueChange = onEmailChange,
            placeholder = "name@example.com",
            keyboardType = KeyboardType.Email,
            leadingIcon = Icons.Filled.Email,
            enabled = !isSubmitting,
        )

        FeaturePasswordField(
            label = AppStrings.ui_password,
            placeholder = AppStrings.ui_please_enter_your_password,
            password = password,
            passwordVisible = passwordVisible,
            onPasswordChange = onPasswordChange,
            onPasswordVisibilityToggle = onPasswordVisibilityToggle,
            enabled = !isSubmitting,
        )

        FeatureAgreementRow(
            checked = agreed,
            onCheckedChange = onAgreeChange,
            enabled = !isSubmitting,
        )

        FeatureSubmitButton(
            text = if (isSubmitting) AppStrings.auth_logging_in else AppStrings.ui_login,
            onClick = onSubmit,
            enabled = !isSubmitting,
        )

        FeatureFullWidthTextButton(
            text = AppStrings.ui_forgot_your_password,
            onClick = onNavigateToRecovery,
            enabled = !isSubmitting,
        )

        FeatureAuthSwitchRow(
            prompt = AppStrings.ui_don_t_have_account_yet,
            actionLabel = AppStrings.ui_register_now,
            onActionClick = onNavigateToRegistration,
            enabled = !isSubmitting,
        )
    }
}
