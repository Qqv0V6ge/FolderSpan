package com.folderspan.ui.components.notification

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.hasTextExactly
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.requestFocus
import androidx.compose.ui.test.v2.runComposeUiTest
import com.folderspan.pro.domain.model.NotificationAction
import com.folderspan.pro.domain.model.NotificationActionKind
import com.folderspan.pro.domain.model.NotificationActionStyle
import com.folderspan.ui.components.image.IMAGE_PREVIEW_DIALOG_TEST_TAG
import com.folderspan.ui.screen.main.AccountNotificationActionRow
import strings.AppStrings
import kotlin.test.Test
import kotlin.test.assertEquals

@OptIn(ExperimentalTestApi::class)
class NotificationMarkdownContentTest {
    @Test
    fun supportedLinksExposeLinkRolesAndUnsupportedTargetsRemainPlainLabels() = runComposeUiTest {
        val activated = mutableListOf<String>()
        setContent {
            MaterialTheme {
                NotificationMarkdownContent(
                    content = "Open [Docs](https://example.test), " +
                        "[Email](mailto:support@example.test), and [Settings](route:settings).",
                    onAction = { action -> activated += action.label },
                )
            }
        }

        val links = onAllNodes(isLink(), useUnmergedTree = true)
        links.assertCountEquals(2)
        links[0].assertIsDisplayed().performClick()
        links[1].assertIsDisplayed().performClick()
        onNodeWithText("Open Docs, Email, and Settings.", useUnmergedTree = true).assertIsDisplayed()

        assertEquals(listOf("Docs", "Settings"), activated)
    }

    @Test
    fun keyboardActivationDispatchesOnlyTheFocusedLink() = runComposeUiTest {
        val activated = mutableListOf<String>()
        setContent {
            MaterialTheme {
                NotificationMarkdownContent(
                    content = "[First](https://example.test/first) [Second](route:settings)",
                    onAction = { action -> activated += action.label },
                )
            }
        }

        onAllNodes(isLink(), useUnmergedTree = true)[1]
            .requestFocus()
            .performKeyInput { pressKey(Key.Enter) }

        assertEquals(listOf("Second"), activated)
    }

    @Test
    fun inlineLinksAndStructuredActionsDispatchTheirOwnTargets() = runComposeUiTest {
        val activated = mutableListOf<String>()
        val buttonAction = NotificationAction(
            label = "Action button",
            style = NotificationActionStyle.Primary,
            kind = NotificationActionKind.Url,
            url = "https://example.test/button",
        )
        setContent {
            MaterialTheme {
                Column {
                    NotificationMarkdownContent(
                        content = "[Inline link](https://example.test/inline)",
                        onAction = { action -> activated += action.label },
                    )
                    AccountNotificationActionRow(
                        actions = listOf(buttonAction),
                        onAction = { action -> activated += action.label },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        }

        onAllNodes(isLink(), useUnmergedTree = true)[0].performClick()
        onNodeWithText("Action button").performClick()

        assertEquals(listOf("Inline link", "Action button"), activated)
    }

    @Test
    fun rendersBlockLevelMarkdownStructures() = runComposeUiTest {
        setContent {
            MaterialTheme {
                NotificationMarkdownContent(
                    content = "# Title\n\n- first\n- second\n\n```\ncode\n```\n\n> quote",
                    onAction = {},
                )
            }
        }

        onNodeWithText("Title").assertIsDisplayed()
        onNodeWithText("first").assertIsDisplayed()
        onNodeWithText("second").assertIsDisplayed()
        onNodeWithText("code").assertIsDisplayed()
        onNodeWithText("quote").assertIsDisplayed()
        onAllNodes(hasTextExactly("•"), useUnmergedTree = true).assertCountEquals(2)
    }

    @Test
    fun linksStayClickableInsideSelectionContainer() = runComposeUiTest {
        val activated = mutableListOf<String>()
        setContent {
            MaterialTheme {
                SelectionContainer {
                    NotificationMarkdownContent(
                        content = "Open [Docs](https://example.test/docs)",
                        onAction = { action -> activated += action.label },
                    )
                }
            }
        }

        onAllNodes(isLink(), useUnmergedTree = true)[0].performClick()

        assertEquals(listOf("Docs"), activated)
    }

    @Test
    fun httpImagesDoNotShowRawMarkdownSyntax() = runComposeUiTest {
        setContent {
            MaterialTheme {
                NotificationMarkdownContent(
                    content = "Notes\n\n![Release screenshot](https://example.test/shot.png)",
                    onAction = {},
                )
            }
        }

        onNodeWithText("Notes").assertIsDisplayed()
        onNodeWithText(
            "![Release screenshot](https://example.test/shot.png)",
            substring = true,
        ).assertDoesNotExist()
        waitUntil(timeoutMillis = 5_000) {
            onAllNodes(hasText(AppStrings.ui_loading), useUnmergedTree = true)
                .fetchSemanticsNodes().isNotEmpty() ||
                onAllNodes(hasText(AppStrings.ui_loading_failed), useUnmergedTree = true)
                    .fetchSemanticsNodes().isNotEmpty()
        }
    }

    @Test
    fun unsupportedImageUrlsShowFailureStatusWithoutRetry() = runComposeUiTest {
        setContent {
            MaterialTheme {
                NotificationMarkdownContent(
                    content = "Intro\n\n![secret](file:///tmp/x.png)\n\nOutro",
                    onAction = {},
                )
            }
        }

        onNodeWithText("Intro").assertIsDisplayed()
        onNodeWithText(AppStrings.ui_loading_failed).assertIsDisplayed()
        onNodeWithText("secret").assertIsDisplayed()
        onNodeWithText("Outro").assertIsDisplayed()
        onNodeWithText(AppStrings.ui_reload).assertDoesNotExist()
        onNodeWithText("![secret](file:///tmp/x.png)", substring = true).assertDoesNotExist()
    }

    @Test
    fun clickingImageOpensGlobalPreviewWithTitle() = runComposeUiTest {
        setContent {
            MaterialTheme {
                NotificationMarkdownContent(
                    content = "Notes\n\n![logo](https://example.test/logo.png \"Release notes\")",
                    onAction = {},
                )
            }
        }

        onNodeWithText("logo").performClick()
        onNodeWithTag(IMAGE_PREVIEW_DIALOG_TEST_TAG).assertIsDisplayed()
        onNodeWithText("Release notes").assertIsDisplayed()
        onNodeWithContentDescription(AppStrings.ui_close).assertIsDisplayed().performClick()
        onNodeWithTag(IMAGE_PREVIEW_DIALOG_TEST_TAG).assertDoesNotExist()
    }

    @Test
    fun clickingImageWithoutTitleUsesAltAsCaption() = runComposeUiTest {
        setContent {
            MaterialTheme {
                NotificationMarkdownContent(
                    content = "![screenshot](https://example.test/shot.png)",
                    onAction = {},
                )
            }
        }

        onNodeWithText("screenshot").performClick()
        onNodeWithTag(IMAGE_PREVIEW_DIALOG_TEST_TAG).assertIsDisplayed()
        onNodeWithContentDescription(AppStrings.ui_close).assertIsDisplayed()
    }

    private fun isLink(): SemanticsMatcher =
        SemanticsMatcher.keyIsDefined(SemanticsProperties.LinkTestMarker)
}
