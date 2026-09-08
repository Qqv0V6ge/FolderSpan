package com.folderspan.pro.presentation.screen.sync

import strings.AppStrings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.folderspan.pro.core.datastore.AuthSession
import com.folderspan.pro.core.datastore.SessionManager
import com.folderspan.pro.core.ui.components.AuthStatusTone
import com.folderspan.pro.domain.usecase.DeviceSettingsSyncService
import com.folderspan.pro.domain.usecase.ManualSyncCategory
import com.folderspan.pro.presentation.screen.profile.SectionFeedback
import com.russhwolf.settings.Settings
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class ManualDataSyncViewModel(
    private val settings: Settings,
    private val deviceSettingsSyncService: DeviceSettingsSyncService,
) : ViewModel() {
    private val _state = MutableStateFlow(
        ManualDataSyncUiState(
            selectedCategories = settings.readManualDataSyncSelectedCategories(),
        ),
    )
    val state: StateFlow<ManualDataSyncUiState> = _state
    private var session: AuthSession? = null

    fun onSessionChanged(session: AuthSession?) {
        this.session = session
    }

    fun toggleCategory(category: ManualSyncCategory) {
        if (_state.value.isSyncing) return
        _state.update { current ->
            val selected = current.selectedCategories
            val nextSelected = if (category in selected) {
                selected - category
            } else {
                selected + category
            }
            settings.writeManualDataSyncSelectedCategories(nextSelected)
            current.copy(
                selectedCategories = nextSelected,
                feedback = null,
            )
        }
    }

    fun syncNow() {
        val selected = _state.value.selectedCategories
        if (_state.value.isSyncing || selected.isEmpty()) return
        val token = (SessionManager.currentSession() ?: session)
            ?.accessToken
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
        if (token == null) {
            _state.update {
                it.copy(
                    feedback = SectionFeedback(
                        tone = AuthStatusTone.Error,
                        text = AppStrings.ui_please_log_first_then_synchronize,
                    ),
                )
            }
            return
        }

        viewModelScope.launch {
            _state.update { it.copy(isSyncing = true, feedback = null) }
            val result = runCatching {
                deviceSettingsSyncService.manualSync(settings, token, selected)
            }
            _state.update { current ->
                current.copy(
                    isSyncing = false,
                    feedback = result.fold(
                        onSuccess = { syncResult ->
                            if (syncResult.failed.isEmpty()) {
                                SectionFeedback(
                                    tone = AuthStatusTone.Success,
                                    text = AppStrings.pro_sync_completed,
                                )
                            } else {
                                SectionFeedback(
                                    tone = AuthStatusTone.Error,
                                    text = AppStrings.ui_partial_sync_failed_arg0.format(
                                        arg0 = syncResult.failed.keys.joinToString("、") { item -> item.label },
                                    ),
                                )
                            }
                        },
                        onFailure = { throwable ->
                            SectionFeedback(
                                tone = AuthStatusTone.Error,
                                text = AppStrings.ui_sync_failed_please_try_again_later,
                            )
                        },
                    ),
                )
            }
        }
    }
}

data class ManualDataSyncUiState(
    val selectedCategories: Set<ManualSyncCategory> = emptySet(),
    val isSyncing: Boolean = false,
    val feedback: SectionFeedback? = null,
)

data class ManualDataSyncCategoryItem(
    val category: ManualSyncCategory,
    val title: String,
    val subtitle: String,
) {
    companion object {
        fun defaultItems(): List<ManualDataSyncCategoryItem> = listOf(
            ManualDataSyncCategoryItem(ManualSyncCategory.Bookmarks, AppStrings.ui_bookmark_label, AppStrings.ui_sync_sidebar_bookmarks),
            ManualDataSyncCategoryItem(ManualSyncCategory.Favorites, AppStrings.ui_collection, AppStrings.ui_synchronize_file_collection_list),
            ManualDataSyncCategoryItem(
                ManualSyncCategory.EditorSearchHistory,
                AppStrings.ui_editor_search_history,
                AppStrings.ui_sync_recent_searches_large_file_editors,
            ),
            ManualDataSyncCategoryItem(ManualSyncCategory.Devices, AppStrings.ui_equipment, AppStrings.ui_synchronize_saved_device_connection_configurations),
            ManualDataSyncCategoryItem(ManualSyncCategory.Roles, AppStrings.ui_role, AppStrings.ui_synchronize_device_access_roles_permissions),
            ManualDataSyncCategoryItem(ManualSyncCategory.Settings, AppStrings.settings_title, AppStrings.ui_sync_app_settings_that_can_shared_across_devices),
            ManualDataSyncCategoryItem(ManualSyncCategory.Networks, AppStrings.ui_network_disk, AppStrings.ui_synchronize_network_storage_configuration),
            ManualDataSyncCategoryItem(ManualSyncCategory.WebRtc, AppStrings.ui_remote_connection, AppStrings.ui_synchronize_room_configurations_remote_connections),
            ManualDataSyncCategoryItem(ManualSyncCategory.SyncTasks, AppStrings.ui_sync_tasks, AppStrings.ui_synchronize_automatic_synchronization_task_configuration),
        )
    }
}

val ManualSyncCategory.label: String
    get() = when (this) {
        ManualSyncCategory.Settings -> AppStrings.settings_title
        ManualSyncCategory.Bookmarks -> AppStrings.ui_bookmark_label
        ManualSyncCategory.Favorites -> AppStrings.ui_collection
        ManualSyncCategory.Devices -> AppStrings.ui_equipment
        ManualSyncCategory.Roles -> AppStrings.ui_role
        ManualSyncCategory.Networks -> AppStrings.ui_network_disk
        ManualSyncCategory.WebRtc -> AppStrings.ui_remote_connection
        ManualSyncCategory.SyncTasks -> AppStrings.ui_sync_tasks
        ManualSyncCategory.EditorSearchHistory -> AppStrings.ui_editor_search_history
    }
