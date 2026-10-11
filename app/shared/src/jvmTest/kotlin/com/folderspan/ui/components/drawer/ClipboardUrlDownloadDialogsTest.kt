package com.folderspan.ui.components.drawer

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.v2.runComposeUiTest
import com.folderspan.service.http.clipboard.ClipboardUrlDownloadError
import com.folderspan.service.http.clipboard.ClipboardUrlDownloadPlatformCapabilities
import com.folderspan.service.http.clipboard.ClipboardUrlDownloadProgress
import com.folderspan.service.http.clipboard.ClipboardUrlDownloadResult
import com.folderspan.service.http.clipboard.ClipboardUrlDownloadState
import com.folderspan.service.http.clipboard.ClipboardUrlDownloadTaskConfig
import com.folderspan.service.http.clipboard.ClipboardUrlDownloader
import com.folderspan.ui.state.file.ClipboardUrlDownloadCoordinator
import com.folderspan.ui.state.file.ClipboardUrlDownloadResultPresenter
import com.folderspan.ui.state.main.Task
import com.folderspan.ui.state.main.TaskRuntimePersistenceStore
import com.folderspan.ui.state.main.TaskState
import com.folderspan.ui.state.main.TempTaskRuntimePersistenceStore
import kotlinx.coroutines.Dispatchers
import strings.AppStrings
import kotlin.test.Test
import kotlin.test.assertEquals

@OptIn(ExperimentalTestApi::class)
class ClipboardUrlDownloadDialogsTest {
    @Test
    fun missingFileSizeHasDedicatedFallbackMessage() {
        assertEquals(
            AppStrings.ui_clipboard_url_error_file_size,
            clipboardUrlDownloadErrorMessage(ClipboardUrlDownloadError.MissingFileSize),
        )
    }

    @Test
    fun settingsDoNotRequestBeforeConfirmAndSubmitModifiedValues() = runComposeUiTest {
        val downloader = RecordingDownloader()
        val coordinator = createCoordinator(downloader, NATIVE_CAPABILITIES)
        coordinator.openUrl("https://example.test/file.bin", "https://example.test/file.bin")

        setContent {
            val state by coordinator.state.collectAsState()
            MaterialTheme {
                ClipboardUrlDownloadDialogHost(state, coordinator)
            }
        }

        assertEquals(0, downloader.requests)
        onNodeWithTag(CLIPBOARD_URL_RETRIES_TAG).performTextClearance()
        onNodeWithTag(CLIPBOARD_URL_RETRIES_TAG).performTextInput("4")
        onNodeWithTag(CLIPBOARD_URL_COOKIE_TAG).performTextInput("session=test")
        onNodeWithTag(CLIPBOARD_URL_USER_AGENT_TAG).performTextClearance()
        onNodeWithTag(CLIPBOARD_URL_USER_AGENT_TAG).performTextInput("TestAgent/1")
        onNodeWithTag(CLIPBOARD_URL_AUTOMATIC_HEADERS_TAG).performClick()
        onNodeWithTag(CLIPBOARD_URL_THREADS_TAG).performTextClearance()
        onNodeWithTag(CLIPBOARD_URL_THREADS_TAG).performTextInput("3")

        assertEquals(0, downloader.requests)
        onNodeWithTag(CLIPBOARD_URL_CONFIRM_TAG).performClick()
        waitUntil { downloader.requests == 1 }

        assertEquals(
            ConfigSnapshot(
                retries = 4,
                cookie = "session=test",
                userAgent = "TestAgent/1",
                automaticHeaders = false,
                threads = 3,
            ),
            downloader.lastConfig,
        )
        coordinator.teardown()
    }

    @Test
    fun browserSettingsExposeRestrictionsWithoutDisablingThreadControl() = runComposeUiTest {
        val coordinator = createCoordinator(RecordingDownloader(), BROWSER_CAPABILITIES)
        coordinator.openUrl("https://example.test/file.bin", "https://example.test/file.bin")

        setContent {
            val state by coordinator.state.collectAsState()
            MaterialTheme {
                ClipboardUrlDownloadDialogHost(state, coordinator)
            }
        }

        onNodeWithTag(CLIPBOARD_URL_COOKIE_TAG).assertIsNotEnabled()
        onNodeWithTag(CLIPBOARD_URL_USER_AGENT_TAG).assertIsNotEnabled()
        onNodeWithTag(CLIPBOARD_URL_AUTOMATIC_HEADERS_TAG).assertIsNotEnabled().assertIsOn()
        onNodeWithText(AppStrings.ui_clipboard_url_download_browser_cookie_hint).assertIsDisplayed()
        onAllNodesWithText(AppStrings.ui_clipboard_url_download_browser_headers_hint).assertCountEquals(2)
        coordinator.teardown()
    }

