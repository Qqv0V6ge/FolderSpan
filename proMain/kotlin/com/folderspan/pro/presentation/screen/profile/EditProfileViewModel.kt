package com.folderspan.pro.presentation.screen.profile

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.folderspan.pro.core.common.ApiResult
import com.folderspan.pro.core.common.isUnauthorized
import com.folderspan.pro.core.common.sanitizeUserInput
import com.folderspan.pro.core.datastore.AuthSession
import com.folderspan.pro.core.datastore.SessionManager
import com.folderspan.pro.core.ui.components.AuthStatusTone
import com.folderspan.pro.data.mapper.toProfileUpdateUserViewData
import com.folderspan.pro.data.mapper.toUserProfileViewData
import com.folderspan.pro.domain.model.ProfileAvatarUpload
import com.folderspan.pro.domain.model.UpdateProfileCommand
import com.folderspan.pro.domain.repository.UserRepository
import com.folderspan.pro.domain.usecase.AuthorizedUserRequestExecutor
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import strings.AppStrings

class EditProfileViewModel(
    private val repository: UserRepository,
) : ViewModel() {
    private val executor = AuthorizedUserRequestExecutor(repository::refreshToken)
    private val _state = MutableStateFlow(EditProfileUiState())
    val state: StateFlow<EditProfileUiState> = _state
    private val effects = Channel<ProfileEffect>(capacity = Channel.BUFFERED)
    val effect = effects.receiveAsFlow()
    private var session: AuthSession? = null

    fun onSessionChanged(session: AuthSession?) {
        this.session = session
        refresh()
    }

    fun refresh() {
        viewModelScope.launch {
            _state.update { it.copy(isLoading = true, pageErrorMessage = null) }
            val activeSession = SessionManager.currentSession() ?: session
            when (
                val result = executor.execute(
                    session = activeSession,
                    actionLabel = AppStrings.ui_load_data_editing_page,
                    onUnauthorized = ::emitLoggedOut,
                ) { token ->
                    repository.me(token)
                }
            ) {
                is ApiResult.Success -> {
                    val parsedProfile = result.data.toUserProfileViewData()
                    if (parsedProfile == null) {
                        _state.update { it.copy(pageErrorMessage = AppStrings.ui_data_cannot_displayed_moment_please_try_again_later) }
                    } else {
                        syncUserProfileToSession(parsedProfile, activeSession)
                        _state.update {
                            it.copy(
                                profile = parsedProfile,
                                name = parsedProfile.name,
                                signature = parsedProfile.signature.orEmpty(),
                                pendingAvatar = null,
                                avatarFeedback = null,
                                canRetryAvatarAction = false,
                            )
                        }
                    }
                }

                is ApiResult.Failure -> {
                    if (!result.isUnauthorized()) {
                        _state.update { it.copy(pageErrorMessage = result.message) }
                    }
                }
            }
            _state.update { it.copy(isLoading = false) }
        }
    }

    fun onNameChange(value: String) {
        if (
            _state.value.profile?.profileEditable != true ||
            _state.value.isSubmitting ||
            _state.value.isAvatarSubmitting
        ) return
        _state.update {
            it.copy(
                name = sanitizeUserInput(value),
                feedback = null,
                nameFieldError = null,
            )
        }
    }

    fun onSignatureChange(value: String) {
        if (
            _state.value.profile?.profileEditable != true ||
            _state.value.isSubmitting ||
            _state.value.isAvatarSubmitting
        ) return
        _state.update { it.copy(signature = sanitizeUserInput(value), feedback = null) }
    }

    fun onAvatarPictureOutcome(outcome: AvatarPictureOutcome) {
        if (
            _state.value.profile?.profileEditable != true ||
            _state.value.isAvatarSubmitting ||
            _state.value.isSubmitting
        ) return
        when (outcome) {
            AvatarPictureOutcome.Cancelled -> Unit
            is AvatarPictureOutcome.Failed -> {
                _state.update {
                    it.copy(
                        avatarFeedback = SectionFeedback(
                            tone = AuthStatusTone.Error,
                            text = outcome.message,
                        ),
                        canRetryAvatarAction = false,
                    )
                }
            }
            is AvatarPictureOutcome.Delivered -> {
                _state.update {
                    it.copy(
                        pendingAvatar = outcome.picture,
                        avatarFeedback = SectionFeedback(
                            tone = AuthStatusTone.Info,
                            text = AppStrings.ui_profile_avatar_staged,
                        ),
                        canRetryAvatarAction = false,
                        feedback = null,
                    )
                }
            }
        }
    }

    fun requestAvatarRemoval() {
        val current = _state.value
        if (
            current.profile?.profileEditable != true ||
            current.isAvatarSubmitting ||
            current.isSubmitting ||
            current.profile.avatar.isNullOrBlank()
        ) return
        _state.update { it.copy(isAvatarRemovalConfirmationVisible = true) }
    }

    fun declineAvatarRemoval() {
        _state.update { it.copy(isAvatarRemovalConfirmationVisible = false) }
    }

    fun confirmAvatarRemoval() {
        if (_state.value.profile?.profileEditable != true) return
        _state.update { it.copy(isAvatarRemovalConfirmationVisible = false) }
        startAvatarRemoval()
    }

    fun retryAvatarAction() {
        if (!_state.value.canRetryAvatarAction) return
        startAvatarRemoval()
    }

    fun submit() {
        val current = _state.value
        val publishedProfile = current.profile ?: return
        if (!publishedProfile.profileEditable || current.isSubmitting || current.isAvatarSubmitting) return
        val trimmedName = current.name.trim()
        if (trimmedName.isBlank()) {
            _state.update { it.copy(nameFieldError = AppStrings.ui_please_enter_nickname) }
            return
        }
        val trimmedSignature = current.signature.trim()
        val command = UpdateProfileCommand(
            name = trimmedName.takeIf { it != publishedProfile.name },
            signature = trimmedSignature.takeIf { it != publishedProfile.signature.orEmpty() },
            avatar = current.pendingAvatar?.let { picture ->
                ProfileAvatarUpload(
                    bytes = picture.bytes,
                    contentType = picture.contentType,
                )
            },
        )
        if (command.name == null && command.signature == null && command.avatar == null) {
            _state.update {
                it.copy(
                    feedback = SectionFeedback(
                        tone = AuthStatusTone.Info,
                        text = AppStrings.ui_profile_no_changes,
                    ),
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
                    actionLabel = AppStrings.ui_update_user_profile,
                    onUnauthorized = ::emitLoggedOut,
                ) { token ->
                    repository.updateProfile(
                        command = command,
                        token = token,
                    )
                }
            ) {
                is ApiResult.Success -> {
                    val updatedProfile = result.data.toProfileUpdateUserViewData()
                        ?: publishedProfile.copy(profileEditable = false)
                    syncUserProfileToSession(updatedProfile, activeSession)
                    _state.update {
                        it.copy(
                            profile = updatedProfile,
                            name = updatedProfile.name,
                            signature = updatedProfile.signature.orEmpty(),
                            pendingAvatar = null,
                            avatarFeedback = null,
                            canRetryAvatarAction = false,
                            feedback = SectionFeedback(
                                tone = AuthStatusTone.Success,
                                text = AppStrings.ui_profile_review_submitted,
                            ),
                        )
                    }
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

    private fun startAvatarRemoval() {
        val current = _state.value
        if (current.isAvatarSubmitting || current.isSubmitting) return
        _state.update {
            it.copy(
                isAvatarSubmitting = true,
                isAvatarRemovalConfirmationVisible = false,
                avatarFeedback = null,
                canRetryAvatarAction = false,
            )
        }
        viewModelScope.launch {
            val activeSession = SessionManager.currentSession() ?: session
            when (
                val result = executor.execute(
                    session = activeSession,
                    actionLabel = AppStrings.ui_profile_avatar_remove,
                    onUnauthorized = ::emitLoggedOut,
                ) { token ->
                    repository.removeAvatar(token)
                }
            ) {
                is ApiResult.Success -> {
                    val updatedProfile = result.data.toUserProfileViewData()
                    if (updatedProfile == null) {
                        _state.update {
                            it.copy(
                                avatarFeedback = SectionFeedback(
                                    tone = AuthStatusTone.Error,
                                    text = AppStrings.ui_profile_avatar_remove_failed,
                                ),
                                canRetryAvatarAction = true,
                            )
                        }
                    } else {
                        syncUserProfileToSession(updatedProfile, activeSession)
                        _state.update {
                            it.copy(
                                profile = updatedProfile,
                                avatarFeedback = SectionFeedback(
                                    tone = AuthStatusTone.Success,
                                    text = AppStrings.ui_profile_avatar_remove_success,
                                ),
                                canRetryAvatarAction = false,
                            )
                        }
                    }
                }
                is ApiResult.Failure -> {
                    if (result.isUnauthorized()) {
                        _state.update { it.copy(canRetryAvatarAction = false) }
                    } else {
                        _state.update {
                            it.copy(
                                avatarFeedback = SectionFeedback(
                                    tone = AuthStatusTone.Error,
                                    text = result.message,
                                ),
                                canRetryAvatarAction = true,
                            )
                        }
                    }
                }
            }
            _state.update { it.copy(isAvatarSubmitting = false) }
        }
    }

    private fun emitLoggedOut() {
        viewModelScope.launch {
            effects.send(ProfileEffect.LoggedOut)
        }
    }
}
