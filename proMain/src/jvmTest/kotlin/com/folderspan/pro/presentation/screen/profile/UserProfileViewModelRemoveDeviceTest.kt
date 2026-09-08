package com.folderspan.pro.presentation.screen.profile

import com.folderspan.pro.core.common.ApiResult
import com.folderspan.pro.core.common.JsonResult
import com.folderspan.pro.core.datastore.AuthSession
import com.folderspan.pro.core.datastore.InMemoryAuthSessionStore
import com.folderspan.pro.core.datastore.SessionManager
import com.folderspan.pro.core.ui.components.AuthStatusTone
import com.folderspan.pro.domain.model.ChangePasswordCommand
import com.folderspan.pro.domain.model.UpdateProfileCommand
import com.folderspan.pro.domain.repository.UserRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.test.*
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Rule
import org.junit.rules.TestWatcher
import org.junit.runner.Description
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.milliseconds
import strings.AppStrings

@OptIn(ExperimentalCoroutinesApi::class)
class UserProfileViewModelRemoveDeviceTest {
    @get:Rule
    val mainDispatcherRule = UserProfileMainDispatcherRule()

    @AfterTest
    fun tearDown() {
        SessionManager.clear()
    }

    @Test
    fun removeCurrentDeviceEmitsLoggedOutAfterSuccessfulRemoval() = runTest {
        SessionManager.initialize(
            InMemoryAuthSessionStore(
                AuthSession(accessToken = "access-token"),
            ),
        )
        val viewModel = UserProfileViewModel(RecordingUserRepository())

        viewModel.removeDevice(deviceId = 7L, isCurrentDevice = true)
        advanceUntilIdle()

        assertEquals(null, SessionManager.currentSession())
        assertEquals(ProfileEffect.LoggedOut, viewModel.effect.first())
    }

    @Test
    fun removeOtherDeviceDoesNotEmitLoggedOutAfterSuccessfulRemoval() = runTest {
        SessionManager.initialize(
            InMemoryAuthSessionStore(
                AuthSession(accessToken = "access-token"),
            ),
        )
        val viewModel = UserProfileViewModel(RecordingUserRepository())

        viewModel.removeDevice(deviceId = 7L, isCurrentDevice = false)
        advanceUntilIdle()

        assertEquals(null, withTimeoutOrNull(100.milliseconds) { viewModel.effect.firstOrNull() })
    }

    @Test
    fun refreshProfileDoesNotRequestDevices() = runTest {
        SessionManager.initialize(
            InMemoryAuthSessionStore(
                AuthSession(accessToken = "access-token"),
            ),
        )
        val repository = RecordingUserRepository(
            profileResult = ApiResult.Success(profileJson("Cached", "cached@example.test")),
            devicesResult = ApiResult.Failure(AppStrings.ui_test_user_profile_view_model_remove_device_should_not_request_device_list),
        )
        val viewModel = UserProfileViewModel(repository)

        viewModel.refreshProfile()
        advanceUntilIdle()

        assertEquals("Cached", viewModel.state.value.profile?.name)
        assertEquals(0, repository.listDevicesCallCount)
    }

    @Test
    fun refreshedProfileUpdatesSharedSessionSnapshotForDrawer() = runTest {
        SessionManager.initialize(
            InMemoryAuthSessionStore(
                AuthSession(
                    accessToken = "access-token",
                    userEmail = "old@example.test",
                    loginData = Json.parseToJsonElement(
                        """{"user":{"name":"Old","avatar":"https://cdn.test/old.png"}}""",
                    ) as JsonObject,
                ),
            ),
        )
        val repository = RecordingUserRepository(
            profileResult = ApiResult.Success(
                profileJson(
                    name = "111",
                    email = "new@example.test",
                    avatar = "https://cdn.test/new.png",
                ),
            ),
        )
        val viewModel = UserProfileViewModel(repository)

        viewModel.refreshProfile()
        advanceUntilIdle()

        val session = SessionManager.currentSession()
        val snapshot = session?.loginData?.get("profile") as? JsonObject
        assertEquals("111", snapshot?.get("name")?.jsonPrimitive?.content)
        assertEquals("new@example.test", session?.userEmail)
        assertEquals("https://cdn.test/new.png", snapshot?.get("avatar")?.jsonPrimitive?.content)
    }

