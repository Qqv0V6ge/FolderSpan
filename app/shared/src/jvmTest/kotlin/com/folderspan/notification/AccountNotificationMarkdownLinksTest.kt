package com.folderspan.notification

import strings.AppStrings

import com.folderspan.pro.domain.model.NotificationActionKind
import com.folderspan.test.ChineseLocalizationTest
import com.folderspan.ui.navigation.AppScreenRoute
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AccountNotificationMarkdownLinksTest : ChineseLocalizationTest() {
    private val registry = NotificationRouteRegistry(TestScreenFactory())

    @Test
    fun convertsSafeExternalUrlsAndRejectsOtherSchemes() {
        val https = notificationLinkAction("Docs", "https://example.test/docs")
        val http = notificationLinkAction("Status", "HTTP://example.test/status")

        assertEquals(NotificationActionKind.Url, https?.kind)
        assertEquals("https://example.test/docs", https?.url)
        assertEquals(NotificationActionKind.Url, http?.kind)
        assertNull(notificationLinkAction("Email", "mailto:support@example.test"))
        assertNull(notificationLinkAction("Script", "javascript:alert(1)"))
        assertNull(notificationLinkAction("Spaced", "https://example.test/not safe"))
    }

    @Test
    fun convertsParameterlessAndPercentDecodedRoutes() {
        val settings = notificationLinkAction("Settings", "route:settings")
        val ticket = notificationLinkAction(
            "Ticket",
            "route:feedback_tickets?ticketUuid=%E5%B7%A5%E5%8D%95%2D123&source=notification%20body",
        )

        assertEquals(NotificationActionKind.Route, settings?.kind)
        assertEquals("settings", settings?.route)
        assertEquals(emptyMap(), settings?.params)
        assertEquals("feedback_tickets", ticket?.route)
        assertEquals(
            mapOf("ticketUuid" to AppStrings.ui_test_account_notification_markdown_links_task_123, "source" to "notification body"),
            ticket?.params,
        )
    }

    @Test
    fun rejectsMalformedRouteTargetsWithoutThrowing() {
        val targets = listOf(
            "route:",
            "route:settings?",
            "route:settings?=value",
            "route:settings?name",
            "route:settings?name=one&name=two",
            "route:settings?na%6De=one&name=two",
            "route:settings?name=%",
            "route:settings?name=%GG",
            "route:settings?name=%E4",
            "route:settings?name=value#fragment",
            "route:settings?name=value?other=value",
        )

        targets.forEach { target ->
            assertNull(notificationLinkAction("Label", target), target)
        }
    }

    @Test
    fun registryOwnsUnknownRouteAndParameterAvailability() {
        val unknown = requireNotNull(notificationLinkAction("Unknown", "route:not_available"))
        val invalidParams = requireNotNull(
            notificationLinkAction("Settings", "route:settings?unexpected=value"),
        )
        val valid = requireNotNull(notificationLinkAction("Settings", "route:settings"))

        assertFalse(isAccountNotificationActionAvailable(unknown, registry))
        assertFalse(isAccountNotificationActionAvailable(invalidParams, registry))
        assertTrue(isAccountNotificationActionAvailable(valid, registry))
    }

    @Test
    fun convertedLinksDispatchThroughTheExistingActionPath() {
        val action = requireNotNull(
            notificationLinkAction("Ticket", "route:feedback_tickets?ticketUuid=ticket%2D123"),
        )
        var openedScreen: AppScreenRoute? = null

        val dispatched = dispatchAccountNotificationAction(
            action = action,
            registry = registry,
            openExternalUrl = {},
            openScreen = { screen -> openedScreen = screen },
        )

        assertTrue(dispatched)
        assertEquals("feedback:ticket-123", openedScreen?.routeKey)
    }

    @Test
    fun authenticatedConvertedRouteKeepsThePendingDestinationWhenSignedOut() {
        val action = requireNotNull(notificationLinkAction("Profile", "route:user_profile"))
        val signedOutRegistry = NotificationRouteRegistry(AppNotificationRouteScreenFactory { false })
        val signedInRegistry = NotificationRouteRegistry(AppNotificationRouteScreenFactory { true })

        val signedOut = signedOutRegistry.resolve(requireNotNull(action.route), action.params)?.screen
        val signedIn = signedInRegistry.resolve(requireNotNull(action.route), action.params)?.screen

        assertTrue(requireNotNull(signedOut).routeKey.contains("ProPendingLoginRoute"))
        assertTrue(requireNotNull(signedIn).routeKey.contains("ProUserProfileRoute"))
        assertFalse(signedIn.routeKey.contains("ProPendingLoginRoute"))
    }

    private class TestScreenFactory : NotificationRouteScreenFactory {
        override fun feedbackTickets(ticketUuid: String?): AppScreenRoute = TestScreen("feedback:$ticketUuid")
        override fun notificationCenter(): AppScreenRoute = TestScreen("notification-center")
        override fun userProfile(): AppScreenRoute = TestScreen("user-profile")
        override fun personalSettings(): AppScreenRoute = TestScreen("personal-settings")
        override fun permissionSettings(): AppScreenRoute = TestScreen("permission-settings")
        override fun settings(): AppScreenRoute = TestScreen("settings")
    }

    private data class TestScreen(override val routeKey: String) : AppScreenRoute {
        @androidx.compose.runtime.Composable
        override fun Content() = Unit
    }
}
