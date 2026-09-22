package com.folderspan.pro.presentation.screen.feedback

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.folderspan.pro.core.common.ApiResult
import com.folderspan.pro.core.datastore.AuthSession
import com.folderspan.pro.core.datastore.SessionManager
import com.folderspan.pro.domain.model.FeedbackAttachment
import com.folderspan.pro.domain.model.FeedbackCategory
import com.folderspan.pro.domain.model.FeedbackEvent
import com.folderspan.pro.domain.model.FeedbackPage
import com.folderspan.pro.domain.usecase.FeedbackSessionService
import com.folderspan.pro.testing.FakeFeedbackRepository
import com.folderspan.pro.testing.FakeUserRepository
import com.folderspan.pro.testing.feedbackCase
import com.folderspan.pro.testing.feedbackSummary
import strings.AppStrings
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@OptIn(ExperimentalTestApi::class)
class FeedbackPageComposeTest {
    @AfterTest
    fun tearDown() {
        SessionManager.clear()
    }

    @Test
    fun compactFormCanScrollToPrimaryAction() = runComposeUiTest {
        setContent {
            MaterialTheme {
                Box(Modifier.size(392.dp, 544.dp)) {
                    FeedbackHomePage(
                        state = FeedbackFormUiState(),
                        onNavigateBack = {},
                        onOpenTickets = {},
                        onTypeChange = {},
                        onCategoryChange = {},
                        onContentChange = {},
                        onContactChange = {},
                        onRetryCategories = {},
                        onSubmit = {},
                        onSubmitAnother = {},
                    )
                }
            }
        }

        onNodeWithText(AppStrings.ui_feedback_submit)
            .performScrollTo()
            .assertIsDisplayed()
    }

    @Test
    fun compactFormWrapsAllCategoryChips() = runComposeUiTest {
        val categories = listOf(
            FeedbackCategory("bug", "Bug or failure"),
            FeedbackCategory("feature", "Feature request"),
            FeedbackCategory("experience", "Interface experience"),
            FeedbackCategory("performance", "Performance diagnostics"),
        )
        setContent {
            MaterialTheme {
                Box(Modifier.size(392.dp, 800.dp)) {
                    FeedbackHomePage(
                        state = FeedbackFormUiState(categories = categories),
                        onNavigateBack = {},
                        onOpenTickets = {},
                        onTypeChange = {},
                        onCategoryChange = {},
                        onContentChange = {},
                        onContactChange = {},
                        onRetryCategories = {},
                        onSubmit = {},
                        onSubmitAnother = {},
                    )
                }
            }
        }

        onNodeWithText("Performance diagnostics").assertIsDisplayed()
    }

    @Test
    fun formAcceptsKeyboardInputAndSubmitsThroughPrimaryAction() = runComposeUiTest {
        var state by mutableStateOf(FeedbackFormUiState())
        var submitCount = 0
        setContent {
            MaterialTheme {
                FeedbackHomePage(
                    state = state,
                    onNavigateBack = {},
                    onOpenTickets = {},
                    onTypeChange = { state = state.copy(type = it) },
                    onCategoryChange = { state = state.copy(selectedCategory = it) },
                    onContentChange = { state = state.copy(content = it) },
                    onContactChange = { state = state.copy(contact = it) },
                    onRetryCategories = {},
                    onSubmit = { submitCount++ },
                    onSubmitAnother = {},
                )
            }
        }

        onNodeWithText(AppStrings.ui_feedback_content)
            .performClick()
            .assertIsFocused()
            .performTextInput("A reproducible issue")
        onNodeWithText(AppStrings.ui_feedback_contact_optional).performTextInput("contact@example.test")
        onNodeWithText(AppStrings.ui_feedback_submit).performClick()

        assertEquals("A reproducible issue", state.content)
        assertEquals("contact@example.test", state.contact)
        assertEquals(1, submitCount)
    }

