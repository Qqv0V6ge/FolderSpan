package com.folderspan.ui.screen.settings

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.unit.dp
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.v2.runComposeUiTest
import com.folderspan.data.main.device.DeviceType
import com.folderspan.pro.domain.model.AppUpdate
import com.folderspan.pro.domain.model.AppUpdateChannel
import com.folderspan.pro.domain.model.AppUpdateSnapshot
import com.folderspan.pro.domain.model.AppUpdateStatus
import com.folderspan.test.ChineseLocalizationTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import strings.AppStrings

@OptIn(ExperimentalTestApi::class)
class AboutSoftwareScreenTest : ChineseLocalizationTest() {
    @Test
    fun versionIsVisibleOnEveryPlatform() = runComposeUiTest {
        setContent {
            MaterialTheme {
                AboutSoftwareContent(
                    version = "1.2.3",
                    showCheck = false,
                    snapshot = AppUpdateSnapshot(),
                    onCheck = {},
                    onOpenLink = {},
                )
            }
        }

        onNodeWithTag("about-software-version")
            .assertIsDisplayed()
            .assertTextEquals("1.2.3")
        onAllNodesWithTag("about-software-check").assertCountEquals(0)
        onAllNodesWithTag("about-software-channel").assertCountEquals(0)
        onNodeWithTag("about-software-history").assertIsDisplayed()
        onNodeWithTag("about-software-user-agreement").assertIsDisplayed()
        onNodeWithTag("about-software-privacy-policy").assertIsDisplayed()
        onNodeWithTag("about-software-auto-capture").performScrollTo().assertIsDisplayed()
        onNodeWithTag("about-software-auto-capture-switch").assertIsDisplayed()
    }

    @Test
    fun webHidesCheckForUpdates() {
        assertFalse(shouldShowAppUpdateCheck(DeviceType.JS))
        assertTrue(shouldShowAppUpdateCheck(DeviceType.Android))
        assertTrue(shouldShowAppUpdateCheck(DeviceType.IOS))
        assertTrue(shouldShowAppUpdateCheck(DeviceType.JVM))
    }

    @Test
    fun nativeCheckShowsUpToDateNewerAndErrorWithoutPosting() = runComposeUiTest {
        val checks = mutableListOf<Unit>()
        val opened = mutableListOf<String>()
        val newer = AppUpdateSnapshot(
            status = AppUpdateStatus.Newer,
            update = AppUpdate(
                id = 1L,
                title = "1.4.0",
                content = "Notes",
                version = "1.4.0",
                platform = "linux",
                link = "https://example.test/app",
                publishedAtEpochMillis = 1L,
            ),
        )

        setContent {
            MaterialTheme {
                AboutSoftwareContent(
                    version = "1.0.0",
                    showCheck = true,
                    snapshot = newer,
                    onCheck = { checks += Unit },
                    onOpenLink = opened::add,
                )
            }
        }

        onNodeWithTag("about-software-channel").assertIsDisplayed()
        onAllNodesWithTag("about-software-open-link").assertCountEquals(0)
        onNodeWithTag("about-software-newer").assertIsDisplayed().performClick()
        onNodeWithTag("about-software-open-link").assertIsDisplayed().performClick()
        assertEquals(listOf("https://example.test/app"), opened)
        onAllNodesWithTag("about-software-open-link").assertCountEquals(0)
        onNodeWithTag("about-software-check").assertIsDisplayed().performClick()
        assertEquals(1, checks.size)
    }

    @Test
    fun errorStateShowsRetryableMessage() = runComposeUiTest {
        setContent {
            MaterialTheme {
                AboutSoftwareContent(
                    version = "1.0.0",
                    showCheck = true,
                    snapshot = AppUpdateSnapshot(
                        status = AppUpdateStatus.Error,
                        errorMessage = "Unable to check for updates. Try again later.",
                    ),
                    onCheck = {},
                    onOpenLink = {},
                )
            }
        }

        onNodeWithTag("about-software-error")
            .assertIsDisplayed()
            .assertTextEquals("Unable to check for updates. Try again later.")
        onAllNodesWithTag("about-software-newer").assertCountEquals(0)
    }

