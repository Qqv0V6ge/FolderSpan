package com.folderspan.pro.presentation.screen.auth

import strings.AppStrings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.folderspan.pro.core.common.ApiResult
import com.folderspan.pro.core.common.sanitizeUserInput
import com.folderspan.pro.core.datastore.SessionManager
import com.folderspan.pro.core.datastore.parseAuthSession
import com.folderspan.pro.data.mapper.displaySuccessMessageOrNull
import com.folderspan.pro.domain.repository.AuthRepository
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class LoginViewModel(
    private val repository: AuthRepository,
    private val onSessionAuthorized: suspend (String) -> Unit = {},
) : ViewModel() {
    private val _state = MutableStateFlow(
        LoginUiState(email = SessionManager.rememberedEmail().orEmpty()),
    )
    val state: StateFlow<LoginUiState> = _state
    private val effects = Channel<AuthEffect>(capacity = Channel.BUFFERED)
    val effect = effects.receiveAsFlow()

    fun onEvent(event: LoginEvent) {
        when (event) {
            is LoginEvent.EmailChanged -> updateInput { copy(email = sanitizeUserInput(event.value)) }
            is LoginEvent.PasswordChanged -> updateInput { copy(password = sanitizeUserInput(event.value)) }
            is LoginEvent.AgreementChanged -> updateInput { copy(agreed = event.value) }
            LoginEvent.TogglePasswordVisibility -> _state.update { it.copy(passwordVisible = !it.passwordVisible) }
            LoginEvent.Submit -> submit()
        }
    }

    private fun submit() {
        val current = state.value
        if (current.isSubmitting) return
        val validationMessage = validateLogin(current.email, current.password, current.agreed)
        if (validationMessage != null) {
            _state.update { it.copy(errorMessage = validationMessage, successMessage = null) }
            return
        }
        viewModelScope.launch {
            _state.update { it.copy(isSubmitting = true, errorMessage = null, successMessage = null) }
            when (val result = repository.login(current.email.trim(), current.password)) {
                is ApiResult.Success -> {
                    val session = parseAuthSession(result.data)
                    if (session == null) {
                        _state.update {
                            it.copy(
                                isSubmitting = false,
                                errorMessage = result.data.displaySuccessMessageOrNull()
                                    ?: AppStrings.ui_login_was_successful_but_complete_login_information_has_not,
                            )
                        }
                    } else {
                        SessionManager.update(
                            session.copy(
                                userEmail = session.userEmail ?: current.email.trim(),
                            ),
                        )
                        viewModelScope.launch {
                            runCatching { onSessionAuthorized(session.accessToken) }
                        }
                        _state.update {
                            it.copy(
                                isSubmitting = false,
                                successMessage = result.data.displaySuccessMessageOrNull() ?: AppStrings.ui_login_successful,
                            )
                        }
                        effects.send(AuthEffect.Authorized)
                    }
                }

                is ApiResult.Failure -> {
                    _state.update { it.copy(isSubmitting = false, errorMessage = result.message) }
                }
            }
        }
    }

    private fun updateInput(transform: LoginUiState.() -> LoginUiState) {
        _state.update { it.transform().copy(errorMessage = null, successMessage = null) }
    }
}
