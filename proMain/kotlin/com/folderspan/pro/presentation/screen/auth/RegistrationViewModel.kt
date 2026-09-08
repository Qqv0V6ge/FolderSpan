package com.folderspan.pro.presentation.screen.auth

import strings.AppStrings

import com.folderspan.pro.core.common.sanitizeUserInput
import com.folderspan.pro.domain.repository.AuthRepository
import com.folderspan.pro.data.mapper.displaySuccessMessageOrNull
import com.folderspan.pro.core.common.ApiResult
import com.folderspan.pro.core.datastore.SessionManager
import com.folderspan.pro.core.datastore.parseAuthSession
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class RegistrationViewModel(
    private val repository: AuthRepository,
    private val onSessionAuthorized: suspend (String) -> Unit = {},
) : ViewModel() {
    private val _state = MutableStateFlow(RegistrationUiState())
    val state: StateFlow<RegistrationUiState> = _state
    private val effects = Channel<AuthEffect>(capacity = Channel.BUFFERED)
    val effect = effects.receiveAsFlow()

    fun onEvent(event: RegistrationEvent) {
        when (event) {
            is RegistrationEvent.NameChanged -> updateInput { copy(name = sanitizeUserInput(event.value)) }
            is RegistrationEvent.EmailChanged -> updateInput { copy(email = sanitizeUserInput(event.value)) }
            is RegistrationEvent.PasswordChanged -> updateInput { copy(password = sanitizeUserInput(event.value)) }
            is RegistrationEvent.ConfirmPasswordChanged -> updateInput { copy(confirmPassword = sanitizeUserInput(event.value)) }
            is RegistrationEvent.AgreementChanged -> updateInput { copy(agreed = event.value) }
            RegistrationEvent.TogglePasswordVisibility -> _state.update { it.copy(passwordVisible = !it.passwordVisible) }
            RegistrationEvent.ToggleConfirmPasswordVisibility -> _state.update {
                it.copy(confirmPasswordVisible = !it.confirmPasswordVisible)
            }
            RegistrationEvent.Submit -> submit()
        }
    }

    private fun submit() {
        val current = state.value
        if (current.isSubmitting) return
        val validationMessage = validateRegistration(
            email = current.email,
            password = current.password,
            confirmPassword = current.confirmPassword,
            agreed = current.agreed,
        )
        if (validationMessage != null) {
            _state.update { it.copy(errorMessage = validationMessage, successMessage = null) }
            return
        }
        viewModelScope.launch {
            _state.update { it.copy(isSubmitting = true, errorMessage = null, successMessage = null) }
            when (
                val result = repository.register(
                    name = current.name,
                    email = current.email.trim(),
                    password = current.password,
                )
            ) {
                is ApiResult.Success -> {
                    val session = parseAuthSession(result.data)
                    if (session == null) {
                        _state.update {
                            it.copy(
                                password = "",
                                confirmPassword = "",
                                isSubmitting = false,
                                successMessage = result.data.displaySuccessMessageOrNull() ?: AppStrings.ui_registration_successful_please_return_log,
                            )
                        }
                    } else {
                        SessionManager.update(session)
                        viewModelScope.launch {
                            runCatching { onSessionAuthorized(session.accessToken) }
                        }
                        _state.update {
                            it.copy(
                                isSubmitting = false,
                                successMessage = result.data.displaySuccessMessageOrNull() ?: AppStrings.ui_registration_successful_you_have_automatically_logged,
                            )
                        }
                        effects.send(AuthEffect.Authorized)
                    }
                }

                is ApiResult.Failure -> _state.update {
                    it.copy(isSubmitting = false, errorMessage = result.message)
                }
            }
        }
    }

    private fun updateInput(transform: RegistrationUiState.() -> RegistrationUiState) {
        _state.update { it.transform().copy(errorMessage = null, successMessage = null) }
    }
}