    @Test
    fun lateProfileResponseDoesNotOverwriteANewAccountSession() = runTest {
        SessionManager.initialize(
            InMemoryAuthSessionStore(
                AuthSession(
                    accessToken = "account-a-token",
                    userUuid = "account-a",
                    userEmail = "a@example.test",
                ),
            ),
        )
        val repository = RecordingUserRepository(
            profileResult = ApiResult.Success(profileJson("Account A", "a@example.test")),
            profileDelayMillis = 1_000,
        )
        val viewModel = UserProfileViewModel(repository)

        viewModel.refreshProfile()
        runCurrent()
        SessionManager.update(
            AuthSession(
                accessToken = "account-b-token",
                userUuid = "account-b",
                userEmail = "b@example.test",
                loginData = Json.parseToJsonElement("""{"profile":{"name":"Account B"}}""") as JsonObject,
            ),
        )
        advanceTimeBy(1_000.milliseconds)
        advanceUntilIdle()

        val currentSession = SessionManager.currentSession()
        val snapshot = currentSession?.loginData?.get("profile") as? JsonObject
        assertEquals("account-b", currentSession?.userUuid)
        assertEquals("Account B", snapshot?.get("name")?.jsonPrimitive?.content)
    }

    @Test
    fun withdrawProfileReviewRestoresPublishedProfileAndEditableState() = runTest {
        SessionManager.initialize(
            InMemoryAuthSessionStore(AuthSession(accessToken = "access-token")),
        )
        val repository = RecordingUserRepository(
            profileResult = ApiResult.Success(
                profileJson("Pending profile", "user@example.test", profileEditable = false),
            ),
            withdrawResult = ApiResult.Success(
                withdrawJson("Published profile", "user@example.test"),
            ),
        )
        val viewModel = UserProfileViewModel(repository)
        viewModel.refreshProfile()
        advanceUntilIdle()

        viewModel.withdrawProfileReview()
        advanceUntilIdle()

        val state = viewModel.state.value
        assertEquals(1, repository.withdrawProfileReviewCallCount)
        assertEquals("Published profile", state.profile?.name)
        assertEquals(true, state.profile?.profileEditable)
        assertEquals(false, state.isWithdrawingProfileReview)
        assertEquals(AuthStatusTone.Success, state.profileFeedback?.tone)
    }

    @Test
    fun refreshProfileKeepsCachedProfileWhenResponseCannotRender() = runTest {
        SessionManager.initialize(
            InMemoryAuthSessionStore(
                AuthSession(accessToken = "access-token"),
            ),
        )
        val repository = RecordingUserRepository(
            profileResult = ApiResult.Success(profileJson("Cached", "cached@example.test")),
        )
        val viewModel = UserProfileViewModel(repository)

        viewModel.refreshProfile()
        advanceUntilIdle()

        repository.profileResult = ApiResult.Success(JsonNull)
        viewModel.refreshProfile()
        advanceUntilIdle()

        val state = viewModel.state.value
        assertEquals("Cached", state.profile?.name)
        assertEquals("cached@example.test", state.profile?.email)
        assertEquals(AppStrings.ui_data_cannot_displayed_moment_please_try_again_later, state.pageErrorMessage)
        assertEquals(false, state.isRefreshingPage)
    }

