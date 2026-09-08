package com.folderspan.pro.presentation.screen.profile

import com.folderspan.pro.core.common.ApiResult
import com.folderspan.pro.core.common.JsonResult
import com.folderspan.pro.core.datastore.AuthSession
import com.folderspan.pro.core.datastore.InMemoryAuthSessionStore
import com.folderspan.pro.core.datastore.SessionManager
import com.folderspan.pro.domain.model.ChangePasswordCommand
import com.folderspan.pro.domain.model.ProfileAvatarUpload
import com.folderspan.pro.domain.model.UpdateProfileCommand
import com.folderspan.pro.domain.repository.UserRepository
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.test.resetMain
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import strings.AppStrings
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class EditProfileAvatarViewModelTest {
    @Test
    fun deliveredAvatarIsStagedUntilSingleProfileSubmit() = runTest {
        withViewModel { viewModel, repository ->
            viewModel.onAvatarPictureOutcome(AvatarPictureOutcome.Cancelled)
            assertEquals(0, repository.uploadCount)

            viewModel.onAvatarPictureOutcome(AvatarPictureOutcome.Failed("Choose another picture"))
            assertEquals("Choose another picture", viewModel.state.value.avatarFeedback?.text)
            assertEquals(0, repository.uploadCount)

            viewModel.onAvatarPictureOutcome(delivered())

            assertEquals(0, repository.uploadCount)
            assertEquals("image/jpeg", viewModel.state.value.pendingAvatar?.contentType)
            assertEquals(AppStrings.ui_profile_avatar_staged, viewModel.state.value.avatarFeedback?.text)

            viewModel.submit()
            advanceUntilIdle()

            assertEquals(1, repository.updateCount)
            assertTrue(repository.lastUpdate?.avatar?.bytes?.contentEquals(byteArrayOf(1, 2, 3)) == true)
            assertEquals("https://old.test/avatar.png", viewModel.state.value.profile?.avatar)
            assertFalse(viewModel.state.value.profile?.profileEditable ?: true)
            assertNull(viewModel.state.value.pendingAvatar)
            assertEquals(AppStrings.ui_profile_review_submitted, viewModel.state.value.feedback?.text)
        }
    }

    @Test
    fun pendingProfileReviewRejectsFurtherMutations() = runTest {
        withViewModel(profileEditable = false) { viewModel, repository ->
            viewModel.onNameChange("Must not change")
            viewModel.onSignatureChange("Must not change")
            viewModel.onAvatarPictureOutcome(delivered())
            viewModel.requestAvatarRemoval()
            viewModel.submit()
            advanceUntilIdle()

            assertEquals("Alice", viewModel.state.value.name)
            assertEquals("Hello", viewModel.state.value.signature)
            assertNull(viewModel.state.value.pendingAvatar)
            assertNull(repository.lastUpdate)
            assertEquals(0, repository.removeCount)
            assertFalse(viewModel.state.value.isAvatarRemovalConfirmationVisible)
        }
    }

    @Test
    fun profileFieldsAndAvatarAreSubmittedTogether() = runTest {
        withViewModel { viewModel, repository ->
            viewModel.onAvatarPictureOutcome(delivered())
            viewModel.onNameChange("Updated")
            viewModel.onSignatureChange("Updated signature")
            viewModel.submit()
            advanceUntilIdle()

            assertEquals(1, repository.updateCount)
            assertEquals("Updated", repository.lastUpdate?.name)
            assertEquals("Updated signature", repository.lastUpdate?.signature)
            assertEquals("image/jpeg", repository.lastUpdate?.avatar?.contentType)
            assertEquals(0, repository.uploadCount)
        }
    }

    @Test
    fun removalRequiresConfirmationCanBeDeclinedAndMayBeRepeated() = runTest {
        withViewModel { viewModel, repository ->
            viewModel.requestAvatarRemoval()
            assertTrue(viewModel.state.value.isAvatarRemovalConfirmationVisible)
            viewModel.declineAvatarRemoval()
            assertFalse(viewModel.state.value.isAvatarRemovalConfirmationVisible)
            assertEquals(0, repository.removeCount)

            viewModel.requestAvatarRemoval()
            viewModel.confirmAvatarRemoval()
            advanceUntilIdle()
            assertEquals(1, repository.removeCount)
            assertNull(viewModel.state.value.profile?.avatar)

            viewModel.confirmAvatarRemoval()
            advanceUntilIdle()
            assertEquals(2, repository.removeCount)
            assertNull(viewModel.state.value.profile?.avatar)
        }
    }

    @Test
    fun profileSaveDoesNotCarryAnAvatarAddress() = runTest {
        withViewModel { viewModel, repository ->
            viewModel.onNameChange("Updated")
            viewModel.submit()
            advanceUntilIdle()

            assertEquals("Updated", repository.lastUpdate?.name)
            assertNull(repository.lastUpdate?.signature)
            assertNull(repository.lastUpdate?.avatar)
            assertEquals("https://old.test/avatar.png", viewModel.state.value.profile?.avatar)
        }
    }

    @Test
    fun unchangedProfileDoesNotSendAReviewRequest() = runTest {
        withViewModel { viewModel, repository ->
            viewModel.submit()
            advanceUntilIdle()

            assertEquals(0, repository.updateCount)
            assertEquals(AppStrings.ui_profile_no_changes, viewModel.state.value.feedback?.text)
        }
    }

    @Test
    fun clearingPublishedSignatureSendsAnExplicitEmptyValue() = runTest {
        withViewModel { viewModel, repository ->
            viewModel.onSignatureChange("")
            viewModel.submit()
            advanceUntilIdle()

            assertNull(repository.lastUpdate?.name)
            assertEquals("", repository.lastUpdate?.signature)
        }
    }

    @Test
    fun unauthorizedProfileUpdateUsesExistingLoggedOutEffect() = runTest {
        withViewModel { viewModel, repository ->
            repository.updateHandler = {
                ApiResult.Failure(
                    message = "safe",
                    statusCode = HttpStatusCode.Unauthorized.value,
                )
            }
            val effect = async { viewModel.effect.first() }

            viewModel.onAvatarPictureOutcome(delivered())
            viewModel.submit()
            advanceUntilIdle()

            assertIs<ProfileEffect.LoggedOut>(effect.await())
        }
    }

    private suspend fun kotlinx.coroutines.test.TestScope.withViewModel(
        profileEditable: Boolean = true,
        block: suspend kotlinx.coroutines.test.TestScope.(EditProfileViewModel, AvatarUserRepository) -> Unit,
    ) {
        val dispatcher = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(dispatcher)
        val session = AuthSession(accessToken = "token")
        SessionManager.initialize(InMemoryAuthSessionStore(session))
        val repository = AvatarUserRepository(profileEditable)
        val viewModel = EditProfileViewModel(repository)
        try {
            viewModel.onSessionChanged(session)
            advanceUntilIdle()
            block(viewModel, repository)
        } finally {
            SessionManager.clear()
            Dispatchers.resetMain()
        }
    }

    private fun delivered() = AvatarPictureOutcome.Delivered(
        DeliveredAvatarPicture(byteArrayOf(1, 2, 3), "image/jpeg"),
    )
}

