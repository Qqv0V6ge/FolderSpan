package com.folderspan.pro.presentation.screen.sync

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.lifecycle.viewmodel.compose.viewModel
import com.folderspan.pro.core.datastore.SessionManager
import com.folderspan.pro.domain.usecase.DeviceSettingsSyncService
import com.russhwolf.settings.Settings

@Composable
fun ManualDataSyncRoute(
    viewModelKey: String? = null,
    settings: Settings,
    deviceSettingsSyncService: DeviceSettingsSyncService,
    onNavigateBack: () -> Unit,
) {
    val viewModel = viewModel(key = viewModelKey) {
        ManualDataSyncViewModel(
            settings = settings,
            deviceSettingsSyncService = deviceSettingsSyncService,
        )
    }
    val state by viewModel.state.collectAsState()
    val session by SessionManager.session.collectAsState()

    LaunchedEffect(session?.accessToken) {
        viewModel.onSessionChanged(session)
    }

    ManualDataSyncPage(
        state = state,
        onNavigateBack = onNavigateBack,
        onToggleCategory = viewModel::toggleCategory,
        onSync = viewModel::syncNow,
    )
}