    @Test
    fun refreshProfileShowsCachedProfileBeforeNetworkRefreshFinishes() = runTest {
        SessionManager.initialize(
            InMemoryAuthSessionStore(
                AuthSession(accessToken = "access-token"),
            ),
        )
        val repository = RecordingUserRepository(
            cachedProfileResult = ApiResult.Success(profileJson("Cached", "cached@example.test")),
            profileResult = ApiResult.Success(profileJson("Network", "network@example.test")),
            profileDelayMillis = 1_000,
        )
        val viewModel = UserProfileViewModel(repository)

        viewModel.refreshProfile()
        runCurrent()

        val cachedState = viewModel.state.value
        assertEquals("Cached", cachedState.profile?.name)
        assertEquals("cached@example.test", cachedState.profile?.email)
        assertEquals(true, cachedState.hasCheckedProfileCache)
        assertEquals(true, cachedState.isRefreshingPage)
        assertEquals(1, repository.cachedMeCallCount)

        advanceTimeBy(1_000.milliseconds)
        advanceUntilIdle()

        val refreshedState = viewModel.state.value
        assertEquals("Network", refreshedState.profile?.name)
        assertEquals("network@example.test", refreshedState.profile?.email)
        assertEquals(false, refreshedState.isRefreshingPage)
    }

    @Test
    fun onSessionChangedCanSkipProfileRefreshForDevicePage() = runTest {
        SessionManager.initialize(
            InMemoryAuthSessionStore(
                AuthSession(accessToken = "access-token"),
            ),
        )
        val repository = RecordingUserRepository(
            profileResult = ApiResult.Failure(AppStrings.ui_test_user_profile_view_model_remove_device_should_not_request_materials),
        )
        val viewModel = UserProfileViewModel(repository)

        viewModel.onSessionChanged(
            session = AuthSession(accessToken = "access-token"),
            loadProfile = false,
        )
        advanceUntilIdle()

        assertEquals(0, repository.meCallCount)
        assertEquals(null, viewModel.state.value.pageErrorMessage)
    }

    @Test
    fun refreshDevicesKeepsCachedDevicesWhenDeviceRefreshFails() = runTest {
        SessionManager.initialize(
            InMemoryAuthSessionStore(
                AuthSession(accessToken = "access-token"),
            ),
        )
        val repository = RecordingUserRepository(
            profileResult = ApiResult.Success(profileJson("Cached", "cached@example.test")),
            devicesResult = ApiResult.Success(devicesJson("Cached Phone")),
        )
        val viewModel = UserProfileViewModel(repository)

        viewModel.refreshDevices()
        advanceUntilIdle()

        repository.profileResult = ApiResult.Success(profileJson("Network", "network@example.test"))
        repository.devicesResult = ApiResult.Failure(AppStrings.ui_test_user_profile_view_model_remove_device_device_list_temporarily_cannot)
        viewModel.refreshDevices()
        advanceUntilIdle()

        val state = viewModel.state.value
        assertEquals(listOf("Cached Phone"), state.devices.map { it.deviceName })
        assertEquals(1, state.deviceTotal)
        assertEquals(AppStrings.ui_test_user_profile_view_model_remove_device_device_list_temporarily_cannot, state.pageErrorMessage)
        assertEquals(false, state.isRefreshingPage)
    }

    @Test
    fun refreshDevicesMarksEmptyDeviceListAsLoaded() = runTest {
        SessionManager.initialize(
            InMemoryAuthSessionStore(
                AuthSession(accessToken = "access-token"),
            ),
        )
        val repository = RecordingUserRepository(
            devicesResult = ApiResult.Success(emptyDevicesJson()),
        )
        val viewModel = UserProfileViewModel(repository)

        viewModel.refreshDevices(forceRefresh = false)
        advanceUntilIdle()
        viewModel.refreshDevices(forceRefresh = false)
        advanceUntilIdle()

        assertEquals(1, repository.listDevicesCallCount)
        assertEquals(emptyList(), viewModel.state.value.devices)
        assertEquals(0, viewModel.state.value.deviceTotal)
    }

