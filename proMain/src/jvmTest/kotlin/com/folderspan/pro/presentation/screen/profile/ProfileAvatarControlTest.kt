package com.folderspan.pro.presentation.screen.profile

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.v2.runComposeUiTest
import strings.AppStrings
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

@OptIn(ExperimentalTestApi::class)
class ProfileAvatarControlTest {
    @Test
    fun pendingProfileReviewIsShownOnAccountSummary() = runComposeUiTest {
        setContent {
            MaterialTheme {
                UserProfileHeroCard(
                    profile = profile("https://images.test/avatar.png").copy(profileEditable = false),
                    layoutMode = UserProfileLayoutMode.Compact,
                )
            }
        }

        onNodeWithText(AppStrings.ui_profile_review_pending_lock).assertIsDisplayed()
    }

    @Test
    fun pendingProfileReviewCanBeWithdrawnAfterConfirmation() = runComposeUiTest {
        var withdrawCount = 0
        setContent {
            MaterialTheme {
                UserProfilePage(
                    state = UserProfileUiState(
                        profile = profile("https://images.test/avatar.png").copy(profileEditable = false),
                        hasCheckedProfileCache = true,
                    ),
                    onWithdrawProfileReview = { withdrawCount += 1 },
                )
            }
        }

        onNodeWithText(AppStrings.ui_profile_review_withdraw).performClick()
        onNodeWithText(AppStrings.ui_profile_review_withdraw_confirm_title).assertIsDisplayed()
        onNodeWithText(AppStrings.ui_profile_review_withdraw_confirm_action).performClick()

        runOnIdle { assertEquals(1, withdrawCount) }
    }

    @Test
    fun editableProfileDoesNotShowPendingReviewStateOnAccountSummary() = runComposeUiTest {
        setContent {
            MaterialTheme {
                UserProfileHeroCard(
                    profile = profile("https://images.test/avatar.png"),
                    layoutMode = UserProfileLayoutMode.Compact,
                )
            }
        }

        onAllNodesWithText(AppStrings.ui_profile_review_pending_lock).assertCountEquals(0)
    }

    @Test
    fun editProfilePageDoesNotRepeatTheAccountSummaryCardOrAvatarHeading() = runComposeUiTest {
        setContent {
            MaterialTheme {
                EditProfilePage(
                    state = EditProfileUiState(
                        profile = profile("https://images.test/avatar.png"),
                        name = "Alice",
                        signature = "Hello",
                    ),
                )
            }
        }

        onAllNodesWithText("alice@example.test").assertCountEquals(0)
        onNodeWithText(AppStrings.ui_nickname).assertIsDisplayed()
        onAllNodesWithText(AppStrings.ui_profile_avatar).assertCountEquals(0)
    }

    @Test
    fun avatarRemovalConfirmationUsesSnackbar() = runComposeUiTest {
        var confirmations = 0
        setContent {
            MaterialTheme {
                EditProfilePage(
                    state = EditProfileUiState(isAvatarRemovalConfirmationVisible = true),
                    onConfirmAvatarRemoval = { confirmations += 1 },
                )
            }
        }

        onNodeWithText(AppStrings.ui_profile_avatar_remove_confirm_message).assertIsDisplayed()
        onAllNodesWithText(AppStrings.ui_profile_avatar_remove_confirm_title).assertCountEquals(0)
        onNodeWithText(AppStrings.ui_profile_avatar_remove).performClick()
        runOnIdle { assertEquals(1, confirmations) }
    }

    @Test
    fun externalAvatarOffersReplaceAndRemoveWithoutAddressField() = runComposeUiTest {
        var outcome: AvatarPictureOutcome? = null
        setContent {
            MaterialTheme {
                CompositionLocalProvider(
                    LocalAvatarPicturePicker provides AvatarPicturePicker { _, callback ->
                        callback(AvatarPictureOutcome.Cancelled)
                    },
                ) {
                    ProfileEditorCard(
                        isSubmitting = false,
                        isAvatarSubmitting = false,
                        profile = profile("https://images.test/external.png"),
                        name = "Alice",
                        signature = "Hello",
                        avatarFeedback = null,
                        canRetryAvatarAction = false,
                        nameFieldError = null,
                        onNameChange = {},
                        onAvatarPictureOutcome = { outcome = it },
                        onRequestAvatarRemoval = {},
                        onRetryAvatarAction = {},
                        onSignatureChange = {},
                        onSubmit = {},
                    )
                }
            }
        }

        onNodeWithContentDescription(AppStrings.ui_user_avatar).assertIsDisplayed()
        onNodeWithText(AppStrings.ui_profile_avatar_replace).performClick()
        runOnIdle { assertIs<AvatarPictureOutcome.Cancelled>(outcome) }
        onNodeWithText(AppStrings.ui_profile_avatar_remove).assertIsDisplayed()
        onAllNodesWithText("Avatar address").assertCountEquals(0)
        onAllNodesWithText(AppStrings.ui_test_profile_avatar_control_avatar_address).assertCountEquals(0)
    }

    @Test
    fun emptyAvatarShowsUnavailableChooseActionWithoutRemove() = runComposeUiTest {
        setContent {
            MaterialTheme {
                ProfileEditorCard(
                    isSubmitting = false,
                    isAvatarSubmitting = false,
                    profile = profile(null),
                    name = "Alice",
                    signature = "Hello",
                    avatarFeedback = null,
                    canRetryAvatarAction = false,
                    nameFieldError = null,
                    onNameChange = {},
                    onAvatarPictureOutcome = {},
                    onRequestAvatarRemoval = {},
                    onRetryAvatarAction = {},
                    onSignatureChange = {},
                    onSubmit = {},
                )
            }
        }

        onNodeWithText(AppStrings.ui_profile_avatar_choose).assertIsNotEnabled()
        onNodeWithText(AppStrings.ui_profile_avatar_unavailable).assertIsDisplayed()
        onAllNodesWithText(AppStrings.ui_profile_avatar_remove).assertCountEquals(0)
    }

    @Test
    fun pendingProfileReviewExplainsLockAndDisablesMutationActions() = runComposeUiTest {
        setContent {
            MaterialTheme {
                CompositionLocalProvider(
                    LocalAvatarPicturePicker provides AvatarPicturePicker { _, _ -> },
                ) {
                    ProfileEditorCard(
                        isSubmitting = false,
                        isAvatarSubmitting = false,
                        profile = profile("https://images.test/external.png").copy(profileEditable = false),
                        name = "Alice",
                        signature = "Hello",
                        avatarFeedback = null,
                        canRetryAvatarAction = false,
                        nameFieldError = null,
                        onNameChange = {},
                        onAvatarPictureOutcome = {},
                        onRequestAvatarRemoval = {},
                        onRetryAvatarAction = {},
                        onSignatureChange = {},
                        onSubmit = {},
                    )
                }
            }
        }

        onNodeWithText(AppStrings.ui_profile_review_pending_lock).assertIsDisplayed()
        onNodeWithText(AppStrings.ui_profile_avatar_replace).assertIsNotEnabled()
        onNodeWithText(AppStrings.ui_profile_avatar_remove).assertIsNotEnabled()
        onNodeWithText(AppStrings.ui_save_data).assertIsNotEnabled()
    }

    private fun profile(avatar: String?) = UserProfileViewData(
        uuid = "user",
        name = "Alice",
        email = "alice@example.test",
        avatar = avatar,
        signature = "Hello",
        status = 1,
    )
}
