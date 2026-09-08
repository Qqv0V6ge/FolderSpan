package com.folderspan.pro.presentation.screen.profile

import androidx.compose.runtime.*
import androidx.lifecycle.viewmodel.compose.viewModel
import com.folderspan.pro.core.datastore.SessionManager
import com.folderspan.pro.core.network.runtimeDeviceIdentity
import com.folderspan.pro.domain.repository.SettingRepository
import com.folderspan.pro.domain.repository.UserRepository
import com.folderspan.pro.domain.usecase.DeviceSettingsSyncService
import com.russhwolf.settings.Settings

@Composable
fun UserProfileRoute(
    viewModelKey: String? = null,
    repository: UserRepository,
    onNavigateBack: () -> Unit,
    onNavigateToLoginDevices: () -> Unit,
    onNavigateToEditProfile: () -> Unit,
    onNavigateToChangePassword: () -> Unit,
    onNavigateToPersonalSettings: () -> Unit,
    onLogout: () -> Unit,
) {
    val viewModel = viewModel(key = viewModelKey) { UserProfileViewModel(repository) }
    val state by viewModel.state.collectAsState()
    val session by SessionManager.session.collectAsState()
    val logout = {
        SessionManager.clear()
        onLogout()
    }

    LaunchedEffect(session?.accessToken) {
        viewModel.onSessionChanged(session)
    }

    LaunchedEffect(viewModel) {
        viewModel.effect.collect { effect ->
            when (effect) {
                ProfileEffect.LoggedOut -> logout()
            }
        }
    }

    UserProfilePage(
        state = state,
        onNavigateBack = onNavigateBack,
        onNavigateToLoginDevices = onNavigateToLoginDevices,
        onNavigateToEditProfile = onNavigateToEditProfile,
        onNavigateToChangePassword = onNavigateToChangePassword,
        onNavigateToPersonalSettings = onNavigateToPersonalSettings,
        onRefreshAll = { viewModel.refreshProfile() },
        onWithdrawProfileReview = viewModel::withdrawProfileReview,
        onLogout = logout,
    )
}

@Composable
fun LoginDevicesRoute(
    viewModelKey: String? = null,
    repository: UserRepository,
    onNavigateBack: () -> Unit,
    onLogout: () -> Unit,
) {
    val viewModel = viewModel(key = viewModelKey) { UserProfileViewModel(repository) }
    val state by viewModel.state.collectAsState()
    val session by SessionManager.session.collectAsState()
    val currentDeviceKey = remember { runtimeDeviceIdentity().key }
    val logout = {
        SessionManager.clear()
        onLogout()
    }

    LaunchedEffect(session?.accessToken) {
        viewModel.onSessionChanged(
            session = session,
            loadProfile = false,
        )
    }

    LaunchedEffect(viewModel, session?.accessToken) {
        viewModel.refreshDevices(forceRefresh = false)
    }

    LaunchedEffect(viewModel) {
        viewModel.effect.collect { effect ->
            when (effect) {
                ProfileEffect.LoggedOut -> logout()
            }
        }
    }

    LoginDevicesPage(
        state = state,
        currentDeviceKey = currentDeviceKey,
        onNavigateBack = onNavigateBack,
        onRefresh = { viewModel.refreshDevices() },
        onRemoveDevice = viewModel::removeDevice,
        onLogout = logout,
    )
}

@Composable
fun PersonalSettingsRoute(
    viewModelKey: String? = null,
    repository: SettingRepository,
    userRepository: UserRepository,
    settings: Settings,
    deviceSettingsSyncService: DeviceSettingsSyncService,
    onNavigateBack: () -> Unit,
    onLogout: () -> Unit,
) {
    val viewModel = viewModel(key = viewModelKey) {
        PersonalSettingsViewModel(
            repository = repository,
            userRepository = userRepository,
            onUnauthorized = {
                SessionManager.clear()
                onLogout()
            },
            settings = settings,
            deviceSettingsSyncService = deviceSettingsSyncService,
        )
    }
    val state by viewModel.state.collectAsState()
    val session by SessionManager.session.collectAsState()
    val logout = {
        SessionManager.clear()
        onLogout()
    }

    LaunchedEffect(session?.accessToken) {
        viewModel.onSessionChanged(session)
    }

    LaunchedEffect(viewModel, session?.accessToken) {
        viewModel.load(forceRefresh = false)
    }

    PersonalSettingsPage(
        state = state,
        onNavigateBack = onNavigateBack,
        onRefresh = { viewModel.load(forceRefresh = true) },
        onUseTarget = viewModel::useTarget,
        onCancelUseTarget = viewModel::cancelUseTarget,
        onCloneTarget = viewModel::cloneTarget,
        onDeleteTarget = viewModel::deleteTarget,
        onLogout = logout,
    )
}

@Composable
fun EditProfileRoute(
    viewModelKey: String? = null,
    repository: UserRepository,
    onNavigateBack: () -> Unit,
    onLogout: () -> Unit,
) {
    val viewModel = viewModel(key = viewModelKey) { EditProfileViewModel(repository) }
    val state by viewModel.state.collectAsState()
    val session by SessionManager.session.collectAsState()
    val logout = {
        SessionManager.clear()
        onLogout()
    }

    LaunchedEffect(session?.accessToken) {
        viewModel.onSessionChanged(session)
    }

    LaunchedEffect(viewModel) {
        viewModel.effect.collect { effect ->
            when (effect) {
                ProfileEffect.LoggedOut -> logout()
            }
        }
    }

    EditProfilePage(
        state = state,
        onNavigateBack = onNavigateBack,
        onRefresh = viewModel::refresh,
        onNameChange = viewModel::onNameChange,
        onAvatarPictureOutcome = viewModel::onAvatarPictureOutcome,
        onRequestAvatarRemoval = viewModel::requestAvatarRemoval,
        onConfirmAvatarRemoval = viewModel::confirmAvatarRemoval,
        onDeclineAvatarRemoval = viewModel::declineAvatarRemoval,
        onRetryAvatarAction = viewModel::retryAvatarAction,
        onSignatureChange = viewModel::onSignatureChange,
        onSubmit = viewModel::submit,
        onLogout = logout,
    )
}

@Composable
fun ChangePasswordRoute(
    viewModelKey: String? = null,
    repository: UserRepository,
    onNavigateBack: () -> Unit,
    onLogout: () -> Unit,
) {
    val viewModel = viewModel(key = viewModelKey) { ChangePasswordViewModel(repository) }
    val state by viewModel.state.collectAsState()
    val session by SessionManager.session.collectAsState()
    val logout = {
        SessionManager.clear()
        onLogout()
    }

    LaunchedEffect(session?.accessToken) {
        viewModel.onSessionChanged(session)
    }

    LaunchedEffect(viewModel) {
        viewModel.effect.collect { effect ->
            when (effect) {
                ProfileEffect.LoggedOut -> logout()
            }
        }
    }

    ChangePasswordPage(
        state = state,
        onNavigateBack = onNavigateBack,
        onOldPasswordChange = viewModel::onOldPasswordChange,
        onNewPasswordChange = viewModel::onNewPasswordChange,
        onConfirmPasswordChange = viewModel::onConfirmPasswordChange,
        onOldPasswordVisibilityToggle = viewModel::toggleOldPasswordVisibility,
        onNewPasswordVisibilityToggle = viewModel::toggleNewPasswordVisibility,
        onConfirmPasswordVisibilityToggle = viewModel::toggleConfirmPasswordVisibility,
        onSubmit = viewModel::submit,
        onLogout = logout,
    )
}