    @Test
    fun upToDateStatusDoesNotOfferDownloadLink() = runComposeUiTest {
        setContent {
            MaterialTheme {
                AboutSoftwareContent(
                    version = "1.0.0",
                    showCheck = true,
                    snapshot = AppUpdateSnapshot(status = AppUpdateStatus.UpToDate),
                    onCheck = {},
                    onOpenLink = {},
                )
            }
        }

        onNodeWithTag("about-software-check").assertIsDisplayed()
        onAllNodesWithTag("about-software-open-link").assertCountEquals(0)
        onAllNodesWithTag("about-software-error").assertCountEquals(0)
    }

    @Test
    fun changelogStripsLeadingMarkers() {
        assertEquals(
            listOf(AppStrings.ui_test_about_software_screen_new_plugin_market, AppStrings.ui_test_about_software_screen_optimize_the_performance_of_the_notification, AppStrings.ui_test_about_software_screen_fix_the_crash_issue),
            aboutSoftwareChangelogLines(AppStrings.ui_test_about_software_screen_add_plugin_market_optimize_notification_center),
        )
    }

    @Test
    fun compactWindowsUseTighterPadding() {
        assertEquals(16.dp, aboutSoftwareHorizontalPadding(599.dp))
        assertEquals(24.dp, aboutSoftwareHorizontalPadding(600.dp))
        assertEquals(8.dp, aboutSoftwareVerticalPadding(479.dp))
        assertEquals(16.dp, aboutSoftwareVerticalPadding(480.dp))
    }

    @Test
    fun nativeChannelSwitchDoesNotCheck() = runComposeUiTest {
        val channels = mutableListOf<AppUpdateChannel>()
        val checks = mutableListOf<Unit>()
        setContent {
            MaterialTheme {
                AboutSoftwareContent(
                    version = "1.0.0",
                    showCheck = true,
                    snapshot = AppUpdateSnapshot(),
                    channel = AppUpdateChannel.Release,
                    onCheck = { checks += Unit },
                    onChannelChange = channels::add,
                    onOpenLink = {},
                )
            }
        }

        onNodeWithTag("about-software-channel-release").assertIsDisplayed()
        onNodeWithTag("about-software-channel-beta").performClick()
        assertEquals(listOf(AppUpdateChannel.Beta), channels)
        assertEquals(emptyList(), checks)
        onAllNodesWithTag("about-software-open-link").assertCountEquals(0)
    }

    @Test
    fun autoCaptureSwitchIsVisibleAndDoesNotCheckForUpdates() = runComposeUiTest {
        val checks = mutableListOf<Unit>()
        val toggles = mutableListOf<Boolean>()
        setContent {
            MaterialTheme {
                AboutSoftwareContent(
                    version = "1.0.0",
                    showCheck = true,
                    snapshot = AppUpdateSnapshot(),
                    onCheck = { checks += Unit },
                    onOpenLink = {},
                    autoCaptureLogs = false,
                    onAutoCaptureLogsChange = toggles::add,
                )
            }
        }

        onNodeWithTag("about-software-auto-capture").performScrollTo().assertIsDisplayed()
        onNodeWithTag("about-software-auto-capture-switch").assertIsDisplayed().performClick()
        assertEquals(listOf(true), toggles)
        assertEquals(emptyList(), checks)
    }

