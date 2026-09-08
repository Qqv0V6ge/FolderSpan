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
fun RegistrationPage(
    modifier: Modifier = Modifier,
    state: RegistrationUiState,
    onEvent: (RegistrationEvent) -> Unit,
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
    )
    ProSnackbarEffect(
        hostState = snackbarHostState,
        prompt = snackbarPrompt,
    )

    Box(modifier = modifier.fillMaxSize()) {
        FeatureFormScaffold(
            modifier = Modifier.fillMaxSize(),
            scrollThreshold = 800.dp,
            navigationIcon = {
                FeatureFormBackButton(
                    onClick = onNavigateBack,
                    enabled = !state.isSubmitting,
                )
            },
        ) { contentModifier ->
            RegistrationFormSection(
                modifier = contentModifier,
                name = state.name,
                email = state.email,
                password = state.password,
                confirmPassword = state.confirmPassword,
                passwordVisible = state.passwordVisible,
                confirmPasswordVisible = state.confirmPasswordVisible,
                agreed = state.agreed,
                isSubmitting = state.isSubmitting,
                onNameChange = { onEvent(RegistrationEvent.NameChanged(it)) },
                onEmailChange = { onEvent(RegistrationEvent.EmailChanged(it)) },
                onPasswordChange = { onEvent(RegistrationEvent.PasswordChanged(it)) },
                onConfirmPasswordChange = { onEvent(RegistrationEvent.ConfirmPasswordChanged(it)) },
                onPasswordVisibilityToggle = { onEvent(RegistrationEvent.TogglePasswordVisibility) },
                onConfirmPasswordVisibilityToggle = { onEvent(RegistrationEvent.ToggleConfirmPasswordVisibility) },
                onAgreeChange = { onEvent(RegistrationEvent.AgreementChanged(it)) },
                onSubmit = { onEvent(RegistrationEvent.Submit) },
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
private fun RegistrationFormSection(
    modifier: Modifier = Modifier,
    name: String,
    email: String,
    password: String,
    confirmPassword: String,
    passwordVisible: Boolean,
    confirmPasswordVisible: Boolean,
    agreed: Boolean,
    isSubmitting: Boolean,
    onNameChange: (String) -> Unit,
    onEmailChange: (String) -> Unit,
    onPasswordChange: (String) -> Unit,
    onConfirmPasswordChange: (String) -> Unit,
    onPasswordVisibilityToggle: () -> Unit,
    onConfirmPasswordVisibilityToggle: () -> Unit,
    onAgreeChange: (Boolean) -> Unit,
    onSubmit: () -> Unit,
    onNavigateToLogin: () -> Unit,
) {
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(18.dp),
    ) {
        FeatureFormHeader(
            title = AppStrings.ui_create_account,
            description = AppStrings.ui_enter_your_email_and_password_to_create_a_new_account,
        )

        FeatureTextField(
            label = AppStrings.ui_nickname_optional,
            value = name,
            onValueChange = onNameChange,
            placeholder = AppStrings.ui_if_left_blank_email_name_prefix_will_used_default,
            keyboardType = KeyboardType.Text,
            enabled = !isSubmitting,
        )

        FeatureTextField(
            label = AppStrings.ui_email,
            value = email,
            onValueChange = onEmailChange,
            placeholder = AppStrings.ui_enter_your_frequently_used_email_address,
            keyboardType = KeyboardType.Email,
            leadingIcon = Icons.Filled.Email,
            enabled = !isSubmitting,
        )

        FeaturePasswordField(
            label = AppStrings.ui_password,
            placeholder = AppStrings.ui_set_login_password,
            password = password,
            passwordVisible = passwordVisible,
            onPasswordChange = onPasswordChange,
            onPasswordVisibilityToggle = onPasswordVisibilityToggle,
            enabled = !isSubmitting,
        )

        FeaturePasswordField(
            label = AppStrings.ui_confirm_password,
            placeholder = AppStrings.ui_enter_your_login_password_again,
            password = confirmPassword,
            passwordVisible = confirmPasswordVisible,
            onPasswordChange = onConfirmPasswordChange,
            onPasswordVisibilityToggle = onConfirmPasswordVisibilityToggle,
            enabled = !isSubmitting,
        )

        FeatureHintPanel(
            text = AppStrings.ui_password_recommended_8_20_characters_long_must_contain_letters,
            emphasized = true,
        )

        FeatureAgreementRow(
            checked = agreed,
            onCheckedChange = onAgreeChange,
            enabled = !isSubmitting,
        )

        FeatureSubmitButton(
            text = if (isSubmitting) AppStrings.ui_creating else AppStrings.ui_create_account,
            onClick = onSubmit,
            enabled = !isSubmitting,
        )

        FeatureAuthSwitchRow(
            prompt = AppStrings.ui_already_have_account,
            actionLabel = AppStrings.ui_return_login,
            onActionClick = onNavigateToLogin,
            enabled = !isSubmitting,
        )
    }
}
