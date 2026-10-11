package com.folderspan.ui.components.avatar

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import androidx.compose.ui.test.v2.runComposeUiTest
import strings.AppStrings
import kotlin.test.Test
import kotlin.test.assertTrue

@OptIn(ExperimentalTestApi::class)
class AvatarImageEditorDialogTest {
    @Test
    fun editorKeepsPreviewToolsAndPrimaryActionVisible() = runDesktopComposeUiTest(
        width = 696,
        height = 516,
    ) {
        setContent {
            MaterialTheme {
                AvatarImageEditorDialog(
                    source = ImageBitmap(8, 8),
                    isProcessing = false,
                    onDismiss = {},
                    onConfirm = { _, _ -> },
                )
            }
        }

        onNodeWithContentDescription(AVATAR_EDITOR_COMPACT_TEST_TAG).assertIsDisplayed()
        val cropNode = onNodeWithContentDescription(AVATAR_EDITOR_CROP_TEST_TAG)
        cropNode.assertIsDisplayed()
        assertTrue(cropNode.fetchSemanticsNode().boundsInRoot.height >= 280f)
        onNodeWithContentDescription(AppStrings.ui_profile_avatar_rotate_left).assertIsDisplayed()
        onNodeWithContentDescription(AppStrings.ui_profile_avatar_flip_vertical)
            .assertIsDisplayed()
            .performClick()
            .assertIsOn()
        onNodeWithText(AppStrings.ui_confirm).assertIsDisplayed()
    }

    @Test
    fun mouseWheelZoomsTheImageInsideTheCropWindow() = runDesktopComposeUiTest(
        width = 696,
        height = 516,
    ) {
        var confirmedScale = 0f
        setContent {
            MaterialTheme {
                AvatarImageEditorDialog(
                    source = ImageBitmap(1024, 1024),
                    isProcessing = false,
                    onDismiss = {},
                    onConfirm = { state, _ -> confirmedScale = state.scale },
                )
            }
        }

        onNodeWithContentDescription(AVATAR_EDITOR_CROP_TEST_TAG)
            .performMouseInput {
                moveTo(center)
                scroll(-1f)
            }
        onNodeWithText(AppStrings.ui_confirm).performClick()

        assertTrue(confirmedScale > 1f, "Expected wheel-up to zoom in, scale was $confirmedScale")
    }

    @Test
    fun darkThemeProgressStateRemainsReadableAndBlocksActions() = runComposeUiTest {
        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                AvatarImageEditorDialog(
                    source = ImageBitmap(8, 8),
                    isProcessing = true,
                    onDismiss = {},
                    onConfirm = { _, _ -> },
                )
            }
        }

        onNodeWithContentDescription(AVATAR_EDITOR_EXPANDED_TEST_TAG).assertIsDisplayed()
        onNodeWithText(AppStrings.ui_profile_avatar_editor_title).assertIsDisplayed()
        onNodeWithContentDescription(AVATAR_EDITOR_PROGRESS_TEST_TAG).assertIsDisplayed()
        onNodeWithText(AppStrings.ui_profile_avatar_processing).assertIsDisplayed()
    }
}