    @Test
    fun newerUpdateDialogCanBeDismissedAndOpenedAgain() = runComposeUiTest {
        setContent {
            MaterialTheme {
                AboutSoftwareContent(
                    version = "1.0.0",
                    showCheck = true,
                    snapshot = AppUpdateSnapshot(
                        status = AppUpdateStatus.Newer,
                        update = AppUpdate(
                            id = 1L,
                            title = AppStrings.ui_linux_desktop_2_5_0_release,
                            content = AppStrings.settings_about_software_changelog_preview,
                            version = "2.5.0",
                            platform = "linux",
                            link = "https://example.test/app",
                            publishedAtEpochMillis = 1L,
                        ),
                    ),
                    onCheck = {},
                    onOpenLink = {},
                )
            }
        }

        onAllNodesWithTag("about-software-open-link").assertCountEquals(0)
        onNodeWithTag("about-software-newer").performClick()
        onNodeWithTag("about-software-open-link").assertIsDisplayed()
        onNodeWithText(AppStrings.ui_got_it).performClick()
        onAllNodesWithTag("about-software-open-link").assertCountEquals(0)
        onNodeWithTag("about-software-newer").performClick()
        onNodeWithTag("about-software-open-link").assertIsDisplayed()
    }

    @Test
    fun historyItemInvokesCallbackOnEveryPlatform() = runComposeUiTest {
        val opened = mutableListOf<Unit>()
        setContent {
            MaterialTheme {
                AboutSoftwareContent(
                    version = "1.2.3",
                    showCheck = false,
                    snapshot = AppUpdateSnapshot(),
                    onCheck = {},
                    onOpenLink = {},
                    onOpenHistory = { opened += Unit },
                )
            }
        }

        onNodeWithTag("about-software-history").assertIsDisplayed().performClick()
        assertEquals(1, opened.size)
    }

    @Test
    fun externalLinkItemsOpenExpectedUrls() = runComposeUiTest {
        val opened = mutableListOf<String>()
        setContent {
            MaterialTheme {
                AboutSoftwareContent(
                    version = "1.2.3",
                    showCheck = false,
                    snapshot = AppUpdateSnapshot(),
                    onCheck = {},
                    onOpenLink = opened::add,
                )
            }
        }

        onNodeWithTag("about-software-website").performScrollTo().performClick()
        onNodeWithTag("about-software-github").performScrollTo().assertIsDisplayed().performClick()
        onNodeWithTag("about-software-download-other-platforms").performScrollTo().performClick()

        assertEquals(
            listOf(
                "https://www.folderspan.com",
                "https://github.com/Qqv0V6ge/FolderSpan",
                "https://www.folderspan.com/download",
            ),
            opened,
        )
    }

    @Test
    fun legalDocumentsCanBeReviewedFromAboutSoftware() = runComposeUiTest {
        setContent {
            MaterialTheme {
                AboutSoftwareContent(
                    version = "1.2.3",
                    showCheck = false,
                    snapshot = AppUpdateSnapshot(),
                    onCheck = {},
                    onOpenLink = {},
                )
            }
        }

        onNodeWithTag("about-software-user-agreement").performClick()
        onNodeWithText(AppStrings.agreement_user_scope, substring = true).assertIsDisplayed()
        onNodeWithText(AppStrings.ui_got_it).performClick()

        onNodeWithTag("about-software-privacy-policy").performClick()
        onNodeWithText(AppStrings.agreement_privacy_scope, substring = true).assertIsDisplayed()
    }

    @Test
    fun idlePageDoesNotShowUpdateDialog() = runComposeUiTest {
        val checks = mutableListOf<Unit>()
        setContent {
            MaterialTheme {
                AboutSoftwareContent(
                    version = "1.0.0",
                    showCheck = true,
                    snapshot = AppUpdateSnapshot(),
                    onCheck = { checks += Unit },
                    onOpenLink = {},
                )
            }
        }

        onNodeWithTag("about-software-check").assertIsDisplayed()
        onAllNodesWithTag("about-software-newer").assertCountEquals(0)
        onAllNodesWithTag("about-software-open-link").assertCountEquals(0)
        onAllNodesWithTag("about-software-error").assertCountEquals(0)
        assertEquals(emptyList(), checks)
    }

    @Test
    fun idleStatusUsesCheckDescription() {
        assertEquals(
            AppStrings.settings_about_software_check_description,
            aboutSoftwareStatusText(AppUpdateSnapshot()),
        )
    }
}
