package com.folderspan.notification

import androidx.compose.runtime.Composable
import com.folderspan.pro.domain.model.AccountNotificationPayloadKeys
import com.folderspan.pro.domain.model.NotificationAction
import com.folderspan.pro.domain.model.NotificationActionKind
import com.folderspan.pro.domain.model.NotificationActionStyle
import com.folderspan.ui.navigation.AppScreenRoute
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AccountNotificationNavigationTest {
    private val registry = NotificationRouteRegistry(TestScreenFactory())

    @Test
    fun dispatchesHttpUrlAndRejectsOtherSchemes() {
        var openedUrl: String? = null
        val httpsDispatched = dispatchAccountNotificationAction(
            action = urlAction("https://example.test/help"),
            registry = registry,
            openExternalUrl = { url -> openedUrl = url },
            openScreen = {},
        )
        val customSchemeDispatched = dispatchAccountNotificationAction(
            action = urlAction("folderspan://settings"),
            registry = registry,
            openExternalUrl = { url -> openedUrl = url },
            openScreen = {},
        )

        assertTrue(httpsDispatched)
        assertEquals("https://example.test/help", openedUrl)
        assertFalse(customSchemeDispatched)
    }

    @Test
    fun dispatchesRegisteredRouteWithParameters() {
        var openedScreen: AppScreenRoute? = null

        val dispatched = dispatchAccountNotificationAction(
            action = NotificationAction(
                label = "Ticket",
                style = NotificationActionStyle.Primary,
                kind = NotificationActionKind.Route,
                route = "feedback_tickets",
                params = mapOf("ticketUuid" to "ticket-1"),
            ),
            registry = registry,
            openExternalUrl = {},
            openScreen = { screen -> openedScreen = screen },
        )

        assertTrue(dispatched)
        assertEquals("feedback:ticket-1", openedScreen?.routeKey)
    }

    @Test
    fun unavailableActionsDoNotDispatch() {
        var opened = false
        val dispatched = dispatchAccountNotificationAction(
            action = NotificationAction(
                label = "Unknown",
                style = NotificationActionStyle.Secondary,
                kind = NotificationActionKind.Route,
                route = "unknown_route",
            ),
            registry = registry,
            openExternalUrl = { opened = true },
            openScreen = { opened = true },
        )

        assertFalse(dispatched)
        assertFalse(opened)
    }

    @Test
    fun authenticatedRoutesUsePendingLoginOnlyWhenSignedOut() {
        val signedOutRegistry = NotificationRouteRegistry(AppNotificationRouteScreenFactory { false })
        val signedInRegistry = NotificationRouteRegistry(AppNotificationRouteScreenFactory { true })

        val signedOutScreen = signedOutRegistry.resolve("personal_settings", emptyMap())?.screen
        val signedInScreen = signedInRegistry.resolve("personal_settings", emptyMap())?.screen

        assertContains(requireNotNull(signedOutScreen).routeKey, "ProPendingLoginRoute")
        assertContains(requireNotNull(signedInScreen).routeKey, "ProPersonalSettingsRoute")
        assertFalse(signedInScreen.routeKey.contains("ProPendingLoginRoute"))
    }

    @Test
    fun errorLogFeedbackUsesPendingLoginWhenSignedOut() {
        val signedOut = AppNotificationRouteScreenFactory { false }.feedbackForm()
        val signedIn = AppNotificationRouteScreenFactory { true }.feedbackForm()

        assertContains(signedOut.routeKey, "ProPendingLoginRoute")
        assertContains(signedIn.routeKey, "ProFeedbackRoute")
        assertFalse(signedIn.routeKey.contains("ProPendingLoginRoute"))
    }

    @Test
    fun systemPayloadParsesPrimaryRouteAndInvalidPayloadFallsBack() {
        val action = primaryAccountNotificationActionFromPayload(
            mapOf(
                AccountNotificationPayloadKeys.PrimaryActionKind to "route",
                AccountNotificationPayloadKeys.PrimaryActionTarget to "feedback_tickets",
                AccountNotificationPayloadKeys.PrimaryActionParams to "{\"ticketUuid\":\"ticket-2\"}",
            ),
        )
        var fallbackCount = 0
        var openedScreen: AppScreenRoute? = null

        dispatchPrimaryAccountNotificationAction(
            action = action,
            registry = registry,
            openExternalUrl = {},
            openScreen = { screen -> openedScreen = screen },
            fallback = { fallbackCount += 1 },
        )
        dispatchPrimaryAccountNotificationAction(
            action = primaryAccountNotificationActionFromPayload(emptyMap()),
            registry = registry,
            openExternalUrl = {},
            openScreen = {},
            fallback = { fallbackCount += 1 },
        )
        dispatchPrimaryAccountNotificationAction(
            action = NotificationAction(
                label = "Unknown",
                style = NotificationActionStyle.Primary,
                kind = NotificationActionKind.Route,
                route = "missing_route",
            ),
            registry = registry,
            openExternalUrl = {},
            openScreen = {},
            fallback = { fallbackCount += 1 },
        )

        assertEquals("feedback:ticket-2", openedScreen?.routeKey)
        assertEquals(2, fallbackCount)
        assertNull(
            primaryAccountNotificationActionFromPayload(
                mapOf(
                    AccountNotificationPayloadKeys.PrimaryActionKind to "route",
                    AccountNotificationPayloadKeys.PrimaryActionTarget to "feedback_tickets",
                    AccountNotificationPayloadKeys.PrimaryActionParams to "not-json",
                ),
            ),
        )
    }

    private fun urlAction(url: String) = NotificationAction(
        label = "Open",
        style = NotificationActionStyle.Primary,
        kind = NotificationActionKind.Url,
        url = url,
    )

    private class TestScreenFactory : NotificationRouteScreenFactory {
        override fun feedbackTickets(ticketUuid: String?): AppScreenRoute = TestScreen("feedback:$ticketUuid")
        override fun notificationCenter(): AppScreenRoute = TestScreen("notification-center")
        override fun userProfile(): AppScreenRoute = TestScreen("user-profile")
        override fun personalSettings(): AppScreenRoute = TestScreen("personal-settings")
        override fun permissionSettings(): AppScreenRoute = TestScreen("permission-settings")
        override fun settings(): AppScreenRoute = TestScreen("settings")
    }

    private data class TestScreen(override val routeKey: String) : AppScreenRoute {
        @Composable
        override fun Content() = Unit
    }
}
