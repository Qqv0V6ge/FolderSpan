package com.folderspan.pro.presentation.screen.auth

import strings.AppStrings

import com.folderspan.pro.core.common.sanitizeUserInput
import com.folderspan.pro.domain.repository.AuthRepository
import com.folderspan.pro.data.mapper.displaySuccessMessageOrNull
import com.folderspan.pro.core.common.ApiResult
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class RecoveryViewModel(
    private val repository: AuthRepository,
) : ViewModel() {
    private val _state = MutableStateFlow(RecoveryUiState())
    val state: StateFlow<RecoveryUiState> = _state

    fun onEvent(event: RecoveryEvent) {
        when (event) {
            is RecoveryEvent.EmailChanged -> updateEmail(event.value)
            is RecoveryEvent.VerificationCodeChanged -> updateInput {
                copy(
                    verificationCode = sanitizeUserInput(event.value).filter(Char::isDigit).take(6),
                    verificationCodeFieldError = null,
                )
            }
            is RecoveryEvent.NewPasswordChanged -> updateInput {
                copy(
                    newPassword = sanitizeUserInput(event.value),
                    newPasswordFieldError = null,
                    confirmPasswordFieldError = null,
                )
            }
            is RecoveryEvent.ConfirmPasswordChanged -> updateInput {
                copy(
                    confirmPassword = sanitizeUserInput(event.value),
                    confirmPasswordFieldError = null,
                )
            }
            RecoveryEvent.TogglePasswordVisibility -> _state.update { it.copy(passwordVisible = !it.passwordVisible) }
            RecoveryEvent.ToggleConfirmPasswordVisibility -> _state.update {
                it.copy(confirmPasswordVisible = !it.confirmPasswordVisible)
            }
            RecoveryEvent.SendVerificationCode -> sendVerificationCode()
            RecoveryEvent.SubmitReset -> resetPassword()
        }
    }

    private fun updateEmail(value: String) {
        val sanitizedValue = sanitizeUserInput(value)
        _state.update {
            val previousEmail = it.email.trim()
            it.copy(
                email = sanitizedValue,
                errorMessage = null,
                successMessage = null,
                emailFieldError = null,
                hasRequestedCode = previousEmail == sanitizedValue && it.hasRequestedCode,
                infoMessage = if (previousEmail != sanitizedValue) null else it.infoMessage,
            )
        }
    }

    private fun sendVerificationCode() {
        val current = state.value
        if (current.isSendingCode || current.isResettingPassword) return
        val emailError = validateEmail(current.email)
        if (emailError != null) {
            _state.update {
                it.copy(
                    emailFieldError = emailError,
                    errorMessage = null,
                    successMessage = null,
                )
            }
            return
        }
        viewModelScope.launch {
            _state.update {
                it.copy(
                    isSendingCode = true,
                    emailFieldError = null,
                    errorMessage = null,
                    successMessage = null,
                )
            }
            when (val result = repository.requestPasswordReset(current.email.trim())) {
                is ApiResult.Success -> _state.update {
                    it.copy(
                        isSendingCode = false,
                        hasRequestedCode = true,
                        infoMessage = result.data.displaySuccessMessageOrNull()
                            ?: AppStrings.ui_the_verification_code_was_sent_check_your_email,
                    )
                }

                is ApiResult.Failure -> _state.update {
                    it.copy(isSendingCode = false, errorMessage = result.message)
                }
            }
        }
    }

    private fun resetPassword() {
        val current = state.value
        if (current.isSendingCode || current.isResettingPassword) return
        val fieldErrors = validateRecoveryFields(
            email = current.email,
            verificationCode = current.verificationCode,
            newPassword = current.newPassword,
            confirmPassword = current.confirmPassword,
        )
        if (fieldErrors.hasError()) {
            _state.update {
                it.copy(
                    emailFieldError = fieldErrors.email,
                    verificationCodeFieldError = fieldErrors.verificationCode,
                    newPasswordFieldError = fieldErrors.newPassword,
                    confirmPasswordFieldError = fieldErrors.confirmPassword,
                    errorMessage = null,
                    successMessage = null,
                )
            }
            return
        }
        viewModelScope.launch {
            _state.update {
                it.copy(
                    isResettingPassword = true,
                    emailFieldError = null,
                    verificationCodeFieldError = null,
                    newPasswordFieldError = null,
                    confirmPasswordFieldError = null,
                    errorMessage = null,
                    successMessage = null,
                )
            }
            when (
                val result = repository.resetPassword(
                    email = current.email.trim(),
                    code = current.verificationCode.trim(),
                    newPassword = current.newPassword,
                )
            ) {
                is ApiResult.Success -> _state.update {
                    it.copy(
                        verificationCode = "",
                        newPassword = "",
                        confirmPassword = "",
                        passwordVisible = false,
                        confirmPasswordVisible = false,
                        hasRequestedCode = false,
                        isResettingPassword = false,
                        infoMessage = null,
                        successMessage = result.data.displaySuccessMessageOrNull() ?: AppStrings.ui_password_has_been_reset_please_log_again,
                    )
                }

                is ApiResult.Failure -> _state.update {
                    it.copy(isResettingPassword = false, errorMessage = result.message)
                }
            }
        }
    }

    private fun updateInput(transform: RecoveryUiState.() -> RecoveryUiState) {
        _state.update { it.transform().copy(errorMessage = null, successMessage = null) }
    }
}