    @Test
    fun formShowsValidationInDarkThemeWithLabeledNavigation() = runComposeUiTest {
        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                FeedbackHomePage(
                    state = FeedbackFormUiState(contentErrorMessage = AppStrings.ui_feedback_content_required),
                    onNavigateBack = {},
                    onOpenTickets = {},
                    onTypeChange = {},
                    onCategoryChange = {},
                    onContentChange = {},
                    onContactChange = {},
                    onRetryCategories = {},
                    onSubmit = {},
                    onSubmitAnother = {},
                )
            }
        }

        onNodeWithText(AppStrings.ui_feedback_content_required).assertIsDisplayed()
        onNodeWithContentDescription(AppStrings.ui_return).assertIsDisplayed()
        onNodeWithText(AppStrings.ui_feedback_submit).assertHasClickAction()
    }

    @Test
    fun detailRequiresConfirmationBeforeDelete() = runComposeUiTest {
        var deleteCount = 0
        setContent {
            MaterialTheme {
                FeedbackTicketDetailPane(
                    state = FeedbackTicketDetailUiState(
                        uuid = "ticket",
                        ticket = feedbackCase("ticket"),
                        editContent = "content",
                    ),
                    onRefresh = {},
                    onEditContentChange = {},
                    onUpdate = {},
                    onDelete = { deleteCount++ },
                    onSupplementContentChange = {},
                    onSupplement = {},
                    onWithdrawalReasonChange = {},
                    onWithdraw = {},
                    onUploadAttachment = {},
                    onDownloadAttachment = { _, _ -> },
                    onCancelOperation = {},
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }

        onNodeWithText(AppStrings.ui_feedback_delete).performScrollTo().performClick()
        onNodeWithText(AppStrings.ui_feedback_confirm_delete_title).assertIsDisplayed()
        assertEquals(0, deleteCount)
        onAllNodesWithText(AppStrings.ui_feedback_delete).onLast().performClick()
        assertEquals(1, deleteCount)
    }

    @Test
    fun attachmentDownloadActionHasScreenReaderLabel() = runComposeUiTest {
        val ticket = feedbackCase("ticket").copy(
            attachments = listOf(
                FeedbackAttachment(
                    uuid = "attachment",
                    originalName = "diagnostic.log",
                    contentType = "text/plain",
                    size = 3,
                    createdAt = "now",
                ),
            ),
        )
        setContent {
            MaterialTheme {
                FeedbackTicketDetailPane(
                    state = FeedbackTicketDetailUiState(uuid = "ticket", ticket = ticket, editContent = "content"),
                    onRefresh = {},
                    onEditContentChange = {},
                    onUpdate = {},
                    onDelete = {},
                    onSupplementContentChange = {},
                    onSupplement = {},
                    onWithdrawalReasonChange = {},
                    onWithdraw = {},
                    onUploadAttachment = {},
                    onDownloadAttachment = { _, _ -> },
                    onCancelOperation = {},
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }

        onNodeWithContentDescription(AppStrings.ui_feedback_download_attachment)
            .performScrollTo()
            .assertIsDisplayed()
    }

    @Test
    fun attachmentSectionShowsSupportedTypesAndConfirmsWithSnackbarBeforeDelete() = runComposeUiTest {
        var deletedAttachmentUuid: String? = null
        val ticket = feedbackCase("ticket").copy(
            attachments = listOf(
                FeedbackAttachment(
                    uuid = "attachment",
                    originalName = "diagnostic.log",
                    contentType = "text/plain",
                    size = 3,
                    createdAt = "now",
                ),
            ),
        )
        setContent {
            MaterialTheme {
                FeedbackTicketDetailPane(
                    state = FeedbackTicketDetailUiState(uuid = "ticket", ticket = ticket, editContent = "content"),
                    onRefresh = {},
                    onEditContentChange = {},
                    onUpdate = {},
                    onDelete = {},
                    onSupplementContentChange = {},
                    onSupplement = {},
                    onWithdrawalReasonChange = {},
                    onWithdraw = {},
                    onUploadAttachment = {},
                    onDownloadAttachment = { _, _ -> },
                    onDeleteAttachment = { deletedAttachmentUuid = it },
                    onCancelOperation = {},
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }

        val supportedTypes = AppStrings.ui_feedback_attachment_supported_types_arg0.format(
            arg0 = supportedFeedbackAttachmentExtensionsLabel(),
        )
        onNodeWithText(supportedTypes).performScrollTo().assertIsDisplayed()
        onNodeWithContentDescription(AppStrings.ui_feedback_delete_attachment)
            .performScrollTo()
            .performClick()
        onNodeWithText(
            AppStrings.ui_feedback_confirm_delete_attachment_message_arg0.format(arg0 = "diagnostic.log"),
        ).assertIsDisplayed()
        assertEquals(null, deletedAttachmentUuid)
        onNodeWithText(AppStrings.ui_delete).performClick()
        runOnIdle { assertEquals("attachment", deletedAttachmentUuid) }
    }

    @Test
    fun emptyAttachmentStateShowsIconAndVerticalPadding() = runComposeUiTest {
        setContent {
            MaterialTheme {
                FeedbackTicketDetailPane(
                    state = FeedbackTicketDetailUiState(
                        uuid = "ticket",
                        ticket = feedbackCase("ticket"),
                        editContent = "content",
                    ),
                    onRefresh = {},
                    onEditContentChange = {},
                    onUpdate = {},
                    onDelete = {},
                    onSupplementContentChange = {},
                    onSupplement = {},
                    onWithdrawalReasonChange = {},
                    onWithdraw = {},
                    onUploadAttachment = {},
                    onDownloadAttachment = { _, _ -> },
                    onCancelOperation = {},
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }

        val emptyState = onNodeWithTag(FeedbackAttachmentEmptyStateTestTag)
            .performScrollTo()
            .assertIsDisplayed()
        onNodeWithContentDescription(AppStrings.ui_feedback_attachments).assertIsDisplayed()
        val bounds = emptyState.getUnclippedBoundsInRoot()
        assertTrue(bounds.bottom - bounds.top >= 48.dp)
    }

    @Test
    fun uploadProgressIsRenderedInsideAttachmentSectionWithAccessibleCancel() = runComposeUiTest {
        var cancelCount = 0
        setContent {
            MaterialTheme {
                FeedbackTicketDetailPane(
                    state = FeedbackTicketDetailUiState(
                        uuid = "ticket",
                        ticket = feedbackCase("ticket"),
                        editContent = "content",
                        attachmentTransfer = FeedbackAttachmentTransferState(
                            kind = FeedbackAttachmentTransferKind.Upload,
                            fileName = "trace.log",
                            phase = FeedbackAttachmentTransferPhase.Transferring,
                            transferredBytes = 512,
                            totalBytes = 1_024,
                        ),
                    ),
                    onRefresh = {}, onEditContentChange = {}, onUpdate = {}, onDelete = {},
                    onSupplementContentChange = {}, onSupplement = {},
                    onWithdrawalReasonChange = {}, onWithdraw = {}, onUploadAttachment = {},
                    onDownloadAttachment = { _, _ -> }, onCancelOperation = { cancelCount++ },
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }

        onNodeWithTag(FeedbackUploadTransferProgressTestTag).performScrollTo().assertIsDisplayed()
        onNodeWithText("trace.log").assertIsDisplayed()
        onAllNodesWithText(AppStrings.ui_cancel).onLast().performClick()
        assertEquals(1, cancelCount)
    }

    @Test
    fun completedUploadProgressIsNotRenderedBesideUploadedAttachment() = runComposeUiTest {
        val attachment = FeedbackAttachment("uploaded", "trace.log", "text/plain", 10, "now")
        setContent {
            MaterialTheme {
                FeedbackTicketDetailPane(
                    state = FeedbackTicketDetailUiState(
                        uuid = "ticket",
                        ticket = feedbackCase("ticket").copy(attachments = listOf(attachment)),
                        editContent = "content",
                        attachmentTransfer = FeedbackAttachmentTransferState(
                            kind = FeedbackAttachmentTransferKind.Upload,
                            fileName = attachment.originalName,
                            phase = FeedbackAttachmentTransferPhase.Completed,
                            transferredBytes = attachment.size,
                            totalBytes = attachment.size,
                            completed = true,
                        ),
                    ),
                    onRefresh = {}, onEditContentChange = {}, onUpdate = {}, onDelete = {},
                    onSupplementContentChange = {}, onSupplement = {},
                    onWithdrawalReasonChange = {}, onWithdraw = {}, onUploadAttachment = {},
                    onDownloadAttachment = { _, _ -> }, onCancelOperation = {},
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }

        onNodeWithTag(FeedbackUploadTransferProgressTestTag).assertDoesNotExist()
        onNodeWithTag("$FeedbackAttachmentRowTestTagPrefix${attachment.uuid}")
            .performScrollTo()
            .assertIsDisplayed()
    }

    @Test
    fun failedUploadUsesSnackbarAndExposesRetryIconButton() = runComposeUiTest {
        var retryCount = 0
        setContent {
            MaterialTheme {
                FeedbackTicketDetailPane(
                    state = FeedbackTicketDetailUiState(
                        uuid = "ticket",
                        ticket = feedbackCase("ticket"),
                        editContent = "content",
                        refreshMessage = "network down",
                        attachmentTransfer = FeedbackAttachmentTransferState(
                            kind = FeedbackAttachmentTransferKind.Upload,
                            fileName = "trace.log",
                            phase = FeedbackAttachmentTransferPhase.Failed,
                            totalBytes = 1_024,
                            failureMessage = "network down",
                        ),
                    ),
                    onRefresh = {}, onEditContentChange = {}, onUpdate = {}, onDelete = {},
                    onSupplementContentChange = {}, onSupplement = {},
                    onWithdrawalReasonChange = {}, onWithdraw = {}, onUploadAttachment = {},
                    onDownloadAttachment = { _, _ -> }, onCancelOperation = {},
                    onRetryUploadAttachment = { retryCount++ },
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }

        onNodeWithTag(FeedbackTicketDetailSnackbarTestTag).assertIsDisplayed()
        onNodeWithText("network down").assertIsDisplayed()
        onNodeWithContentDescription(AppStrings.ui_try_again).performScrollTo().performClick()
        assertEquals(1, retryCount)
    }

    @Test
    fun downloadProgressIsAssociatedOnlyWithAffectedAttachmentRow() = runComposeUiTest {
        val attachments = listOf(
            FeedbackAttachment("one", "one.log", "text/plain", 10, "now"),
            FeedbackAttachment("two", "two.log", "text/plain", 20, "now"),
        )
        setContent {
            MaterialTheme {
                FeedbackTicketDetailPane(
                    state = FeedbackTicketDetailUiState(
                        uuid = "ticket",
                        ticket = feedbackCase("ticket").copy(attachments = attachments),
                        editContent = "content",
                        attachmentTransfer = FeedbackAttachmentTransferState(
                            kind = FeedbackAttachmentTransferKind.Download,
                            attachmentUuid = "two",
                            fileName = "two.log",
                            phase = FeedbackAttachmentTransferPhase.Saving,
                            transferredBytes = 5,
                            totalBytes = 20,
                        ),
                    ),
                    onRefresh = {}, onEditContentChange = {}, onUpdate = {}, onDelete = {},
                    onSupplementContentChange = {}, onSupplement = {},
                    onWithdrawalReasonChange = {}, onWithdraw = {}, onUploadAttachment = {},
                    onDownloadAttachment = { _, _ -> }, onCancelOperation = {},
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }

        onNodeWithTag("$FeedbackDownloadTransferProgressTestTagPrefix${attachments[1].uuid}")
            .performScrollTo()
            .assertIsDisplayed()
        onNodeWithTag("$FeedbackDownloadTransferProgressTestTagPrefix${attachments[0].uuid}")
            .assertDoesNotExist()
    }

    @Test
    fun completedDownloadProgressIsNotRenderedInsideAttachmentRow() = runComposeUiTest {
        val attachment = FeedbackAttachment("downloaded", "trace.log", "text/plain", 10, "now")
        setContent {
            MaterialTheme {
                FeedbackTicketDetailPane(
                    state = FeedbackTicketDetailUiState(
                        uuid = "ticket",
                        ticket = feedbackCase("ticket").copy(attachments = listOf(attachment)),
                        editContent = "content",
                        attachmentTransfer = FeedbackAttachmentTransferState(
                            kind = FeedbackAttachmentTransferKind.Download,
                            attachmentUuid = attachment.uuid,
                            fileName = attachment.originalName,
                            phase = FeedbackAttachmentTransferPhase.Completed,
                            transferredBytes = attachment.size,
                            totalBytes = attachment.size,
                            completed = true,
                        ),
                    ),
                    onRefresh = {}, onEditContentChange = {}, onUpdate = {}, onDelete = {},
                    onSupplementContentChange = {}, onSupplement = {},
                    onWithdrawalReasonChange = {}, onWithdraw = {}, onUploadAttachment = {},
                    onDownloadAttachment = { _, _ -> }, onCancelOperation = {},
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }

        onNodeWithTag("$FeedbackDownloadTransferProgressTestTagPrefix${attachment.uuid}")
            .assertDoesNotExist()
        onNodeWithTag("$FeedbackAttachmentRowTestTagPrefix${attachment.uuid}")
            .performScrollTo()
            .assertIsDisplayed()
    }

    @Test
    fun offscreenTransferUsesFlatBottomStripAndActivatingItRevealsPrimaryProgress() = runComposeUiTest {
        setContent {
            MaterialTheme {
                Box(Modifier.size(392.dp, 360.dp)) {
                    FeedbackTicketDetailPane(
                        state = FeedbackTicketDetailUiState(
                            uuid = "ticket",
                            ticket = feedbackCase("ticket"),
                            editContent = "content",
                            attachmentTransfer = FeedbackAttachmentTransferState(
                                kind = FeedbackAttachmentTransferKind.Upload,
                                fileName = "trace.log",
                                phase = FeedbackAttachmentTransferPhase.Transferring,
                                transferredBytes = 1,
                                totalBytes = 10,
                            ),
                        ),
                        onRefresh = {}, onEditContentChange = {}, onUpdate = {}, onDelete = {},
                        onSupplementContentChange = {}, onSupplement = {},
                        onWithdrawalReasonChange = {}, onWithdraw = {}, onUploadAttachment = {},
                        onDownloadAttachment = { _, _ -> }, onCancelOperation = {},
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            }
        }

        onNodeWithTag(FeedbackTransferBottomStripTestTag).assertIsDisplayed().performClick()
        waitForIdle()
        onNodeWithTag(FeedbackUploadTransferProgressTestTag).assertIsDisplayed()
    }

    @Test
    fun compactListShowsDetailInsideTicketWorkspaceAndBackReturnsToList() = runComposeUiTest {
        var outerBackCount = 0
        setContent {
            MaterialTheme {
                Box(Modifier.size(600.dp, 800.dp)) {
                    FeedbackTicketListPage(
                        state = ticketListState(),
                        onNavigateBack = { outerBackCount++ },
                        onSignIn = {},
                        onRefresh = {},
                        onLoadMore = {},
                        onUpdateFilters = { _, _, _, _ -> },
                        onSubmitFeedback = {},
                        ticketDetail = { uuid, modifier, _ ->
                            Box(modifier) { Text("detail-$uuid") }
                        },
                    )
                }
            }
        }

        onNodeWithText("content-one").performClick()
        onNodeWithText("detail-one").assertIsDisplayed()
        onNodeWithContentDescription(AppStrings.ui_return).performClick()
        onNodeWithText("content-one").assertIsDisplayed()
        onNodeWithText("detail-one").assertDoesNotExist()
        assertEquals(0, outerBackCount)
    }

    @Test
    fun compactWorkspaceDisplaysNotificationRequestedTicketWithoutASeparateRoute() = runComposeUiTest {
        setContent {
            MaterialTheme {
                Box(Modifier.size(600.dp, 800.dp)) {
                    FeedbackTicketListPage(
                        state = ticketListState(),
                        initialTicketUuid = "requested-ticket",
                        onNavigateBack = {},
                        onSignIn = {},
                        onRefresh = {},
                        onLoadMore = {},
                        onUpdateFilters = { _, _, _, _ -> },
                        onSubmitFeedback = {},
                        ticketDetail = { uuid, modifier, _ ->
                            Box(modifier) { Text("detail-$uuid") }
                        },
                    )
                }
            }
        }

        onNodeWithText("detail-requested-ticket").assertIsDisplayed()
        onNodeWithText("content-one").assertDoesNotExist()
    }

    @Test
    fun ticketCountIsShownInTopBarTitleWithoutSeparateOverview() = runComposeUiTest {
        setContent {
            MaterialTheme {
                Box(Modifier.size(392.dp, 544.dp)) {
                    FeedbackTicketListPage(
                        state = ticketListState().copy(total = 7),
                        onNavigateBack = {},
                        onSignIn = {},
                        onRefresh = {},
                        onLoadMore = {},
                        onUpdateFilters = { _, _, _, _ -> },
                        onSubmitFeedback = {},
                        ticketDetail = { _, _, _ -> },
                    )
                }
            }
        }

        onNodeWithText("${AppStrings.ui_feedback_tickets}(7)").assertIsDisplayed()
        onNodeWithText(
            AppStrings.ui_feedback_ticket_count_arg0.format(arg0 = "7"),
        ).assertDoesNotExist()
    }

    @Test
    fun ticketRowsUseEdgeToEdgeFlatListLayout() = runComposeUiTest {
        setContent {
            MaterialTheme {
                Box(Modifier.size(392.dp, 544.dp)) {
                    FeedbackTicketListPage(
                        state = ticketListState(),
                        onNavigateBack = {},
                        onSignIn = {},
                        onRefresh = {},
                        onLoadMore = {},
                        onUpdateFilters = { _, _, _, _ -> },
                        onSubmitFeedback = {},
                        ticketDetail = { _, _, _ -> },
                    )
                }
            }
        }

        val ticketLabel = AppStrings.ui_feedback_type_category_arg0_arg1.format(
            arg0 = AppStrings.ui_feedback,
            arg1 = AppStrings.ui_feedback_category_bug,
        )
        val rowBounds = onNode(hasClickAction() and hasText(ticketLabel)).getUnclippedBoundsInRoot()

        assertEquals(0.dp, rowBounds.left)
        assertEquals(392.dp, rowBounds.right)
    }

    @Test
    fun compactListMakesTicketStateAndContextImmediatelyVisible() = runComposeUiTest {
        val ticket = feedbackSummary(
            uuid = "one",
            unreadCount = 3,
            status = "waiting_user",
        ).copy(
            priority = "high",
            statusNote = "Please attach the latest diagnostic log",
        )
        setContent {
            MaterialTheme {
                Box(Modifier.size(392.dp, 544.dp)) {
                    FeedbackTicketListPage(
                        state = ticketListState().copy(items = listOf(ticket)),
                        onNavigateBack = {},
                        onSignIn = {},
                        onRefresh = {},
                        onLoadMore = {},
                        onUpdateFilters = { _, _, _, _ -> },
                        onSubmitFeedback = {},
                        ticketDetail = { _, _, _ -> },
                    )
                }
            }
        }

        onNodeWithText(
            AppStrings.ui_feedback_type_category_arg0_arg1.format(
                arg0 = AppStrings.ui_feedback,
                arg1 = AppStrings.ui_feedback_category_bug,
            ),
        ).assertIsDisplayed()
        onNodeWithText(AppStrings.ui_feedback_status_waiting_user).assertIsDisplayed()
        onNodeWithText(
            AppStrings.ui_feedback_priority_arg0.format(arg0 = AppStrings.ui_feedback_priority_high),
        ).assertIsDisplayed()
        onNodeWithText(
            AppStrings.ui_feedback_status_note_arg0.format(
                arg0 = "Please attach the latest diagnostic log",
            ),
        ).assertIsDisplayed()
        onNodeWithText("linux").assertIsDisplayed()
        onNodeWithContentDescription(
            AppStrings.ui_feedback_unread_arg0.format(arg0 = "3"),
        ).assertIsDisplayed()
    }

    @Test
    fun ticketFilterActionUsesAppProvidedSheetAndAppliesSelection() = runComposeUiTest {
        var appliedSelection by mutableStateOf<FeedbackTicketFilterSelection?>(null)
        val filterSheet = object : FeedbackTicketFilterSheet {
            @Composable
            override fun Content(
                selection: FeedbackTicketFilterSelection,
                onApply: (FeedbackTicketFilterSelection) -> Unit,
                onDismiss: () -> Unit,
            ) {
                Dialog(onDismissRequest = onDismiss) {
                    Button(
                        onClick = {
                            onApply(selection.copy(type = "suggestion", platform = "linux"))
                            onDismiss()
                        },
                    ) {
                        Text("apply-test-filter")
                    }
                }
            }
        }
        setContent {
            MaterialTheme {
                CompositionLocalProvider(LocalFeedbackTicketFilterSheet provides filterSheet) {
                    FeedbackTicketListPage(
                        state = ticketListState(),
                        onNavigateBack = {},
                        onSignIn = {},
                        onRefresh = {},
                        onLoadMore = {},
                        onUpdateFilters = { type, platform, start, end ->
                            appliedSelection = FeedbackTicketFilterSelection(type, platform, start, end)
                        },
                        onSubmitFeedback = {},
                        ticketDetail = { _, _, _ -> },
                    )
                }
            }
        }

        onNodeWithContentDescription(AppStrings.ui_feedback_filter_tickets).performClick()
        onNodeWithText("apply-test-filter").performClick()
        waitForIdle()

        assertEquals("suggestion", appliedSelection?.type)
        assertEquals("linux", appliedSelection?.platform)
    }

    @Test
    fun expandedListDisplaysFullDetailWithoutOpeningAnotherRoute() = runComposeUiTest {
        setContent {
            MaterialTheme {
                Box(Modifier.size(2_400.dp, 1_200.dp)) {
                    FeedbackTicketListPage(
                        state = ticketListState().copy(
                            items = listOf(feedbackSummary("one"), feedbackSummary("two")),
                            total = 2,
                        ),
                        onNavigateBack = {},
                        onSignIn = {},
                        onRefresh = {},
                        onLoadMore = {},
                        onUpdateFilters = { _, _, _, _ -> },
                        onSubmitFeedback = {},
                        ticketDetail = { uuid, modifier, _ ->
                            val ticket = feedbackCase(uuid).copy(
                                feedback = feedbackSummary(uuid).copy(statusNote = "detail-$uuid"),
                            )
                            FeedbackTicketDetailPane(
                                state = FeedbackTicketDetailUiState(
                                    uuid = uuid,
                                    ticket = ticket,
                                    editContent = ticket.feedback.content,
                                ),
                                onRefresh = {},
                                onEditContentChange = {},
                                onUpdate = {},
                                onDelete = {},
                                onSupplementContentChange = {},
                                onSupplement = {},
                                onWithdrawalReasonChange = {},
                                onWithdraw = {},
                                onUploadAttachment = {},
                                onDownloadAttachment = { _, _ -> },
                                onCancelOperation = {},
                                modifier = modifier,
                            )
                        },
                    )
                }
            }
        }

        onNodeWithText("detail-one").assertIsDisplayed()
        onAllNodesWithText("content-two").onFirst().performClick()
        waitForIdle()
        onNodeWithText("detail-two").assertIsDisplayed()
        onNodeWithText(AppStrings.ui_feedback_current_status).assertIsDisplayed()
        onNodeWithText(AppStrings.ui_feedback_timeline).performScrollTo().assertIsDisplayed()
        val paneWidth = onNodeWithTag(FeedbackTicketDetailPaneTestTag)
            .fetchSemanticsNode().boundsInRoot.width
        val contentWidth = onNodeWithTag(FeedbackTicketDetailContentTestTag)
            .fetchSemanticsNode().boundsInRoot.width
        assertTrue(contentWidth >= paneWidth - 1f)
    }

    @Test
    fun expandedRouteLoadsDetailAfterSelectingAnotherTicket() = runComposeUiTest {
        val detailRequests = mutableListOf<String>()
        val repository = FakeFeedbackRepository().apply {
            listHandler = { _, _ ->
                ApiResult.Success(
                    FeedbackPage(
                        items = listOf(feedbackSummary("one"), feedbackSummary("two")),
                        total = 2,
                    ),
                )
            }
            detailHandler = { uuid, _ ->
                detailRequests += uuid
                ApiResult.Success(feedbackCase(uuid))
            }
        }
        val sessionService = FeedbackSessionService(
            repository = repository,
            userRepository = FakeUserRepository(),
            sessionProvider = SessionManager::currentSession,
        )
        SessionManager.update(AuthSession(accessToken = "token"))

        setContent {
            MaterialTheme {
                Box(Modifier.size(2_400.dp, 1_200.dp)) {
                    FeedbackTicketListRoute(
                        viewModelKey = "switch-ticket-test",
                        sessionService = sessionService,
                        onNavigateBack = {},
                        onSignIn = {},
                        onUnauthorized = {},
                    )
                }
            }
        }

        waitForIdle()
        assertTrue("one" in detailRequests, AppStrings.ui_test_feedback_page_compose_first_ticket_detail_should_be_loaded_when)
        onAllNodesWithText("content-two").onFirst().performClick()
        waitForIdle()
        assertTrue("two" in detailRequests, AppStrings.ui_test_feedback_page_compose_load_the_second_order_detail_after_switching)

        onNode(hasText("content-two") and isHeading()).assertIsDisplayed()
    }

    @Test
    fun feedbackWorkspaceFormatsEveryServerTimestamp() = runComposeUiTest {
        val serverTimestamp = "2026-08-10T17:01:52Z"
        val formattedTimestamp = formatFeedbackDateTime(serverTimestamp)
        val summary = feedbackSummary("ticket").copy(
            createdAt = serverTimestamp,
            updatedAt = serverTimestamp,
            dueAt = serverTimestamp,
        )
        val ticket = feedbackCase("ticket").copy(
            feedback = summary,
            events = listOf(
                FeedbackEvent(
                    uuid = "event",
                    actorType = "admin",
                    actorName = "Support",
                    eventType = "replied",
                    fromStatus = "submitted",
                    toStatus = "in_progress",
                    message = "Investigating",
                    createdAt = serverTimestamp,
                ),
            ),
            attachments = listOf(
                FeedbackAttachment(
                    uuid = "attachment",
                    originalName = "diagnostic.log",
                    contentType = "text/plain",
                    size = 3,
                    createdAt = serverTimestamp,
                ),
            ),
        )

        setContent {
            MaterialTheme {
                Box(Modifier.size(2_400.dp, 1_400.dp)) {
                    FeedbackTicketListPage(
                        state = ticketListState().copy(items = listOf(summary)),
                        onNavigateBack = {},
                        onSignIn = {},
                        onRefresh = {},
                        onLoadMore = {},
                        onUpdateFilters = { _, _, _, _ -> },
                        onSubmitFeedback = {},
                        ticketDetail = { _, modifier, _ ->
                            FeedbackTicketDetailPane(
                                state = FeedbackTicketDetailUiState(
                                    uuid = "ticket",
                                    ticket = ticket,
                                    editContent = ticket.feedback.content,
                                ),
                                onRefresh = {},
                                onEditContentChange = {},
                                onUpdate = {},
                                onDelete = {},
                                onSupplementContentChange = {},
                                onSupplement = {},
                                onWithdrawalReasonChange = {},
                                onWithdraw = {},
                                onUploadAttachment = {},
                                onDownloadAttachment = { _, _ -> },
                                onCancelOperation = {},
                                modifier = modifier,
                            )
                        },
                    )
                }
            }
        }

        onAllNodesWithText(serverTimestamp, substring = true).assertCountEquals(0)
        assertTrue(
            onAllNodesWithText(formattedTimestamp, substring = true).fetchSemanticsNodes().size >= 5,
        )
    }

    @Test
    fun expandedDetailUsesFlatSectionsAndSurfacesPriorityInformationFirst() = runComposeUiTest {
        val ticket = feedbackCase("ticket").copy(
            feedback = feedbackSummary("ticket", status = "waiting_user").copy(
                statusNote = "Please attach a fresh diagnostic log",
                priority = "high",
                dueAt = "2026-08-12T05:58:15Z",
            ),
            events = listOf(
                FeedbackEvent(
                    uuid = "event-one",
                    actorType = "admin",
                    actorName = "Support",
                    eventType = "replied",
                    fromStatus = "in_progress",
                    toStatus = "waiting_user",
                    message = "We need one more log to continue",
                    createdAt = "2026-08-09T06:26:58Z",
                ),
            ),
        )
        setContent {
            MaterialTheme {
                Box(Modifier.size(1_440.dp, 900.dp)) {
                    FeedbackTicketDetailPane(
                        state = FeedbackTicketDetailUiState(
                            uuid = "ticket",
                            ticket = ticket,
                            editContent = ticket.feedback.content,
                        ),
                        onRefresh = {},
                        onEditContentChange = {},
                        onUpdate = {},
                        onDelete = {},
                        onSupplementContentChange = {},
                        onSupplement = {},
                        onWithdrawalReasonChange = {},
                        onWithdraw = {},
                        onUploadAttachment = {},
                        onDownloadAttachment = { _, _ -> },
                        onCancelOperation = {},
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            }
        }

        onNodeWithText(AppStrings.ui_feedback_current_status).assertIsDisplayed()
        onAllNodesWithText(AppStrings.ui_feedback_status_waiting_user).onFirst().assertIsDisplayed()
        onNodeWithText(AppStrings.ui_feedback_status_note).assertIsDisplayed()
        onNodeWithText("Please attach a fresh diagnostic log").assertIsDisplayed()
        onAllNodesWithText(AppStrings.ui_feedback_latest_progress).onFirst().assertIsDisplayed()
        onAllNodesWithText("We need one more log to continue").onFirst().assertIsDisplayed()
        onNodeWithText(AppStrings.ui_feedback_ticket_information).assertIsDisplayed()
        onAllNodesWithText(AppStrings.ui_feedback_platform).onFirst().assertIsDisplayed()
        onAllNodesWithText("linux").onFirst().assertIsDisplayed()
        onNodeWithContentDescription(AppStrings.ui_reload).assertDoesNotExist()

        val summaryLabel = AppStrings.ui_feedback_type_category_arg0_arg1.format(
            arg0 = AppStrings.ui_feedback,
            arg1 = AppStrings.ui_feedback_category_bug,
        )
        assertEquals(28.dp, onNodeWithText(summaryLabel).getUnclippedBoundsInRoot().left)
        onNodeWithText(AppStrings.ui_feedback_timeline).performScrollTo()
        assertEquals(
            28.dp,
            onNodeWithText(AppStrings.ui_feedback_timeline).getUnclippedBoundsInRoot().left,
        )
    }

    private fun ticketListState() = FeedbackTicketListUiState(
        isSignedIn = true,
        items = listOf(feedbackSummary("one")),
        total = 1,
        page = 1,
        hasMore = false,
    )
}
