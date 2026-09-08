package com.folderspan.pro.presentation.screen.auth

data class LoginUiState(
    val email: String = "",
    val password: String = "",
    val passwordVisible: Boolean = false,
    val agreed: Boolean = false,
    val isSubmitting: Boolean = false,
    val errorMessage: String? = null,
    val successMessage: String? = null,
)

sealed interface LoginEvent {
    data class EmailChanged(val value: String) : LoginEvent
    data class PasswordChanged(val value: String) : LoginEvent
    data class AgreementChanged(val value: Boolean) : LoginEvent
    data object TogglePasswordVisibility : LoginEvent
    data object Submit : LoginEvent
}

data class RegistrationUiState(
    val name: String = "",
    val email: String = "",
    val password: String = "",
    val confirmPassword: String = "",
    val passwordVisible: Boolean = false,
    val confirmPasswordVisible: Boolean = false,
    val agreed: Boolean = false,
    val isSubmitting: Boolean = false,
    val errorMessage: String? = null,
    val successMessage: String? = null,
)

sealed interface RegistrationEvent {
    data class NameChanged(val value: String) : RegistrationEvent
    data class EmailChanged(val value: String) : RegistrationEvent
    data class PasswordChanged(val value: String) : RegistrationEvent
    data class ConfirmPasswordChanged(val value: String) : RegistrationEvent
    data class AgreementChanged(val value: Boolean) : RegistrationEvent
    data object TogglePasswordVisibility : RegistrationEvent
    data object ToggleConfirmPasswordVisibility : RegistrationEvent
    data object Submit : RegistrationEvent
}

data class RecoveryUiState(
    val email: String = "",
    val verificationCode: String = "",
    val newPassword: String = "",
    val confirmPassword: String = "",
    val passwordVisible: Boolean = false,
    val confirmPasswordVisible: Boolean = false,
    val hasRequestedCode: Boolean = false,
    val isSendingCode: Boolean = false,
    val isResettingPassword: Boolean = false,
    val infoMessage: String? = null,
    val errorMessage: String? = null,
    val successMessage: String? = null,
    val emailFieldError: String? = null,
    val verificationCodeFieldError: String? = null,
    val newPasswordFieldError: String? = null,
    val confirmPasswordFieldError: String? = null,
)

sealed interface RecoveryEvent {
    data class EmailChanged(val value: String) : RecoveryEvent
    data class VerificationCodeChanged(val value: String) : RecoveryEvent
    data class NewPasswordChanged(val value: String) : RecoveryEvent
    data class ConfirmPasswordChanged(val value: String) : RecoveryEvent
    data object TogglePasswordVisibility : RecoveryEvent
    data object ToggleConfirmPasswordVisibility : RecoveryEvent
    data object SendVerificationCode : RecoveryEvent
    data object SubmitReset : RecoveryEvent
}

sealed interface AuthEffect {
    data object Authorized : AuthEffect
}
