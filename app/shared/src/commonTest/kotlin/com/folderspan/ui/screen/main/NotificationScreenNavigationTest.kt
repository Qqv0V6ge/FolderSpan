package com.folderspan.ui.screen.main

import androidx.compose.ui.unit.dp
import com.folderspan.notification.RequestNotificationFactory
import com.folderspan.pro.domain.model.AccountNotification
import com.folderspan.pro.domain.model.Announcement
import com.folderspan.pro.domain.model.UserNotificationStatus
import com.folderspan.pro.presentation.screen.feedback.ErrorLogFeedbackLaunch
import com.folderspan.ui.navigation.AppRoute
import com.folderspan.ui.navigation.AppRoutePayloadStore
import com.folderspan.ui.navigation.AppScreenRoute
import com.folderspan.ui.navigation.MutableAppNavigator
import com.folderspan.ui.navigation.pushSafe
import com.folderspan.ui.state.main.Notification
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class NotificationScreenNavigationTest {
    @Test
    fun notificationTabsKeepLocalAccountAndAnnouncementSourcesSeparate() {
        assertTrue(NotificationTab.Local.showsLocalNotifications())
        assertFalse(NotificationTab.Local.showsAccountNotifications(loggedIn = true))
        assertFalse(NotificationTab.Local.showsAnnouncements())

        assertFalse(NotificationTab.Account.showsLocalNotifications())
        assertTrue(NotificationTab.Account.showsAccountNotifications(loggedIn = true))
        assertFalse(NotificationTab.Account.showsAccountNotifications(loggedIn = false))
        assertFalse(NotificationTab.Account.showsAnnouncements())

        assertFalse(NotificationTab.Announcement.showsLocalNotifications())
        assertFalse(NotificationTab.Announcement.showsAccountNotifications(loggedIn = true))
        assertTrue(NotificationTab.Announcement.showsAnnouncements())
    }

    @Test
    fun signedOutNotificationCenterShowsLocalAndAnnouncementTabs() {
        assertTrue(shouldShowNotificationTabs(loggedIn = false))
        assertEquals(
            listOf(NotificationTab.Local, NotificationTab.Announcement),
            visibleNotificationTabs(loggedIn = false),
        )
        assertEquals(0, selectedNotificationTabIndex(NotificationTab.Local, loggedIn = false))
        assertEquals(1, selectedNotificationTabIndex(NotificationTab.Announcement, loggedIn = false))
        assertEquals(
            NotificationTab.Local,
            effectiveNotificationTab(NotificationTab.Account, loggedIn = false),
        )
        assertEquals(
            NotificationTab.Announcement,
            effectiveNotificationTab(NotificationTab.Announcement, loggedIn = false),
        )
        assertEquals(NotificationTab.Local, NotificationScreen().selectedTab)
    }

    @Test
    fun signedInNotificationCenterShowsThreeTabs() {
        assertTrue(shouldShowNotificationTabs(loggedIn = true))
        assertEquals(
            listOf(NotificationTab.Local, NotificationTab.Account, NotificationTab.Announcement),
            visibleNotificationTabs(loggedIn = true),
        )
        assertEquals(0, selectedNotificationTabIndex(NotificationTab.Local, loggedIn = true))
        assertEquals(1, selectedNotificationTabIndex(NotificationTab.Account, loggedIn = true))
        assertEquals(2, selectedNotificationTabIndex(NotificationTab.Announcement, loggedIn = true))
        assertEquals(
            NotificationTab.Account,
            effectiveNotificationTab(NotificationTab.Account, loggedIn = true),
        )
    }

    @Test
    fun announcementFiltersUseLocalReadState() {
        assertTrue(NotificationReadFilter.All.matches(isRead = false))
        assertTrue(NotificationReadFilter.All.matches(isRead = true))
        assertTrue(NotificationReadFilter.Unread.matches(isRead = false))
        assertFalse(NotificationReadFilter.Unread.matches(isRead = true))
        assertFalse(NotificationReadFilter.Read.matches(isRead = false))
        assertTrue(NotificationReadFilter.Read.matches(isRead = true))
        assertFalse(
            TimelineNotification.Announcement(
                notification = sampleAnnouncement(),
                cutoffEpochMillis = 0L,
            ).isSelectable(),
        )
    }

    @Test
    fun homeBellIgnoresAnnouncementUnread() {
        assertEquals(0, notificationBellUnreadCount(localUnread = 0, accountUnread = 0))
        assertEquals(3, notificationBellUnreadCount(localUnread = 1, accountUnread = 2))
        assertEquals(2, notificationBellUnreadCount(localUnread = 0, accountUnread = 2))
    }

    @Test
    fun notificationDetailPaneOnlyAppearsAtExpandedContentWidth() {
        assertFalse(shouldUseNotificationSplitPane(393.dp))
        assertFalse(shouldUseNotificationSplitPane(839.dp))
        assertTrue(shouldUseNotificationSplitPane(840.dp))
    }

    @Test
    fun openNotificationDetailPushesMatchingPayloadScreen() {
        val notification = Notification(
            id = 42L,
            title = "Test title",
            message = "Test message"
        )
        val navigator = MutableAppNavigator(AppRoute.Home)

        openNotificationDetail(navigator, notification)

        val route = assertIs<AppRoute.Payload>(navigator.currentRoute)
        val screen = assertIs<NotificationDetailScreen>(AppRoutePayloadStore.resolve(route))
        assertEquals(notification, screen.notification)
        assertNull(screen.accountNotification)
        assertNull(screen.announcement)

        navigator.pop()
    }

    @Test
    fun detailBackKeepsExistingNotificationListOnStack() {
        val navigator = MutableAppNavigator(AppRoute.Home)
        navigator.pushSafe(NotificationScreen())
        navigator.pushSafe(
            NotificationDetailScreen(Notification(id = 1L, title = "Title", message = "Message")),
        )

        navigateBackToNotificationList(navigator)

        assertEquals(2, navigator.routes.size)
        val route = assertIs<AppRoute.Payload>(navigator.currentRoute)
        assertIs<NotificationScreen>(AppRoutePayloadStore.resolve(route))
        navigator.popToRoot()
    }

    @Test
    fun detailBackOpenedFromHomeReturnsToNotificationList() {
        val navigator = MutableAppNavigator(AppRoute.Home)
        navigator.pushSafe(
            NotificationDetailScreen(Notification(id = 2L, title = "Title", message = "Message")),
        )

        navigateBackToNotificationList(navigator)

        assertEquals(2, navigator.routes.size)
        val route = assertIs<AppRoute.Payload>(navigator.currentRoute)
        assertIs<NotificationScreen>(AppRoutePayloadStore.resolve(route))
        navigator.popToRoot()
    }

    @Test
    fun openAccountNotificationDetailPushesMatchingPayloadScreen() {
        val notification = sampleAccountNotification()
        val navigator = MutableAppNavigator(AppRoute.Home)

        openAccountNotificationDetail(navigator, notification)

        val route = assertIs<AppRoute.Payload>(navigator.currentRoute)
        val screen = assertIs<NotificationDetailScreen>(AppRoutePayloadStore.resolve(route))
        assertNull(screen.notification)
        assertEquals(notification, screen.accountNotification)
        assertNull(screen.announcement)

        navigator.pop()
    }

    @Test
    fun accountDetailBackRestoresAccountTab() {
        val notificationScreen = NotificationScreen()
        notificationScreen.readFilter = NotificationReadFilter.Unread
        val navigator = MutableAppNavigator(AppRoute.Home)
        navigator.pushSafe(notificationScreen)
        navigator.pushSafe(NotificationDetailScreen(accountNotification = sampleAccountNotification()))

        navigateBackToNotificationList(
            navigator = navigator,
            targetTab = NotificationTab.Account,
        )

        assertEquals(NotificationTab.Account, notificationScreen.selectedTab)
        assertEquals(NotificationReadFilter.Unread, notificationScreen.readFilter)
        val route = assertIs<AppRoute.Payload>(navigator.currentRoute)
        assertEquals(notificationScreen, AppRoutePayloadStore.resolve(route))
        navigator.popToRoot()
    }

    @Test
    fun accountDetailOpenedDirectlyReturnsToAccountTab() {
        val navigator = MutableAppNavigator(AppRoute.Home)
        navigator.pushSafe(NotificationDetailScreen(accountNotification = sampleAccountNotification()))

        navigateBackToNotificationList(
            navigator = navigator,
            targetTab = NotificationTab.Account,
        )

        val route = assertIs<AppRoute.Payload>(navigator.currentRoute)
        val notificationScreen = assertIs<NotificationScreen>(AppRoutePayloadStore.resolve(route))
        assertEquals(NotificationTab.Account, notificationScreen.selectedTab)
        navigator.popToRoot()
    }

    @Test
    fun openAppUpdateNotificationOpensDetailWithoutOpeningLink() {
        val navigator = MutableAppNavigator(AppRoute.Home)
        val notification = RequestNotificationFactory.buildAppUpdateNotification(
            version = "1.4.0",
            link = "https://example.test/download",
        ).notification

        openNotificationDetail(navigator, notification)

        val route = assertIs<AppRoute.Payload>(navigator.currentRoute)
        val screen = assertIs<NotificationDetailScreen>(AppRoutePayloadStore.resolve(route))
        assertEquals(notification, screen.notification)
        navigator.popToRoot()
    }

    @Test
    fun openAppUpdateNotificationWithInvalidLinkStillOpensDetail() {
        val navigator = MutableAppNavigator(AppRoute.Home)
        val notification = RequestNotificationFactory.buildAppUpdateNotification(
            version = "1.4.0",
            link = "",
        ).notification

        openNotificationDetail(navigator, notification)

        val route = assertIs<AppRoute.Payload>(navigator.currentRoute)
        val screen = assertIs<NotificationDetailScreen>(AppRoutePayloadStore.resolve(route))
        assertEquals(notification, screen.notification)
        navigator.popToRoot()
    }

    @Test
    fun openErrorLogNotificationFromTimelineStartsFeedbackWithPendingPayload() {
        val notification = RequestNotificationFactory.buildErrorLogNotification(
            summary = "Captured failure",
        ).notification
        val navigator = MutableAppNavigator(AppRoute.Home)
        ErrorLogFeedbackLaunch.pending = null

        try {
            openLocalNotification(navigator, notification)

            val route = assertIs<AppRoute.Payload>(navigator.currentRoute)
            val screen = assertIs<AppScreenRoute>(AppRoutePayloadStore.resolve(route))
            assertTrue(screen.routeKey.contains("ProFeedbackRoute"))
            assertEquals("Captured failure", ErrorLogFeedbackLaunch.pending?.summary)
        } finally {
            navigator.popToRoot()
            ErrorLogFeedbackLaunch.pending = null
        }
    }

    @Test
    fun errorLogNotificationBypassesSplitDetailPane() {
        val notification = RequestNotificationFactory.buildErrorLogNotification(
            summary = "Captured failure",
        ).notification
        val appUpdate = RequestNotificationFactory.buildAppUpdateNotification(
            version = "1.4.0",
            link = "https://example.test/download",
        ).notification

        assertTrue(shouldOpenLocalNotificationDirectly(notification))
        assertFalse(shouldOpenLocalNotificationDirectly(appUpdate))
    }

    @Test
    fun openAnnouncementDetailPushesMatchingPayloadScreen() {
        val announcement = sampleAnnouncement()
        val navigator = MutableAppNavigator(AppRoute.Home)

        openAnnouncementDetail(navigator, announcement)

        val route = assertIs<AppRoute.Payload>(navigator.currentRoute)
        val screen = assertIs<NotificationDetailScreen>(AppRoutePayloadStore.resolve(route))
        assertNull(screen.notification)
        assertNull(screen.accountNotification)
        assertEquals(announcement, screen.announcement)

        navigator.pop()
    }

    @Test
    fun announcementDetailBackRestoresAnnouncementTab() {
        val notificationScreen = NotificationScreen()
        notificationScreen.readFilter = NotificationReadFilter.Unread
        val navigator = MutableAppNavigator(AppRoute.Home)
        navigator.pushSafe(notificationScreen)
        navigator.pushSafe(NotificationDetailScreen(announcement = sampleAnnouncement()))

        navigateBackToNotificationList(
            navigator = navigator,
            targetTab = NotificationTab.Announcement,
        )

        assertEquals(NotificationTab.Announcement, notificationScreen.selectedTab)
        assertEquals(NotificationReadFilter.Unread, notificationScreen.readFilter)
        val route = assertIs<AppRoute.Payload>(navigator.currentRoute)
        assertEquals(notificationScreen, AppRoutePayloadStore.resolve(route))
        navigator.popToRoot()
    }

    private fun sampleAccountNotification() = AccountNotification(
        id = "notice-1",
        userUuid = "user-1",
        title = "Account title",
        content = "Account message",
        locale = null,
        type = "general",
        status = UserNotificationStatus.Unread,
        actions = emptyList(),
        readAtEpochMillis = null,
        createdAtEpochMillis = 0L,
    )

    private fun sampleAnnouncement() = Announcement(
        id = 9L,
        type = "announcement",
        title = "Announcement title",
        content = "Announcement body",
        version = "",
        platform = "linux",
        link = "",
        locale = null,
        publishedAtEpochMillis = 20L,
        actions = emptyList(),
    )
}
