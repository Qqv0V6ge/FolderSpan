package com.folderspan.pro.presentation.screen.profile

import strings.AppStrings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.folderspan.createSettings
import com.folderspan.pro.core.common.ApiResult
import com.folderspan.pro.core.common.isUnauthorized
import com.folderspan.pro.core.datastore.AuthSession
import com.folderspan.pro.core.datastore.SessionManager
import com.folderspan.pro.core.network.*
import com.folderspan.pro.core.ui.components.AuthStatusTone
import com.folderspan.pro.data.mapper.toSettingTargetsPayload
import com.folderspan.pro.domain.model.CloneSettingTargetCommand
import com.folderspan.pro.domain.repository.SettingRepository
import com.folderspan.pro.domain.repository.UserRepository
import com.folderspan.pro.domain.usecase.AuthorizedUserRequestExecutor
import com.folderspan.pro.domain.usecase.DeviceSettingsSyncService
import com.russhwolf.settings.Settings
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class PersonalSettingsViewModel(
    private val repository: SettingRepository,
    userRepository: UserRepository,
    private val onUnauthorized: () -> Unit,
    private val deviceIdentityProvider: () -> DeviceIdentity = ::runtimeDeviceIdentity,
    private val settings: Settings = createSettings(),
    private val deviceSettingsSyncService: DeviceSettingsSyncService = DeviceSettingsSyncService(
        repository = repository,
        deviceIdentityProvider = deviceIdentityProvider,
    ),
) : ViewModel() {
    private val executor = AuthorizedUserRequestExecutor(userRepository::refreshToken)
    private val _state = MutableStateFlow(PersonalSettingsUiState())
    val state: StateFlow<PersonalSettingsUiState> = _state
    private var hasLoaded = false
    private var session: AuthSession? = null

    init {
        updateActiveRequestHeaderDeviceKey()
    }

    fun onSessionChanged(session: AuthSession?) {
        this.session = session
    }

    fun load(forceRefresh: Boolean = false) {
        if (_state.value.isLoading) return
        if (!forceRefresh && hasLoaded) return

        viewModelScope.launch {
            _state.update { it.copy(isLoading = true, pageErrorMessage = null) }
            val activeSession = SessionManager.currentSession() ?: session
            when (
                val result = executor.execute(
                    session = activeSession,
                    actionLabel = AppStrings.ui_get_personalization_goals,
                    onUnauthorized = onUnauthorized,
                ) { token ->
                    repository.listDeviceTargets(token)
                }
            ) {
                is ApiResult.Success -> {
                    val payload = result.data.toSettingTargetsPayload()
                    if (payload == null) {
                        _state.update {
                            it.copy(
                                targets = emptyList(),
                                targetTotal = 0,
                                pageErrorMessage = AppStrings.ui_unable_display_personalized_settings_this_time_please_try_again,
                            )
                        }
                    } else {
                        _state.update {
                            it.copy(
                                targets = payload.targets,
                                targetTotal = payload.total,
                            )
                        }
                        hasLoaded = true
                    }
                }

                is ApiResult.Failure -> {
                    if (!result.isUnauthorized()) {
                        if (result.message.isMissingDeviceTargetsMessage()) {
                            _state.update {
                                it.copy(
                                    targets = emptyList(),
                                    targetTotal = 0,
                                    pageErrorMessage = null,
                                )
                            }
                            hasLoaded = true
                        } else {
                            _state.update {
                                it.copy(pageErrorMessage = result.message)
                            }
                        }
                    }
                }
            }
            _state.update { it.copy(isLoading = false) }
        }
    }

    fun cloneTarget(source: SettingTargetViewData) {
        if (_state.value.isTargetActionSubmitting) return

        val deviceIdentity = deviceIdentityProvider()
        val command = CloneSettingTargetCommand(
            type = source.type.normalizedTargetType(),
            sourceTargetId = source.id,
            targetId = deviceIdentity.key.trim(),
            name = deviceIdentity.name.trim(),
            description = null,
            subType = deviceIdentity.type.trim(),
        )
        viewModelScope.launch {
            _state.update { it.copy(isTargetActionSubmitting = true, feedback = null) }
            val activeSession = SessionManager.currentSession() ?: session
            when (
                val result = executor.execute(
                    session = activeSession,
                    actionLabel = AppStrings.ui_copy_personalization_target,
                    onUnauthorized = onUnauthorized,
                ) { token ->
                    repository.cloneTargetSettings(command, token)
                }
            ) {
                is ApiResult.Success -> {
                    _state.update {
                        it.copy(
                            feedback = SectionFeedback(
                                tone = AuthStatusTone.Success,
                                text = AppStrings.profile_value_copied_arg0.format(arg0 = source.name),
                            ),
                        )
                    }
                    load(forceRefresh = true)
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
            _state.update { it.copy(isTargetActionSubmitting = false) }
        }
    }

    fun useTarget(source: SettingTargetViewData) {
        if (_state.value.isTargetActionSubmitting) return

        val deviceKey = source.requestHeaderDeviceKey()
        if (deviceKey == null) {
            _state.update {
                it.copy(
                    feedback = SectionFeedback(
                        tone = AuthStatusTone.Error,
                        text = AppStrings.ui_this_settings_snapshot_cannot_be_used_try_another_one,
                    ),
                )
            }
            return
        }

        val deviceIdentity = deviceIdentityProvider()
        settings.putString(
            KEY_PRO_API_HEADER_OVERRIDE,
            encodeProApiHeaderOverride(
                ProApiHeaderOverride(
                    host = PRO_API_HOST_HEADER_VALUE,
                    deviceType = source.subType.normalizedOptionalText() ?: deviceIdentity.type.trim(),
                    deviceKey = deviceKey,
                    deviceName = source.name.trim().takeIf { it.isNotEmpty() },
                ),
            ),
        )
        updateActiveRequestHeaderDeviceKey()
        viewModelScope.launch {
            _state.update { it.copy(isTargetActionSubmitting = true, feedback = null) }
            val activeSession = SessionManager.currentSession() ?: session
            when (
                val result = executor.execute(
                    session = activeSession,
                    actionLabel = AppStrings.ui_use_personalization_set_goals,
                    onUnauthorized = onUnauthorized,
                ) { token ->
                    deviceSettingsSyncService.pullAllRemoteSettingsForTarget(
                        settings = settings,
                        targetId = deviceKey,
                        token = token,
                    )
                }
            ) {
                is ApiResult.Success -> {
                    _state.update {
                        it.copy(
                            feedback = SectionFeedback(
                                tone = AuthStatusTone.Success,
                                text = AppStrings.ui_switched_setting_arg0.format(arg0 = source.name),
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
            _state.update { it.copy(isTargetActionSubmitting = false) }
        }
    }

    fun cancelUseTarget() {
        settings.remove(KEY_PRO_API_HEADER_OVERRIDE)
        updateActiveRequestHeaderDeviceKey()
        _state.update {
            it.copy(
                feedback = SectionFeedback(
                    tone = AuthStatusTone.Success,
                    text = AppStrings.ui_this_device_has_been_restored_its_original_settings,
                ),
            )
        }
    }

    private fun updateActiveRequestHeaderDeviceKey() {
        _state.update {
            it.copy(
                activeRequestHeaderDeviceKey = decodeProApiHeaderOverride(
                    settings.getString(KEY_PRO_API_HEADER_OVERRIDE, ""),
                )?.deviceKey,
            )
        }
    }

    fun deleteTarget(target: SettingTargetViewData) {
        if (_state.value.isTargetActionSubmitting) return

        viewModelScope.launch {
            _state.update { it.copy(isTargetActionSubmitting = true, feedback = null) }
            val activeSession = SessionManager.currentSession() ?: session
            val type = target.type.normalizedTargetType()
            when (
                val result = executor.execute(
                    session = activeSession,
                    actionLabel = AppStrings.ui_delete_personalization_goal,
                    onUnauthorized = onUnauthorized,
                ) { token ->
                    repository.deleteTargetSettings(
                        type = type,
                        targetId = target.id,
                        token = token,
                    )
                }
            ) {
                is ApiResult.Success -> {
                    _state.update {
                        it.copy(
                            feedback = SectionFeedback(
                                tone = AuthStatusTone.Success,
                                text = AppStrings.ui_arg0_has_been_deleted.format(arg0 = target.name),
                            ),
                        )
                    }
                    load(forceRefresh = true)
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
            _state.update { it.copy(isTargetActionSubmitting = false) }
        }
    }
}

private fun String.isMissingDeviceTargetsMessage(): Boolean =
    contains(AppStrings.ui_device_not_found) ||
        contains(AppStrings.legacy_profile_device_not_found_marker) ||
        contains(AppStrings.legacy_profile_no_device_found_marker)

private fun String?.normalizedTargetType(): String =
    normalizedOptionalText() ?: "device"

private fun String?.normalizedOptionalText(): String? =
    this?.trim()?.takeIf { it.isNotEmpty() }
