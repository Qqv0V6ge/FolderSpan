package com.folderspan.pro.presentation.screen.profile

import strings.AppStrings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.folderspan.pro.core.common.ApiResult
import com.folderspan.pro.core.common.isUnauthorized
import com.folderspan.pro.core.common.sanitizeUserInput
import com.folderspan.pro.core.datastore.AuthSession
import com.folderspan.pro.core.datastore.SessionManager
import com.folderspan.pro.core.ui.components.AuthStatusTone
import com.folderspan.pro.data.mapper.displaySuccessMessageOrNull
import com.folderspan.pro.domain.model.ChangePasswordCommand
import com.folderspan.pro.domain.repository.UserRepository
import com.folderspan.pro.domain.usecase.AuthorizedUserRequestExecutor
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class ChangePasswordViewModel(
    private val repository: UserRepository,
) : ViewModel() {
    private val executor = AuthorizedUserRequestExecutor(repository::refreshToken)
    private val _state = MutableStateFlow(ChangePasswordUiState())
    val state: StateFlow<ChangePasswordUiState> = _state
    private val effects = Channel<ProfileEffect>(capacity = Channel.BUFFERED)
    val effect = effects.receiveAsFlow()
    private var session: AuthSession? = null

    fun onSessionChanged(session: AuthSession?) {
        this.session = session
    }

    fun onOldPasswordChange(value: String) {
        _state.update {
            it.copy(
                oldPassword = sanitizeUserInput(value),
                feedback = null,
                oldPasswordFieldError = null,
            )
        }
    }

    fun onNewPasswordChange(value: String) {
        _state.update {
            it.copy(
                newPassword = sanitizeUserInput(value),
                feedback = null,
                newPasswordFieldError = null,
                confirmPasswordFieldError = null,
            )
        }
    }

    fun onConfirmPasswordChange(value: String) {
        _state.update {
            it.copy(
                confirmPassword = sanitizeUserInput(value),
                feedback = null,
                confirmPasswordFieldError = null,
            )
        }
    }

    fun toggleOldPasswordVisibility() {
        _state.update { it.copy(oldPasswordVisible = !it.oldPasswordVisible) }
    }

    fun toggleNewPasswordVisibility() {
        _state.update { it.copy(newPasswordVisible = !it.newPasswordVisible) }
    }

    fun toggleConfirmPasswordVisibility() {
        _state.update { it.copy(confirmPasswordVisible = !it.confirmPasswordVisible) }
    }

    fun submit() {
        val current = _state.value
        if (current.isSubmitting) return
        val oldPasswordError = if (current.oldPassword.isBlank()) AppStrings.ui_please_enter_your_old_password else null
        val newPasswordError = if (current.newPassword.length < 8) AppStrings.ui_new_password_needs_least_8_characters_long else null
        val confirmPasswordError = when {
            current.confirmPassword.isBlank() -> AppStrings.ui_please_enter_your_new_password_again
            current.newPassword != current.confirmPassword ->
                AppStrings.validation_new_passwords_do_not_match
            else -> null
        }
        if (oldPasswordError != null || newPasswordError != null || confirmPasswordError != null) {
            _state.update {
                it.copy(
                    oldPasswordFieldError = oldPasswordError,
                    newPasswordFieldError = newPasswordError,
                    confirmPasswordFieldError = confirmPasswordError,
                )
            }
            return
        }

        viewModelScope.launch {
            _state.update { it.copy(isSubmitting = true, feedback = null) }
            val activeSession = SessionManager.currentSession() ?: session
            when (
                val result = executor.execute(
                    session = activeSession,
                    actionLabel = AppStrings.ui_update_password,
                    onUnauthorized = ::emitLoggedOut,
                ) { token ->
                    repository.changePassword(
                        command = ChangePasswordCommand(
                            oldPassword = current.oldPassword,
                            newPassword = current.newPassword,
                        ),
                        token = token,
                    )
                }
            ) {
                is ApiResult.Success -> _state.update {
                    it.copy(
                        oldPassword = "",
                        newPassword = "",
                        confirmPassword = "",
                        oldPasswordVisible = false,
                        newPasswordVisible = false,
                        confirmPasswordVisible = false,
                        feedback = SectionFeedback(
                            tone = AuthStatusTone.Success,
                            text = result.data.displaySuccessMessageOrNull() ?: AppStrings.ui_password_has_been_updated,
                        ),
                    )
                }

                is ApiResult.Failure -> {
                    if (!result.isUnauthorized()) {
                        _state.update {
                            it.copy(
                                feedback = SectionFeedback(
                                    tone = AuthStatusTone.Error,
                                    text = result.message,
                                ),
                            )
                        }
                    }
                }
            }
            _state.update { it.copy(isSubmitting = false) }
        }
    }

    private fun emitLoggedOut() {
        viewModelScope.launch {
            effects.send(ProfileEffect.LoggedOut)
        }
    }
}
