package com.folderspan.pro.presentation.screen.feedback

import com.folderspan.pro.core.common.ApiResult
import com.folderspan.pro.core.datastore.AuthSession
import com.folderspan.pro.core.datastore.SessionManager
import com.folderspan.pro.domain.model.FeedbackAttachment
import com.folderspan.pro.domain.model.FeedbackDownload
import com.folderspan.pro.domain.model.FeedbackPage
import com.folderspan.pro.domain.model.FeedbackTransferProgress
import com.folderspan.pro.domain.model.FeedbackUpload
import com.folderspan.pro.domain.usecase.FeedbackSessionService
import com.folderspan.utils.ErrorLogFeedbackPayload
import com.folderspan.pro.testing.FakeFeedbackRepository
import com.folderspan.pro.testing.FakeUserRepository
import com.folderspan.pro.testing.feedbackCase
import com.folderspan.pro.testing.feedbackSummary
import com.folderspan.ui.components.pagestate.PageAppendState
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.*
import kotlinx.datetime.TimeZone
import org.junit.Rule
import org.junit.rules.TestWatcher
import org.junit.runner.Description
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class FeedbackViewModelTest {
    @get:Rule
    val mainDispatcherRule = FeedbackMainDispatcherRule()

    @AfterTest
    fun tearDown() {
        SessionManager.clear()
        ErrorLogFeedbackLaunch.pending = null
    }

    @Test
    fun formValidatesContentAndPreventsDuplicateSubmissionWhilePreservingFailureInput() = runTest {
        val repository = FakeFeedbackRepository()
        val gate = CompletableDeferred<Unit>()
        repository.submitHandler = { _, _ -> gate.await(); ApiResult.Failure("try later") }
        val viewModel = FeedbackFormViewModel(
            repository = repository,
            sessionService = FeedbackSessionService(repository, FakeUserRepository(), sessionProvider = { null }),
            onUnauthorized = {},
            metadata = FeedbackClientMetadata("2.0.0", "linux"),
        )

        viewModel.submit()
        assertNotNull(viewModel.state.value.contentErrorMessage)
        viewModel.onContentChange("important details")
        viewModel.submit()
        viewModel.submit()
        runCurrent()

        assertEquals(1, repository.submittedDrafts.size)
        assertEquals("2.0.0", repository.submittedDrafts.single().appVersion)
        gate.complete(Unit)
        advanceUntilIdle()
        assertEquals("important details", viewModel.state.value.content)
        assertEquals("try later", viewModel.state.value.formErrorMessage)
        assertFalse(viewModel.state.value.isSubmitting)
    }

    @Test
    fun launchPayloadPrefillsContentAndUploadsLogAfterSubmit() = runTest {
        val repository = FakeFeedbackRepository()
        val uploaded = mutableListOf<Pair<String, String>>()
        repository.submitHandler = { _, _ -> ApiResult.Success("ticket-log") }
        repository.uploadHandler = { uuid, upload, _, _ ->
            uploaded += uuid to upload.fileName
            ApiResult.Success(
                FeedbackAttachment(
                    uuid = "att-1",
                    originalName = upload.fileName,
                    contentType = upload.contentType,
                    size = upload.bytes.size.toLong(),
                    createdAt = "now",
                ),
            )
        }
        val payload = ErrorLogFeedbackPayload(
            summary = "copy failed",
            logBytes = "log-line\n".encodeToByteArray(),
        )
        val viewModel = FeedbackFormViewModel(
            repository = repository,
            sessionService = FeedbackSessionService(repository, FakeUserRepository(), sessionProvider = { null }),
            onUnauthorized = {},
            metadata = FeedbackClientMetadata("2.0.0", "linux"),
        )
        viewModel.applyLaunchPayload(payload)
        viewModel.onSessionChanged(AuthSession("token"))

        assertEquals("copy failed", viewModel.state.value.content)
        viewModel.submit()
        advanceUntilIdle()

        assertEquals("ticket-log", viewModel.state.value.submittedReference)
        assertEquals(listOf("ticket-log" to "folderspan-error.log"), uploaded)
        assertTrue(payload.logBytes.size <= 10L * 1024L * 1024L)
    }

    @Test
    fun listDeduplicatesPagesAndRetainsStaleDataWhenRefreshFails() = runTest {
        val repository = FakeFeedbackRepository()
        var call = 0
        repository.listHandler = { query, _ ->
            call++
            when (call) {
                1 -> ApiResult.Success(FeedbackPage(listOf(feedbackSummary("one"), feedbackSummary("two")), 3))
                2 -> ApiResult.Success(FeedbackPage(listOf(feedbackSummary("two"), feedbackSummary("three")), 3))
                else -> ApiResult.Failure("offline")
            }
        }
        val viewModel = FeedbackTicketListViewModel(
            FeedbackSessionService(repository, FakeUserRepository(), sessionProvider = { null }),
            onUnauthorized = {},
        )
        viewModel.onSessionChanged(AuthSession("token"))

        viewModel.load(refresh = true)
        advanceUntilIdle()
        viewModel.load(refresh = false)
        advanceUntilIdle()

        assertEquals(listOf("one", "two", "three"), viewModel.state.value.items.map { it.uuid })
        assertFalse(viewModel.state.value.hasMore)

        viewModel.load(refresh = true)
        advanceUntilIdle()
        assertEquals(listOf("one", "two", "three"), viewModel.state.value.items.map { it.uuid })
        assertEquals("offline", viewModel.state.value.refreshMessage)
    }

    @Test
    fun listKeepsLoadedItemsAndSurfacesAppendErrorWhenLoadMoreFails() = runTest {
        val repository = FakeFeedbackRepository()
        var call = 0
        repository.listHandler = { _, _ ->
            call++
            when (call) {
                1 -> ApiResult.Success(FeedbackPage(listOf(feedbackSummary("one"), feedbackSummary("two")), 4))
                else -> ApiResult.Failure("page 2 down")
            }
        }
        val viewModel = FeedbackTicketListViewModel(
            FeedbackSessionService(repository, FakeUserRepository(), sessionProvider = { null }),
            onUnauthorized = {},
        )
        viewModel.onSessionChanged(AuthSession("token"))

        viewModel.load(refresh = true)
        advanceUntilIdle()
        viewModel.load(refresh = false)
        advanceUntilIdle()

        assertEquals(listOf("one", "two"), viewModel.state.value.items.map { it.uuid })
        assertEquals("page 2 down", viewModel.state.value.errorMessage)
        assertEquals(null, viewModel.state.value.refreshMessage)
        assertEquals(
            PageAppendState.Error("page 2 down"),
            viewModel.state.value.toPageAppendState(),
        )
    }

    @Test
    fun detailRefreshesAfterWorkflowAndRetainsLastServerStateOnRefreshFailure() = runTest {
        val repository = FakeFeedbackRepository()
        var detailCall = 0
        repository.detailHandler = { uuid, _ ->
            detailCall++
            when (detailCall) {
                1 -> ApiResult.Success(feedbackCase(uuid))
                2 -> ApiResult.Success(feedbackCase(uuid).copy(feedback = feedbackSummary(uuid, status = "waiting_user")))
                else -> ApiResult.Failure("refresh failed")
            }
        }
        val viewModel = FeedbackTicketDetailViewModel(
            uuid = "ticket",
            sessionService = FeedbackSessionService(repository, FakeUserRepository(), sessionProvider = { null }),
            onUnauthorized = {},
        )
        viewModel.onSessionChanged(AuthSession("token"))
        viewModel.load()
        advanceUntilIdle()

        viewModel.onSupplementContentChange("more context")
        viewModel.supplement()
        advanceUntilIdle()

        assertEquals("waiting_user", viewModel.state.value.ticket?.feedback?.status)
        assertEquals("", viewModel.state.value.supplementContent)

        viewModel.load(force = true)
        advanceUntilIdle()
        assertEquals("waiting_user", viewModel.state.value.ticket?.feedback?.status)
        assertEquals("refresh failed", viewModel.state.value.refreshMessage)
    }

    @Test
    fun cancelStopsInFlightDetailOperationAndRestoresIdleState() = runTest {
        val repository = FakeFeedbackRepository().apply {
            detailHandler = { uuid, _ -> ApiResult.Success(feedbackCase(uuid)) }
        }
        val started = CompletableDeferred<Unit>()
        val cancelled = CompletableDeferred<Unit>()
        repository.supplementHandler = { _, _, _ ->
            started.complete(Unit)
            try {
                awaitCancellation()
            } finally {
                cancelled.complete(Unit)
            }
        }
        val viewModel = FeedbackTicketDetailViewModel(
            uuid = "ticket",
            sessionService = FeedbackSessionService(repository, FakeUserRepository(), sessionProvider = { null }),
            onUnauthorized = {},
        )
        viewModel.onSessionChanged(AuthSession("token"))
        viewModel.load()
        advanceUntilIdle()
        viewModel.onSupplementContentChange("more context")

        viewModel.supplement()
        runCurrent()
        started.await()
        assertEquals(FeedbackDetailOperation.Supplement, viewModel.state.value.operation)
        viewModel.cancelOperation()
        runCurrent()

        assertTrue(cancelled.isCompleted)
        assertEquals(null, viewModel.state.value.operation)
        assertEquals("more context", viewModel.state.value.supplementContent)
    }

    @Test
    fun destinationSelectionCancelDoesNotStartDownload() = runTest {
        var downloadCalls = 0
        val repository = FakeFeedbackRepository().apply {
            downloadHandler = { _, _, _, _ ->
                downloadCalls++
                ApiResult.Failure("unexpected")
            }
        }
        val viewModel = detailViewModel(repository)
        viewModel.onSessionChanged(AuthSession("token"))

        viewModel.beginDownloadDestinationSelection("attachment", "diagnostic.log")
        assertEquals(
            FeedbackAttachmentTransferPhase.SelectingDestination,
            viewModel.state.value.attachmentTransfer?.phase,
        )
        viewModel.cancelDownloadDestinationSelection()
        advanceUntilIdle()

        assertEquals(0, downloadCalls)
        assertEquals(null, viewModel.state.value.attachmentTransfer)
    }

    @Test
    fun downloadFallsBackToSanitizedMetadataNameAndPublishesDownloadAndSavePhases() = runTest {
        val observedNetworkProgress = mutableListOf<FeedbackTransferProgress>()
        val networkGate = CompletableDeferred<Unit>()
        val saveGate = CompletableDeferred<Unit>()
        val repository = FakeFeedbackRepository().apply {
            downloadHandler = { _, _, _, onProgress ->
                onProgress(FeedbackTransferProgress(0, 3))
                onProgress(FeedbackTransferProgress(3, 3))
                observedNetworkProgress += FeedbackTransferProgress(3, 3)
                networkGate.await()
                ApiResult.Success(FeedbackDownload(fileName = null, contentType = "text/plain", bytes = byteArrayOf(1)))
            }
        }
        val viewModel = detailViewModel(repository)
        viewModel.onSessionChanged(AuthSession("token"))
        var savedName: String? = null
        val destination = FeedbackDownloadDestination("/exports")
        val saver = FeedbackAttachmentTransferSaver { actualDestination, download, onProgress ->
            assertEquals(destination, actualDestination)
            savedName = download.fileName
            onProgress(FeedbackTransferProgress(0, download.bytes.size.toLong()))
            saveGate.await()
            onProgress(FeedbackTransferProgress(download.bytes.size.toLong(), download.bytes.size.toLong()))
            Result.success(true)
        }

        viewModel.beginDownloadDestinationSelection("attachment", "../diagnostic.log")
        viewModel.confirmDownloadDestination(destination, saver)
        assertEquals(
            FeedbackAttachmentTransferPhase.Preparing,
            viewModel.state.value.attachmentTransfer?.phase,
        )
        runCurrent()
        assertEquals(
            FeedbackAttachmentTransferPhase.Transferring,
            viewModel.state.value.attachmentTransfer?.phase,
        )
        networkGate.complete(Unit)
        runCurrent()
        assertEquals(
            FeedbackAttachmentTransferPhase.Saving,
            viewModel.state.value.attachmentTransfer?.phase,
        )
        saveGate.complete(Unit)
        advanceUntilIdle()

        val transfer = assertNotNull(viewModel.state.value.attachmentTransfer)
        assertEquals("diagnostic.log", savedName)
        assertEquals(destination, transfer.destination)
        assertEquals(FeedbackAttachmentTransferPhase.Completed, transfer.phase)
        assertTrue(transfer.completed)
        assertTrue(observedNetworkProgress.isNotEmpty())
        assertEquals(strings.AppStrings.ui_feedback_attachment_saved, viewModel.state.value.refreshMessage)
    }

    @Test
    fun uploadOwnsSingleTransferJobPublishesMonotonicProgressAndRefreshesAfterSuccess() = runTest {
        val gate = CompletableDeferred<Unit>()
        var detailCalls = 0
        val repository = FakeFeedbackRepository().apply {
            detailHandler = { uuid, _ ->
                detailCalls++
                ApiResult.Success(feedbackCase(uuid))
            }
            uploadHandler = { _, upload, _, onProgress ->
                onProgress(FeedbackTransferProgress(0, upload.bytes.size.toLong()))
                onProgress(FeedbackTransferProgress(300_000, upload.bytes.size.toLong()))
                onProgress(FeedbackTransferProgress(100_000, upload.bytes.size.toLong()))
                gate.await()
                onProgress(FeedbackTransferProgress(upload.bytes.size.toLong(), upload.bytes.size.toLong()))
                ApiResult.Success(
                    FeedbackAttachment("uploaded", upload.fileName, upload.contentType, upload.bytes.size.toLong(), "now"),
                )
            }
        }
        val viewModel = detailViewModel(repository)
        viewModel.onSessionChanged(AuthSession("token"))
        val upload = FeedbackUpload("trace.log", "text/plain", ByteArray(400_000))

        viewModel.upload(upload)
        runCurrent()
        assertEquals(FeedbackAttachmentTransferKind.Upload, viewModel.state.value.attachmentTransfer?.kind)
        assertEquals(300_000, viewModel.state.value.attachmentTransfer?.transferredBytes)

        viewModel.beginDownloadDestinationSelection("other", "other.log")
        assertEquals(FeedbackAttachmentTransferKind.Upload, viewModel.state.value.attachmentTransfer?.kind)

        gate.complete(Unit)
        advanceUntilIdle()
        val transfer = assertNotNull(viewModel.state.value.attachmentTransfer)
        assertEquals(FeedbackAttachmentTransferPhase.Completed, transfer.phase)
        assertEquals(400_000, transfer.transferredBytes)
        assertEquals(1, detailCalls)
    }

    @Test
    fun cancellingTransferCancelsWorkAndPublishesTerminalCancellation() = runTest {
        val started = CompletableDeferred<Unit>()
        val cancelled = CompletableDeferred<Unit>()
        val repository = FakeFeedbackRepository().apply {
            uploadHandler = { _, _, _, _ ->
                started.complete(Unit)
                try {
                    awaitCancellation()
                } finally {
                    cancelled.complete(Unit)
                }
            }
        }
        val viewModel = detailViewModel(repository)
        viewModel.onSessionChanged(AuthSession("token"))

        viewModel.upload(FeedbackUpload("trace.log", "text/plain", byteArrayOf(1)))
        runCurrent()
        started.await()
        viewModel.cancelAttachmentTransfer()
        runCurrent()

        assertTrue(cancelled.isCompleted)
        val transfer = assertNotNull(viewModel.state.value.attachmentTransfer)
        assertEquals(FeedbackAttachmentTransferPhase.Cancelled, transfer.phase)
        assertTrue(transfer.cancelled)
    }

    @Test
    fun transferFailureIsAssociatedWithTheAttachmentAction() = runTest {
        val repository = FakeFeedbackRepository().apply {
            uploadHandler = { _, _, _, _ -> ApiResult.Failure("network down") }
        }
        val viewModel = detailViewModel(repository)
        viewModel.onSessionChanged(AuthSession("token"))

        viewModel.upload(FeedbackUpload("trace.log", "text/plain", byteArrayOf(1)))
        advanceUntilIdle()

        val transfer = assertNotNull(viewModel.state.value.attachmentTransfer)
        assertEquals(FeedbackAttachmentTransferPhase.Failed, transfer.phase)
        assertEquals("network down", transfer.failureMessage)
    }

    @Test
    fun failedUploadCanRetryTheSameAttachmentWithoutSelectingItAgain() = runTest {
        var uploadCalls = 0
        val uploadedFileNames = mutableListOf<String>()
        val repository = FakeFeedbackRepository().apply {
            detailHandler = { uuid, _ -> ApiResult.Success(feedbackCase(uuid)) }
            uploadHandler = { _, upload, _, _ ->
                uploadCalls++
                uploadedFileNames += upload.fileName
                if (uploadCalls == 1) {
                    ApiResult.Failure("network down")
                } else {
                    ApiResult.Success(
                        FeedbackAttachment(
                            uuid = "uploaded",
                            originalName = upload.fileName,
                            contentType = upload.contentType,
                            size = upload.bytes.size.toLong(),
                            createdAt = "now",
                        ),
                    )
                }
            }
        }
        val viewModel = detailViewModel(repository)
        viewModel.onSessionChanged(AuthSession("token"))

        viewModel.upload(FeedbackUpload("trace.log", "text/plain", byteArrayOf(1)))
        advanceUntilIdle()
        assertEquals(FeedbackAttachmentTransferPhase.Failed, viewModel.state.value.attachmentTransfer?.phase)

        viewModel.retryUploadAttachment()
        advanceUntilIdle()

        assertEquals(2, uploadCalls)
        assertEquals(listOf("trace.log", "trace.log"), uploadedFileNames)
        assertEquals(FeedbackAttachmentTransferPhase.Completed, viewModel.state.value.attachmentTransfer?.phase)
    }

    @Test
    fun deleteAttachmentPassesIdentifiersAndRefreshesAttachmentList() = runTest {
        val attachment = FeedbackAttachment(
            uuid = "attachment",
            originalName = "diagnostic.log",
            contentType = "text/plain",
            size = 3,
            createdAt = "now",
        )
        var detailCall = 0
        var deletedArguments: Triple<String, String, String>? = null
        val repository = FakeFeedbackRepository().apply {
            detailHandler = { uuid, _ ->
                detailCall++
                ApiResult.Success(
                    feedbackCase(uuid).copy(
                        attachments = if (detailCall == 1) listOf(attachment) else emptyList(),
                    ),
                )
            }
            deleteAttachmentHandler = { uuid, attachmentUuid, token ->
                deletedArguments = Triple(uuid, attachmentUuid, token)
                ApiResult.Success(true)
            }
        }
        val viewModel = FeedbackTicketDetailViewModel(
            uuid = "ticket",
            sessionService = FeedbackSessionService(repository, FakeUserRepository(), sessionProvider = { null }),
            onUnauthorized = {},
        )
        viewModel.onSessionChanged(AuthSession("token"))
        viewModel.load()
        advanceUntilIdle()

        viewModel.deleteAttachment("attachment")
        advanceUntilIdle()

        assertEquals(Triple("ticket", "attachment", "token"), deletedArguments)
        assertTrue(viewModel.state.value.ticket?.attachments.orEmpty().isEmpty())
    }

    @Test
    fun unknownServerValuesRemainReadableAndBounded() {
        assertEquals("future state", feedbackStatusLabel("future_state"))
        assertEquals("custom", feedbackPriorityLabel("custom"))
        assertEquals(strings.AppStrings.ui_feedback, feedbackTypeLabel("feedback"))
        assertEquals(strings.AppStrings.ui_feedback_event_status_changed, feedbackEventLabel("status_changed"))
        assertTrue(safeFeedbackServerLabel("x".repeat(200)).length <= 80)
    }

    @Test
    fun feedbackTimestampsUseReadableLocalDateTimeFormat() {
        val shanghai = TimeZone.of("Asia/Shanghai")

        assertEquals(
            "2026-08-11 01:01:52",
            formatFeedbackDateTime("2026-08-10T17:01:52Z", shanghai),
        )
        assertEquals("server-time", formatFeedbackDateTime("server-time", shanghai))
        assertEquals(strings.AppStrings.ui_unknown, formatFeedbackDateTime("", shanghai))
    }

    private fun detailViewModel(repository: FakeFeedbackRepository) = FeedbackTicketDetailViewModel(
        uuid = "ticket",
        sessionService = FeedbackSessionService(repository, FakeUserRepository(), sessionProvider = { null }),
        onUnauthorized = {},
    )
}

@OptIn(ExperimentalCoroutinesApi::class)
class FeedbackMainDispatcherRule(
    private val dispatcher: TestDispatcher = StandardTestDispatcher(),
) : TestWatcher() {
    override fun starting(description: Description) {
        Dispatchers.setMain(dispatcher)
    }

    override fun finished(description: Description) {
        Dispatchers.resetMain()
    }
}
