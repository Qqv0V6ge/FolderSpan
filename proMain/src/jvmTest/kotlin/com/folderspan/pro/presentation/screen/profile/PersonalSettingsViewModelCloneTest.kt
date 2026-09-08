package com.folderspan.pro.presentation.screen.profile

import com.folderspan.pro.core.common.ApiResult
import com.folderspan.pro.core.common.JsonResult
import com.folderspan.pro.core.datastore.AuthSession
import com.folderspan.pro.core.datastore.InMemoryAuthSessionStore
import com.folderspan.pro.core.datastore.SessionManager
import com.folderspan.pro.core.network.DeviceIdentity
import com.folderspan.pro.core.network.KEY_PRO_API_HEADER_OVERRIDE
import com.folderspan.pro.core.network.decodeProApiHeaderOverride
import com.folderspan.pro.domain.model.CloneSettingTargetCommand
import com.folderspan.pro.domain.model.DeviceSettingEntry
import com.folderspan.pro.domain.repository.SettingRepository
import com.folderspan.pro.domain.repository.UserRepository
import com.folderspan.utils.SettingsUtils
import com.russhwolf.settings.MapSettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.*
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Rule
import org.junit.rules.TestWatcher
import org.junit.runner.Description
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import strings.AppStrings

@OptIn(ExperimentalCoroutinesApi::class)
class PersonalSettingsViewModelCloneTest {
    @get:Rule
    val mainDispatcherRule = PersonalSettingsMainDispatcherRule()

    @AfterTest
    fun tearDown() {
        SessionManager.clear()
    }

    @Test
    fun cloneTargetUsesRuntimeDeviceIdentityAsCloneRequestParameters() = runTest {
        SessionManager.initialize(
            InMemoryAuthSessionStore(
                AuthSession(accessToken = "access-token"),
            ),
        )
        val settingRepository = RecordingSettingRepository()
        val viewModel = PersonalSettingsViewModel(
            repository = settingRepository,
            userRepository = NoopUserRepository,
            onUnauthorized = {},
            deviceIdentityProvider = {
                DeviceIdentity(
                    type = "JVM",
                    key = "current-device-id",
                    name = "Workstation",
                )
            },
        )

        viewModel.cloneTarget(
            SettingTargetViewData(
                id = "source-target-id",
                name = AppStrings.ui_source_device,
                deviceKey = "source-device-key",
                type = "device",
                description = AppStrings.ui_test_personal_settings_view_model_clone_source_device_description,
            ),
        )
        advanceUntilIdle()

        assertEquals(
            CloneSettingTargetCommand(
                type = "device",
                sourceTargetId = "source-target-id",
                targetId = "current-device-id",
                name = "Workstation",
                description = null,
                subType = "JVM",
            ),
            settingRepository.lastCloneCommand,
        )
    }

    @Test
    fun useTargetStoresHeaderOverrideWithoutCloningTarget() = runTest {
        SessionManager.initialize(
            InMemoryAuthSessionStore(
                AuthSession(accessToken = "access-token"),
            ),
        )
        val settings = MapSettings()
        val settingRepository = RecordingSettingRepository()
        val viewModel = PersonalSettingsViewModel(
            repository = settingRepository,
            userRepository = NoopUserRepository,
            onUnauthorized = {},
            deviceIdentityProvider = {
                DeviceIdentity(
                    type = "JVM",
                    key = "current-device-id",
                    name = "Workstation",
                )
            },
            settings = settings,
        )

        viewModel.useTarget(
            SettingTargetViewData(
                id = "source-target-id",
                name = AppStrings.ui_source_device,
                deviceKey = "source-device-key",
                type = "device",
                subType = "Android",
                description = AppStrings.ui_test_personal_settings_view_model_clone_source_device_description,
            ),
        )
        advanceUntilIdle()

        assertEquals(null, settingRepository.lastCloneCommand)
        val override = decodeProApiHeaderOverride(settings.getString(KEY_PRO_API_HEADER_OVERRIDE, ""))
        assertEquals("Android", override?.deviceType)
        assertEquals("source-device-key", override?.deviceKey)
        assertEquals(AppStrings.ui_source_device, override?.deviceName)
    }