    @Test
    fun fallbackCopiesExactRawTextThroughInjectedClipboardAction() = runComposeUiTest {
        val rawText = AppStrings.ui_test_clipboard_url_download_dialogs_https_example_test_file_bin
        var copiedText: String? = null
        val coordinator = createCoordinator(RecordingDownloader(), NATIVE_CAPABILITIES)

        setContent {
            MaterialTheme {
                ClipboardUrlDownloadDialogHost(
                    state = ClipboardUrlDownloadState.Fallback(rawText, ClipboardUrlDownloadError.HtmlContent),
                    coordinator = coordinator,
                    copyText = { text ->
                        copiedText = text
                        true
                    },
                )
            }
        }

        onNodeWithText(rawText).assertIsDisplayed()
        onNodeWithTag(CLIPBOARD_CONTENT_COPY_TAG).performClick()
        waitUntil { copiedText != null }
        assertEquals(rawText, copiedText)
        onNodeWithText(AppStrings.ui_copied).assertIsDisplayed()
        coordinator.teardown()
    }

    @Test
    fun downloadingStateDoesNotRenderAnIndependentPopup() = runComposeUiTest {
        val coordinator = createCoordinator(RecordingDownloader(), NATIVE_CAPABILITIES)
        val progress = ClipboardUrlDownloadProgress(
            bytesReceived = 8L,
            totalBytes = 32L,
            attempt = 1,
            maxAttempts = 1,
        )

        setContent {
            MaterialTheme {
                ClipboardUrlDownloadDialogHost(ClipboardUrlDownloadState.Downloading(progress), coordinator)
            }
        }

        onAllNodesWithText(AppStrings.ui_clipboard_url_download_downloading).assertCountEquals(0)
        onAllNodesWithText(AppStrings.ui_cancel).assertCountEquals(0)
        coordinator.teardown()
    }
}

private class RecordingDownloader : ClipboardUrlDownloader {
    var requests: Int = 0
        private set
    var lastConfig: ConfigSnapshot? = null
        private set

    override suspend fun download(
        config: ClipboardUrlDownloadTaskConfig,
        onProgress: suspend (ClipboardUrlDownloadProgress) -> Unit,
        onRetry: suspend (attempt: Int, delayMillis: Long) -> Unit,
    ): ClipboardUrlDownloadResult {
        requests++
        lastConfig = ConfigSnapshot(
            retries = config.retries,
            cookie = config.cookie,
            userAgent = config.userAgent,
            automaticHeaders = config.automaticHeaders,
            threads = config.threads,
        )
        return ClipboardUrlDownloadResult.Failure(
            error = ClipboardUrlDownloadError.HttpFailure,
            rawText = config.rawText,
            safeReason = "test failure",
        )
    }
}

private data class ConfigSnapshot(
    val retries: Int,
    val cookie: String,
    val userAgent: String,
    val automaticHeaders: Boolean,
    val threads: Int,
)

private fun createCoordinator(
    downloader: ClipboardUrlDownloader,
    capabilities: ClipboardUrlDownloadPlatformCapabilities,
): ClipboardUrlDownloadCoordinator = ClipboardUrlDownloadCoordinator(
    engine = downloader,
    taskState = TaskState(
        runtimeStore = object : TaskRuntimePersistenceStore by TempTaskRuntimePersistenceStore() {
            override fun loadTaskSnapshots(): List<Task> = emptyList()
            override fun saveTaskSnapshot(task: Task) = Unit
            override fun deleteTaskSnapshot(taskKey: Long) = Unit
        },
    ),
    resultPresenter = ClipboardUrlDownloadResultPresenter { false },
    uiDispatcher = Dispatchers.Unconfined,
    capabilities = capabilities,
)

private val NATIVE_CAPABILITIES = ClipboardUrlDownloadPlatformCapabilities(
    platformName = "JVM",
    canSetCookie = true,
    canSetUserAgent = true,
    canControlAutomaticHeaders = true,
    browserCredentialsOmitted = false,
)

private val BROWSER_CAPABILITIES = ClipboardUrlDownloadPlatformCapabilities(
    platformName = "Web",
    canSetCookie = false,
    canSetUserAgent = false,
    canControlAutomaticHeaders = false,
    browserCredentialsOmitted = true,
)
