package com.folderspan.pro.presentation.screen.profile

import strings.AppStrings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.folderspan.pro.core.common.ApiResult
import com.folderspan.pro.core.common.isUnauthorized
import com.folderspan.pro.core.common.normalizedAccessTokenOrNull
import com.folderspan.pro.core.datastore.AuthSession
import com.folderspan.pro.core.datastore.SessionManager
import com.folderspan.pro.core.datastore.withUserProfileSnapshot
import com.folderspan.pro.core.ui.components.AuthStatusTone
import com.folderspan.pro.data.mapper.displaySuccessMessageOrNull
import com.folderspan.pro.data.mapper.toProfileUpdateUserViewData
import com.folderspan.pro.data.mapper.toUserDevicesPayload
import com.folderspan.pro.data.mapper.toUserProfileViewData
import com.folderspan.pro.domain.repository.UserRepository
import com.folderspan.pro.domain.usecase.AuthorizedUserRequestExecutor
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class UserProfileViewModel(
    private val repository: UserRepository,
) : ViewModel() {
    private val executor = AuthorizedUserRequestExecutor(repository::refreshToken)
    private val _state = MutableStateFlow(UserProfileUiState())
    val state: StateFlow<UserProfileUiState> = _state
    private val effects = Channel<ProfileEffect>(capacity = Channel.BUFFERED)
    val effect = effects.receiveAsFlow()
    private var session: AuthSession? = null
    private var loadedProfileAccessToken: String? = null
    private var loadedDevicesAccessToken: String? = null

    fun onSessionChanged(
        session: AuthSession?,
        loadProfile: Boolean = true,
    ) {
        this.session = session
        if (loadProfile && shouldLoadProfileForAccessToken(session?.accessToken)) {
            refreshProfile(forceRefresh = false)
        }
    }

    fun refreshProfile(forceRefresh: Boolean = true) {
        val activeSession = SessionManager.currentSession() ?: session
        if (_state.value.isRefreshingPage || _state.value.isWithdrawingProfileReview) return
        if (!forceRefresh && !shouldLoadProfileForAccessToken(activeSession?.accessToken)) return

        viewModelScope.launch {
            _state.update {
                it.copy(
                    isRefreshingPage = true,
                    pageErrorMessage = null,
                    profileFeedback = null,
                )
            }
            loadCachedProfile(activeSession)
            var profileRefreshSucceeded = false

            when (
                val profileResult = executor.execute(
                    session = activeSession,
                    actionLabel = AppStrings.ui_get_my_information,
                    onUnauthorized = ::emitLoggedOut,
                ) { token ->
                    repository.me(token)
                }
            ) {
                is ApiResult.Success -> {
                    val parsedProfile = profileResult.data.toUserProfileViewData()
                    if (parsedProfile == null) {
                        _state.update {
                            it.copy(
                                pageErrorMessage = AppStrings.ui_data_cannot_displayed_moment_please_try_again_later,
                            )
                        }
                    } else {
                        _state.update { it.copy(profile = parsedProfile) }
                        syncUserProfileToSession(parsedProfile, activeSession)
                        profileRefreshSucceeded = true
                    }
                }

                is ApiResult.Failure -> {
                    if (!profileResult.isUnauthorized()) {
                        _state.update { it.copy(pageErrorMessage = profileResult.message) }
                    }
                    _state.update { it.copy(isRefreshingPage = false) }
                    return@launch
                }
            }

            if (profileRefreshSucceeded) {
                loadedProfileAccessToken = (SessionManager.currentSession() ?: activeSession)?.accessToken
                    .normalizedAccessTokenOrNull()
            }
            _state.update { it.copy(isRefreshingPage = false) }
        }
    }

    fun refreshDevices(forceRefresh: Boolean = true) {
        val activeSession = SessionManager.currentSession() ?: session
        if (_state.value.isRefreshingPage) return
        if (!forceRefresh && !shouldLoadDevicesForAccessToken(activeSession?.accessToken)) return

        viewModelScope.launch {
            _state.update { it.copy(isRefreshingPage = true, pageErrorMessage = null) }
            var devicesRefreshSucceeded = false

            when (
                val devicesResult = executor.execute(
                    session = activeSession,
                    actionLabel = AppStrings.ui_get_login_device,
                    onUnauthorized = ::emitLoggedOut,
                ) { token ->
                    repository.listDevices(token)
                }
            ) {
                is ApiResult.Success -> {
                    val payload = devicesResult.data.toUserDevicesPayload()
                    if (payload == null) {
                        _state.update {
                            it.copy(
                                pageErrorMessage = AppStrings.ui_login_device_cannot_displayed_moment_please_try_again_later,
                            )
                        }
                    } else {
                        _state.update {
                            it.copy(
                                devices = payload.devices,
                                deviceTotal = payload.total,
                            )
                        }
                        devicesRefreshSucceeded = true
                    }
                }

                is ApiResult.Failure -> {
                    if (!devicesResult.isUnauthorized()) {
                        _state.update {
                            it.copy(
                                pageErrorMessage = devicesResult.message,
                            )
                        }
                    }
                }
            }

            if (devicesRefreshSucceeded) {
                loadedDevicesAccessToken = (SessionManager.currentSession() ?: activeSession)?.accessToken
                    .normalizedAccessTokenOrNull()
            }
            _state.update { it.copy(isRefreshingPage = false) }
        }
    }

    fun removeDevice(deviceId: Long, isCurrentDevice: Boolean = false) {
        if (deviceId in _state.value.removingDeviceIds) return

        viewModelScope.launch {
            _state.update {
                it.copy(
                    removingDeviceIds = it.removingDeviceIds + deviceId,
                    devicesFeedback = null,
                )
            }
            val activeSession = SessionManager.currentSession() ?: session
            when (
                val result = executor.execute(
                    session = activeSession,
                    actionLabel = AppStrings.profile_remove_device_action,
                    onUnauthorized = ::emitLoggedOut,
                ) { token ->
                    repository.deleteDevice(deviceId, token)
                }
            ) {
                is ApiResult.Success -> {
                    _state.update {
                        val devices = it.devices.filterNot { device -> device.id == deviceId }
                        it.copy(
                            devices = devices,
                            deviceTotal = (it.deviceTotal - 1).coerceAtLeast(devices.size),
                            devicesFeedback = SectionFeedback(
                                tone = AuthStatusTone.Success,
                                text = result.data.displaySuccessMessageOrNull() ?: AppStrings.ui_device_removed,
                            ),
                        )
                    }
                    if (isCurrentDevice) {
                        SessionManager.clear()
                        emitLoggedOut()
                    }
                }

                is ApiResult.Failure -> {
                    if (!result.isUnauthorized()) {
                        _state.update {
                            it.copy(
                                devicesFeedback = SectionFeedback(
                                    tone = AuthStatusTone.Error,
                                    text = result.message,
                                ),
                            )
                        }
                    }
                }
            }
            _state.update { it.copy(removingDeviceIds = it.removingDeviceIds - deviceId) }
        }
    }

    fun withdrawProfileReview() {
        val current = _state.value
        if (
            current.profile?.profileEditable != false ||
            current.isRefreshingPage ||
            current.isWithdrawingProfileReview
        ) return

        viewModelScope.launch {
            _state.update {
                it.copy(
                    isWithdrawingProfileReview = true,
                    profileFeedback = null,
                )
            }
            val activeSession = SessionManager.currentSession() ?: session
            when (
                val result = executor.execute(
                    session = activeSession,
                    actionLabel = AppStrings.ui_profile_review_withdraw,
                    onUnauthorized = ::emitLoggedOut,
                ) { token ->
                    repository.withdrawProfileReview(token)
                }
            ) {
                is ApiResult.Success -> {
                    val publishedProfile = result.data.toProfileUpdateUserViewData()
                    if (publishedProfile == null) {
                        _state.update {
                            it.copy(
                                profileFeedback = SectionFeedback(
                                    tone = AuthStatusTone.Error,
                                    text = AppStrings.ui_data_cannot_displayed_moment_please_try_again_later,
                                ),
                            )
                        }
                    } else {
                        _state.update {
                            it.copy(
                                profile = publishedProfile,
                                profileFeedback = SectionFeedback(
                                    tone = AuthStatusTone.Success,
                                    text = AppStrings.ui_profile_review_withdraw_success,
                                ),
                            )
                        }
                        syncUserProfileToSession(publishedProfile, activeSession)
                    }
                }

                is ApiResult.Failure -> {
                    if (!result.isUnauthorized()) {
                        _state.update {
                            it.copy(
                                profileFeedback = SectionFeedback(
                                    tone = AuthStatusTone.Error,
                                    text = result.message,
                                ),
                            )
                        }
                    }
                }
            }
            _state.update { it.copy(isWithdrawingProfileReview = false) }
        }
    }

    private fun shouldLoadProfileForAccessToken(accessToken: String?): Boolean {
        val normalizedAccessToken = accessToken.normalizedAccessTokenOrNull() ?: return false
        return loadedProfileAccessToken != normalizedAccessToken || _state.value.profile == null
    }

    private fun shouldLoadDevicesForAccessToken(accessToken: String?): Boolean {
        val normalizedAccessToken = accessToken.normalizedAccessTokenOrNull() ?: return false
        return loadedDevicesAccessToken != normalizedAccessToken
    }

    private suspend fun loadCachedProfile(activeSession: AuthSession?) {
        val token = activeSession?.accessToken
            ?.trim()
            ?.takeIf { it.isNotEmpty() }

        if (token == null) {
            _state.update { it.copy(hasCheckedProfileCache = true) }
            return
        }

        val cachedProfile = when (val cachedResult = repository.cachedMe(token)) {
            is ApiResult.Success -> cachedResult.data.toUserProfileViewData()
            is ApiResult.Failure, null -> null
        }

        _state.update { current ->
            current.copy(
                profile = current.profile ?: cachedProfile,
                hasCheckedProfileCache = true,
            )
        }
    }

    private fun emitLoggedOut() {
        viewModelScope.launch {
            effects.send(ProfileEffect.LoggedOut)
        }
    }
}

internal fun syncUserProfileToSession(
    profile: UserProfileViewData,
    requestSession: AuthSession?,
) {
    val session = SessionManager.currentSession()
        ?.takeIf { it.isSameAccountAs(requestSession) }
        ?: return
    SessionManager.update(
        session.withUserProfileSnapshot(
            name = profile.name,
            email = profile.email,
            avatar = profile.avatar,
        ),
    )
}

private fun AuthSession.isSameAccountAs(other: AuthSession?): Boolean {
    other ?: return false
    if (userUuid != null && other.userUuid != null) return userUuid == other.userUuid
    if (userEmail != null && other.userEmail != null) return userEmail.equals(other.userEmail, ignoreCase = true)
    if (accessToken == other.accessToken) return true
    return refreshToken != null && refreshToken == other.refreshToken
}