private class AvatarUserRepository(
    private val profileEditable: Boolean,
) : UserRepository {
    var uploadCount = 0
    var updateCount = 0
    var removeCount = 0
    var lastUpdate: UpdateProfileCommand? = null
    var updateHandler: suspend (UpdateProfileCommand) -> JsonResult = {
        ApiResult.Success(profileUpdateJson("https://old.test/avatar.png", profileEditable = false))
    }
    var uploadHandler: suspend (ProfileAvatarUpload) -> JsonResult = {
        ApiResult.Success(profileJson("https://cdn.test/new.jpg"))
    }
    var removeHandler: suspend () -> JsonResult = {
        ApiResult.Success(profileJson(null))
    }

    override suspend fun cachedMe(token: String): JsonResult? = null
    override suspend fun me(token: String): JsonResult =
        ApiResult.Success(profileJson("https://old.test/avatar.png", profileEditable = profileEditable))

    override suspend fun listDevices(token: String): JsonResult = ApiResult.Success(JsonNull)
    override suspend fun deleteDevice(id: Long, token: String): JsonResult = ApiResult.Success(JsonNull)
    override suspend fun updateProfile(command: UpdateProfileCommand, token: String): JsonResult {
        updateCount += 1
        lastUpdate = command
        return updateHandler(command)
    }

    override suspend fun uploadAvatar(upload: ProfileAvatarUpload, token: String): JsonResult {
        uploadCount += 1
        return uploadHandler(upload)
    }

    override suspend fun removeAvatar(token: String): JsonResult {
        removeCount += 1
        return removeHandler()
    }

    override suspend fun withdrawProfileReview(token: String): JsonResult = ApiResult.Success(JsonNull)

    override suspend fun changePassword(command: ChangePasswordCommand, token: String): JsonResult =
        ApiResult.Success(JsonNull)

    override suspend fun refreshToken(refreshToken: String): JsonResult = ApiResult.Failure("unused")
}

private fun profileJson(
    avatar: String?,
    name: String = "Alice",
    profileEditable: Boolean = true,
) = Json.parseToJsonElement(
    """
        {
          "uuid": "user-id",
          "name": "$name",
          "email": "alice@example.test",
          "avatar": ${avatar?.let { "\"$it\"" } ?: "null"},
          "signature": "Hello",
          "profileEditable": $profileEditable
        }
    """.trimIndent(),
)

private fun profileUpdateJson(avatar: String?, profileEditable: Boolean) = Json.parseToJsonElement(
    """
        {
          "reviewUuid": "review-id",
          "status": "pending",
          "submittedAt": 123,
          "user": {
            "uuid": "user-id",
            "name": "Alice",
            "email": "alice@example.test",
            "avatar": ${avatar?.let { "\"$it\"" } ?: "null"},
            "signature": "Hello",
            "profileEditable": $profileEditable
          }
        }
    """.trimIndent(),
)
