package com.folderspan.pro.presentation.screen.sync

import com.folderspan.pro.core.common.ApiResult
import com.folderspan.pro.core.common.JsonResult
import com.folderspan.pro.core.datastore.AuthSession
import com.folderspan.pro.core.datastore.SessionManager
import com.folderspan.pro.domain.model.CloneSettingTargetCommand
import com.folderspan.pro.domain.model.DeviceSettingEntry
import com.folderspan.pro.domain.repository.SettingRepository
import com.folderspan.pro.domain.usecase.DeviceSettingsSyncService
import com.folderspan.pro.domain.usecase.ManualSyncCategory
import com.folderspan.utils.SettingsUtils
import com.russhwolf.settings.MapSettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.*
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import org.junit.Rule
import org.junit.rules.TestWatcher
import org.junit.runner.Description
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class ManualDataSyncViewModelTest {
    @get:Rule
    val mainDispatcherRule = ManualDataSyncMainDispatcherRule()

    @AfterTest
    fun tearDown() {
        SessionManager.clear()
    }

    @Test
    fun defaultItemsIncludeAllManualSyncCategoriesInDisplayOrder() {
        val items = ManualDataSyncCategoryItem.defaultItems()

        assertEquals(
            listOf(
                ManualSyncCategory.Bookmarks,
                ManualSyncCategory.Favorites,
                ManualSyncCategory.EditorSearchHistory,
                ManualSyncCategory.Devices,
                ManualSyncCategory.Roles,
                ManualSyncCategory.Settings,
                ManualSyncCategory.Networks,
                ManualSyncCategory.WebRtc,
                ManualSyncCategory.SyncTasks,
            ),
            items.map { it.category },
        )
    }

    @Test
    fun defaultStateStartsWithAllSelectedCategoriesWhenNoPreferenceExists() {
        val viewModel = ManualDataSyncViewModel(
            settings = MapSettings(),
            deviceSettingsSyncService = DeviceSettingsSyncService(
                repository = RecordingSettingRepository(),
            ),
        )

        assertEquals(
            ManualDataSyncCategoryItem.defaultItems().map { it.category }.toSet(),
            viewModel.state.value.selectedCategories,
        )
    }

    @Test
    fun persistedSelectionRestoresSelectedCategories() {
        val settings = MapSettings()
        settings.putString(
            SettingsUtils.KEY_MANUAL_DATA_SYNC_SELECTED_CATEGORIES,
            Json.encodeToString(
                listOf(
                    ManualSyncCategory.Bookmarks.name,
                    ManualSyncCategory.Roles.name,
                ),
            ),
        )
        val viewModel = ManualDataSyncViewModel(
            settings = settings,
            deviceSettingsSyncService = DeviceSettingsSyncService(
                repository = RecordingSettingRepository(),
            ),
        )

        assertEquals(
            setOf(ManualSyncCategory.Bookmarks, ManualSyncCategory.Roles),
            viewModel.state.value.selectedCategories,
        )
    }

    @Test
    fun togglingCategoryPersistsSelectedCategoriesWithoutAddingSyncWhitelistEntry() {
        val settings = MapSettings()
        val viewModel = ManualDataSyncViewModel(
            settings = settings,
            deviceSettingsSyncService = DeviceSettingsSyncService(
                repository = RecordingSettingRepository(),
            ),
        )

        viewModel.toggleCategory(ManualSyncCategory.Bookmarks)

        assertEquals(
            ManualDataSyncCategoryItem.defaultItems()
                .map { it.category.name }
                .filterNot { it == ManualSyncCategory.Bookmarks.name }
                .sorted(),
            Json.decodeFromString<List<String>>(
                settings.getString(SettingsUtils.KEY_MANUAL_DATA_SYNC_SELECTED_CATEGORIES, "[]"),
            ),
        )
        assertEquals(null, SettingsUtils.syncableSettingOrNull(SettingsUtils.KEY_MANUAL_DATA_SYNC_SELECTED_CATEGORIES))
    }

    @Test
    fun syncNowWithoutSelectedCategoryDoesNotCallManualSync() = runTest {
        val repository = RecordingSettingRepository()
        val settings = MapSettings()
        settings.putString(SettingsUtils.KEY_MANUAL_DATA_SYNC_SELECTED_CATEGORIES, "[]")
        val viewModel = ManualDataSyncViewModel(
            settings = settings,
            deviceSettingsSyncService = DeviceSettingsSyncService(
                repository = repository,
                deviceIdentityProvider = { com.folderspan.pro.core.network.DeviceIdentity(type = "JVM", key = "device-1", name = "Workstation") },
            ),
        )
        viewModel.onSessionChanged(AuthSession(accessToken = "token-value"))

        viewModel.syncNow()
        advanceUntilIdle()

        assertTrue(repository.calls.isEmpty())
    }

    private class RecordingSettingRepository : SettingRepository {
        val calls = mutableListOf<String>()

        override suspend fun listDeviceTargets(token: String): JsonResult =
            ApiResult.Success(JsonNull)

        override suspend fun cloneTargetSettings(command: CloneSettingTargetCommand, token: String): JsonResult =
            ApiResult.Success(JsonNull)

        override suspend fun deleteTargetSettings(type: String, targetId: String, token: String): JsonResult =
            ApiResult.Success(JsonNull)

        override suspend fun listDeviceSettings(
            targetId: String,
            timestamp: Long,
            token: String,
        ): ApiResult<List<DeviceSettingEntry>> {
            calls += "list"
            return ApiResult.Success(emptyList())
        }

        override suspend fun saveDeviceSetting(
            targetId: String,
            entry: DeviceSettingEntry,
            token: String,
        ): JsonResult {
            calls += "save"
            return ApiResult.Success(JsonNull)
        }
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
class ManualDataSyncMainDispatcherRule(
    private val dispatcher: TestDispatcher = StandardTestDispatcher(),
) : TestWatcher() {
    override fun starting(description: Description) {
        Dispatchers.setMain(dispatcher)
    }

    override fun finished(description: Description) {
        Dispatchers.resetMain()
    }
}