    @Test
    fun useTargetPullsAllRemoteSettingsBeforeUpdatingTimestamp() = runTest {
        SessionManager.initialize(
            InMemoryAuthSessionStore(
                AuthSession(accessToken = "access-token"),
            ),
        )
        val settings = MapSettings()
        settings.putBoolean(SettingsUtils.KEY_FILE_SHARE_ENABLED, false)
        settings.putLong(SettingsUtils.KEY_SETTINGS_SYNC_LAST_TIMESTAMP, 90L)
        val settingRepository = RecordingSettingRepository(
            remoteEntries = listOf(
                DeviceSettingEntry(SettingsUtils.KEY_FILE_SHARE_ENABLED, JsonPrimitive(true), updatedAt = 12L),
                DeviceSettingEntry(SettingsUtils.KEY_FILE_SHARE_PORT, JsonPrimitive(17000), updatedAt = 18L),
            ),
        )
        val viewModel = PersonalSettingsViewModel(
            repository = settingRepository,
            userRepository = NoopUserRepository,
            onUnauthorized = {},
            settings = settings,
        )

        viewModel.useTarget(
            SettingTargetViewData(
                id = "source-target-id",
                name = AppStrings.ui_source_device,
                deviceKey = "source-device-key",
                type = "device",
                subType = "Android",
                description = null,
            ),
        )
        advanceUntilIdle()

        assertEquals(null, settingRepository.lastCloneCommand)
        assertEquals("source-device-key", settingRepository.listTargetId)
        assertEquals(1L, settingRepository.listTimestamp)
        assertEquals(true, settings.getBoolean(SettingsUtils.KEY_FILE_SHARE_ENABLED, false))
        assertEquals(17000, settings.getInt(SettingsUtils.KEY_FILE_SHARE_PORT, 0))
        assertEquals(18L, settings.getLong(SettingsUtils.KEY_SETTINGS_SYNC_LAST_TIMESTAMP, 0L))
    }

    @Test
    fun useTargetRejectsTargetWithoutAnyRequestHeaderKey() = runTest {
        SessionManager.initialize(
            InMemoryAuthSessionStore(
                AuthSession(accessToken = "access-token"),
            ),
        )
        val settings = MapSettings()
        val settingRepository = RecordingSettingRepository()
        val viewModel = PersonalSettingsViewModel(
            repository = settingRepository,
            userRepository = NoopUserRepository,
            onUnauthorized = {},
            deviceIdentityProvider = {
                DeviceIdentity(
                    type = "JVM",
                    key = "current-device-id",
                    name = "Workstation",
                )
            },
            settings = settings,
        )

        viewModel.useTarget(
            SettingTargetViewData(
                id = "",
                name = AppStrings.ui_source_device,
                deviceKey = null,
                type = "device",
                subType = "Android",
                description = null,
            ),
        )
        advanceUntilIdle()

        assertEquals(null, settingRepository.lastCloneCommand)
        assertEquals(false, settings.hasKey(KEY_PRO_API_HEADER_OVERRIDE))
        assertEquals(AppStrings.ui_this_settings_snapshot_cannot_be_used_try_another_one, viewModel.state.value.feedback?.text)
    }

    @Test
    fun useTargetFallsBackToTargetIdWhenDeviceKeyIsMissing() = runTest {
        SessionManager.initialize(
            InMemoryAuthSessionStore(
                AuthSession(accessToken = "access-token"),
            ),
        )
        val settings = MapSettings()
        val settingRepository = RecordingSettingRepository()
        val viewModel = PersonalSettingsViewModel(
            repository = settingRepository,
            userRepository = NoopUserRepository,
            onUnauthorized = {},
            deviceIdentityProvider = {
                DeviceIdentity(
                    type = "JVM",
                    key = "current-device-id",
                    name = "Workstation",
                )
            },
            settings = settings,
        )

        viewModel.useTarget(
            SettingTargetViewData(
                id = "source-target-id",
                name = AppStrings.ui_source_device,
                deviceKey = null,
                type = "device",
                subType = "Android",
                description = null,
            ),
        )
        advanceUntilIdle()

        assertEquals(null, settingRepository.lastCloneCommand)
        val override = decodeProApiHeaderOverride(settings.getString(KEY_PRO_API_HEADER_OVERRIDE, ""))
        assertEquals("source-target-id", override?.deviceKey)
        assertEquals("source-target-id", viewModel.state.value.activeRequestHeaderDeviceKey)
    }