    private class RecordingUserRepository(
        var profileResult: JsonResult = ApiResult.Success(JsonNull),
        var cachedProfileResult: JsonResult? = null,
        var devicesResult: JsonResult = ApiResult.Success(JsonNull),
        var withdrawResult: JsonResult = ApiResult.Success(JsonNull),
        var profileDelayMillis: Long = 0,
    ) : UserRepository {
        var listDevicesCallCount = 0
        var meCallCount = 0
        var cachedMeCallCount = 0
        var withdrawProfileReviewCallCount = 0

        override suspend fun me(token: String): JsonResult {
            meCallCount += 1
            if (profileDelayMillis > 0) {
                delay(profileDelayMillis.milliseconds)
            }
            return profileResult
        }

        override suspend fun cachedMe(token: String): JsonResult? {
            cachedMeCallCount += 1
            return cachedProfileResult
        }

        override suspend fun listDevices(token: String): JsonResult {
            listDevicesCallCount += 1
            return devicesResult
        }

        override suspend fun deleteDevice(id: Long, token: String): JsonResult = ApiResult.Success(JsonNull)
        override suspend fun updateProfile(command: UpdateProfileCommand, token: String): JsonResult =
            ApiResult.Success(JsonNull)

        override suspend fun uploadAvatar(
            upload: com.folderspan.pro.domain.model.ProfileAvatarUpload,
            token: String,
        ): JsonResult = ApiResult.Success(JsonNull)

        override suspend fun removeAvatar(token: String): JsonResult = ApiResult.Success(JsonNull)

        override suspend fun withdrawProfileReview(token: String): JsonResult {
            withdrawProfileReviewCallCount += 1
            return withdrawResult
        }

        override suspend fun changePassword(command: ChangePasswordCommand, token: String): JsonResult =
            ApiResult.Success(JsonNull)

        override suspend fun refreshToken(refreshToken: String): JsonResult =
            ApiResult.Failure("refresh is not used in this test")
    }

    private fun profileJson(
        name: String,
        email: String,
        avatar: String? = null,
        profileEditable: Boolean = true,
    ): JsonElement =
        Json.parseToJsonElement(
            """
            {
              "code": 0,
              "data": {
                "uuid": "user-id",
                "name": "$name",
                "email": "$email",
                "avatar": ${avatar?.let { "\"$it\"" } ?: "null"},
                "profileEditable": $profileEditable
              }
            }
            """.trimIndent(),
        )

    private fun withdrawJson(name: String, email: String): JsonElement =
        Json.parseToJsonElement(
            """
            {
              "code": 0,
              "data": {
                "reviewUuid": "review-id",
                "status": "withdrawn",
                "submittedAt": 1,
                "reviewedAt": 2,
                "user": {
                  "uuid": "user-id",
                  "name": "$name",
                  "email": "$email",
                  "avatar": "https://cdn.test/published.png",
                  "profileEditable": true
                }
              }
            }
            """.trimIndent(),
        )

    private fun devicesJson(deviceName: String): JsonElement =
        Json.parseToJsonElement(
            """
            {
              "code": 0,
              "data": {
                "devices": [
                  {
                    "id": 7,
                    "device_type": "Android",
                    "device_name": "$deviceName",
                    "device_key": "device-key",
                    "created_at": 0
                  }
                ],
                "total": 1
              }
            }
            """.trimIndent(),
        )

    private fun emptyDevicesJson(): JsonElement =
        Json.parseToJsonElement(
            """
            {
              "code": 0,
              "data": {
                "devices": [],
                "total": 0
              }
            }
            """.trimIndent(),
        )
}

@OptIn(ExperimentalCoroutinesApi::class)
class UserProfileMainDispatcherRule(
    private val dispatcher: TestDispatcher = StandardTestDispatcher(),
) : TestWatcher() {
    override fun starting(description: Description) {
        Dispatchers.setMain(dispatcher)
    }

    override fun finished(description: Description) {
        Dispatchers.resetMain()
    }
}
