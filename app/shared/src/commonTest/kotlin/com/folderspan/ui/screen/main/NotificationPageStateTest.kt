package com.folderspan.ui.screen.main

import com.folderspan.pro.domain.model.Announcement
import com.folderspan.pro.domain.model.AnnouncementSnapshot
import com.folderspan.pro.domain.model.UserNotificationSnapshot
import com.folderspan.ui.components.pagestate.PageAppendState
import com.folderspan.ui.components.pagestate.PageErrorType
import com.folderspan.ui.components.pagestate.PageRefreshState
import com.folderspan.ui.components.pagestate.PageViewState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import strings.AppStrings

class NotificationPageStateTest {
    @Test
    fun firstOpenFailureUsesListErrorNotRefreshBanner() {
        val loadUi = announcementLoadUi(
            errorMessage = AppStrings.ui_network_connection_failed_please_check_network_try_again,
        )

        val error = assertIs<PageViewState.Error>(loadUi.toPageViewState(visibleItemCount = 0))
        assertEquals(PageErrorType.Connection, error.error.type)
        assertEquals(
            AppStrings.ui_network_connection_failed_please_check_network_try_again,
            error.error.message,
        )
        assertEquals(PageRefreshState.Idle, loadUi.toPageRefreshState(visibleItemCount = 0))
    }

    @Test
    fun successfulEmptyAnnouncementCatalogIsEmpty() {
        val state = announcementLoadUi().toPageViewState(visibleItemCount = 0)

        val empty = assertIs<PageViewState.Empty>(state)
        assertEquals(AppStrings.ui_notification_announcement_empty, empty.message)
        assertEquals(PageRefreshState.Idle, announcementLoadUi().toPageRefreshState(visibleItemCount = 0))
    }

    @Test
    fun refreshingEmptyAnnouncementCatalogIsLoading() {
        val state = announcementLoadUi(isRefreshing = true).toPageViewState(visibleItemCount = 0)

        assertEquals(PageViewState.Loading, state)
    }

    @Test
    fun refreshFailureKeepsContentAndShowsBanner() {
        val loadUi = announcementLoadUi(
            sourceItemCount = 3,
            errorMessage = AppStrings.ui_network_connection_failed_please_check_network_try_again,
        )

        assertEquals(PageViewState.Content, loadUi.toPageViewState(visibleItemCount = 3))
        val refresh = assertIs<PageRefreshState.Error>(loadUi.toPageRefreshState(visibleItemCount = 3))
        assertEquals(
            AppStrings.ui_network_connection_failed_please_check_network_try_again,
            refresh.message,
        )
    }

    @Test
    fun blankCatalogErrorUsesUnavailableFallback() {
        val loadUi = announcementLoadUi(errorMessage = "  ")
        val error = assertIs<PageViewState.Error>(loadUi.toPageViewState(visibleItemCount = 0))
        assertEquals(AppStrings.ui_notification_announcement_unavailable, error.error.message)
        assertEquals(PageRefreshState.Idle, loadUi.toPageRefreshState(visibleItemCount = 0))
    }

    @Test
    fun localEmptyUsesNotificationEmptyCopy() {
        val loadUi = notificationPageLoadUi(
            accountVisible = false,
            announcementVisible = false,
            accountState = UserNotificationSnapshot(),
            announcementState = AnnouncementSnapshot(),
            visibleItemCount = 0,
        )
        val empty = assertIs<PageViewState.Empty>(loadUi.toPageViewState(visibleItemCount = 0))
        assertEquals(AppStrings.ui_no_notification_yet, empty.message)
        assertNull(loadUi.resolvedErrorMessage())
    }

    @Test
    fun announcementFactoryReadsSnapshotFields() {
        val loadUi = notificationPageLoadUi(
            accountVisible = false,
            announcementVisible = true,
            accountState = UserNotificationSnapshot(),
            announcementState = AnnouncementSnapshot(
                items = listOf(sampleAnnouncement()),
                isRefreshing = true,
                isLoadingMore = true,
                hasMore = true,
                errorMessage = "stale",
            ),
            visibleItemCount = 0,
        )

        assertEquals(1, loadUi.sourceItemCount)
        assertEquals(true, loadUi.isRefreshing)
        assertEquals(true, loadUi.isLoadingMore)
        assertEquals(true, loadUi.hasMore)
        assertEquals("stale", loadUi.errorMessage)
        assertEquals(AppStrings.ui_notification_announcement_empty, loadUi.emptyMessage)
        assertEquals(PageAppendState.Idle, loadUi.toPageAppendState(visibleItemCount = 0))
        assertEquals(PageAppendState.Loading, loadUi.toPageAppendState(visibleItemCount = 1))
    }

    private fun announcementLoadUi(
        isRefreshing: Boolean = false,
        isLoadingMore: Boolean = false,
        sourceItemCount: Int = 0,
        errorMessage: String? = null,
    ) = NotificationPageLoadUi(
        isRefreshing = isRefreshing,
        isLoadingMore = isLoadingMore,
        sourceItemCount = sourceItemCount,
        errorMessage = errorMessage,
        emptyMessage = AppStrings.ui_notification_announcement_empty,
        unavailableFallback = AppStrings.ui_notification_announcement_unavailable,
    )

    private fun sampleAnnouncement() = Announcement(
        id = 1L,
        type = "announcement",
        title = "Title",
        content = "Body",
        version = "",
        platform = "linux",
        link = "",
        locale = null,
        publishedAtEpochMillis = 1L,
        actions = emptyList(),
    )
}