    @Test
    fun cancelUseTargetClearsStoredHeaderOverrideWithoutDeletingRemoteTarget() = runTest {
        SessionManager.initialize(
            InMemoryAuthSessionStore(
                AuthSession(accessToken = "access-token"),
            ),
        )
        val settings = MapSettings()
        settings.putString(KEY_PRO_API_HEADER_OVERRIDE, """{"deviceKey":"source-device-key"}""")
        val settingRepository = RecordingSettingRepository()
        val viewModel = PersonalSettingsViewModel(
            repository = settingRepository,
            userRepository = NoopUserRepository,
            onUnauthorized = {},
            settings = settings,
        )

        viewModel.cancelUseTarget()

        assertEquals(false, settings.hasKey(KEY_PRO_API_HEADER_OVERRIDE))
        assertEquals(null, settingRepository.deletedTargetId)
        assertEquals(null, viewModel.state.value.activeRequestHeaderDeviceKey)
        assertEquals(AppStrings.ui_this_device_has_been_restored_its_original_settings, viewModel.state.value.feedback?.text)
    }

    @Test
    fun cloneTargetKeepsMissingRuntimeDeviceIdentityParametersEmpty() = runTest {
        SessionManager.initialize(
            InMemoryAuthSessionStore(
                AuthSession(accessToken = "access-token"),
            ),
        )
        val settingRepository = RecordingSettingRepository()
        val viewModel = PersonalSettingsViewModel(
            repository = settingRepository,
            userRepository = NoopUserRepository,
            onUnauthorized = {},
            deviceIdentityProvider = {
                DeviceIdentity(
                    type = "",
                    key = "",
                    name = "",
                )
            },
        )

        viewModel.cloneTarget(
            SettingTargetViewData(
                id = "source-target-id",
                name = AppStrings.ui_source_device,
                deviceKey = null,
                type = null,
                description = null,
            ),
        )
        advanceUntilIdle()

        assertEquals(
            CloneSettingTargetCommand(
                type = "device",
                sourceTargetId = "source-target-id",
                targetId = "",
                name = "",
                description = null,
                subType = "",
            ),
            settingRepository.lastCloneCommand,
        )
    }

    private class RecordingSettingRepository(
        private val remoteEntries: List<DeviceSettingEntry> = emptyList(),
    ) : SettingRepository {
        var lastCloneCommand: CloneSettingTargetCommand? = null
        var deletedTargetId: String? = null
        var listTargetId: String? = null
        var listTimestamp: Long? = null

        override suspend fun listDeviceTargets(token: String): JsonResult =
            ApiResult.Success(JsonNull)

        override suspend fun listDeviceSettings(
            targetId: String,
            timestamp: Long,
            token: String,
        ): ApiResult<List<DeviceSettingEntry>> {
            listTargetId = targetId
            listTimestamp = timestamp
            return ApiResult.Success(remoteEntries)
        }

        override suspend fun saveDeviceSetting(
            targetId: String,
            entry: DeviceSettingEntry,
            token: String,
        ): JsonResult =
            ApiResult.Success(JsonNull)

        override suspend fun cloneTargetSettings(command: CloneSettingTargetCommand, token: String): JsonResult {
            lastCloneCommand = command
            return ApiResult.Success(JsonNull)
        }

        override suspend fun deleteTargetSettings(type: String, targetId: String, token: String): JsonResult {
            deletedTargetId = targetId
            return ApiResult.Success(JsonNull)
        }
    }
}

private object NoopUserRepository : UserRepository {
    override suspend fun me(token: String): JsonResult = ApiResult.Success(JsonNull)
    override suspend fun listDevices(token: String): JsonResult = ApiResult.Success(JsonNull)
    override suspend fun deleteDevice(id: Long, token: String): JsonResult = ApiResult.Success(JsonNull)
    override suspend fun updateProfile(
        command: com.folderspan.pro.domain.model.UpdateProfileCommand,
        token: String,
    ): JsonResult = ApiResult.Success(JsonNull)

    override suspend fun uploadAvatar(
        upload: com.folderspan.pro.domain.model.ProfileAvatarUpload,
        token: String,
    ): JsonResult = ApiResult.Success(JsonNull)

    override suspend fun removeAvatar(token: String): JsonResult = ApiResult.Success(JsonNull)

    override suspend fun withdrawProfileReview(token: String): JsonResult = ApiResult.Success(JsonNull)

    override suspend fun changePassword(
        command: com.folderspan.pro.domain.model.ChangePasswordCommand,
        token: String,
    ): JsonResult = ApiResult.Success(JsonNull)

    override suspend fun refreshToken(refreshToken: String): JsonResult =
        ApiResult.Failure("refresh is not used in this test")
}

@OptIn(ExperimentalCoroutinesApi::class)
class PersonalSettingsMainDispatcherRule(
    private val dispatcher: TestDispatcher = StandardTestDispatcher(),
) : TestWatcher() {
    override fun starting(description: Description) {
        Dispatchers.setMain(dispatcher)
    }

    override fun finished(description: Description) {
        Dispatchers.resetMain()
    }
}
