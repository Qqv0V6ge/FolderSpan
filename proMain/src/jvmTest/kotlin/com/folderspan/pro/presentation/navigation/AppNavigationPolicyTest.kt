package com.folderspan.pro.presentation.navigation

import com.folderspan.pro.core.datastore.AuthSession
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AppNavigationPolicyTest {

    @Test
    fun reconcileBackStackWithSession_movesLoginRouteToUserProfileAfterAuthorization() {
        val backStack = mutableListOf<ProRoute>(ProLoginRoute)

        reconcileBackStackWithSession(backStack, AuthSession(accessToken = "token-value"))

        assertEquals(listOf<ProRoute>(ProUserProfileRoute), backStack)
    }

    @Test
    fun reconcileBackStackWithSession_keepsRegistrationAuthorizationTargetAsMarketplace() {
        val backStack = mutableListOf<ProRoute>(ProRegistrationRoute)

        reconcileBackStackWithSession(backStack, AuthSession(accessToken = "token-value"))

        assertEquals(listOf<ProRoute>(ProMarketplaceRoute), backStack)
    }

    @Test
    fun reconcileBackStackWithSession_movesLoginDevicesRouteToLoginWhenSignedOut() {
        val backStack = mutableListOf<ProRoute>(ProLoginDevicesRoute)

        reconcileBackStackWithSession(backStack, session = null)

        assertEquals(listOf<ProRoute>(ProLoginRoute), backStack)
    }

    @Test
    fun proRouteViewModelKey_changesWhenServiceScopeChanges() {
        val firstKey = proRouteViewModelKey("services-1", ProPersonalSettingsRoute)
        val secondKey = proRouteViewModelKey("services-2", ProPersonalSettingsRoute)

        assertEquals(false, firstKey == secondKey)
    }

    @Test
    fun signedOutFeedbackWorkspaceKeepsRequestedTicketForLoginHandoff() {
        val backStack = mutableListOf<ProRoute>(ProFeedbackRoute, ProFeedbackTicketsRoute("ticket-1"))

        reconcileBackStackWithSession(backStack, session = null)

        assertEquals(
            listOf<ProRoute>(
                ProFeedbackRoute,
                ProPendingLoginRoute(ProFeedbackTicketsRoute("ticket-1")),
            ),
            backStack,
        )
    }

    @Test
    fun feedbackLoginReturnsToWorkspaceWithRequestedTicketAfterAuthorization() {
        val backStack = mutableListOf<ProRoute>(
            ProFeedbackRoute,
            ProPendingLoginRoute(ProFeedbackTicketsRoute("ticket-1")),
        )

        reconcileBackStackWithSession(backStack, AuthSession(accessToken = "token-value"))

        assertEquals(listOf<ProRoute>(ProFeedbackRoute, ProFeedbackTicketsRoute("ticket-1")), backStack)
    }

    @Test
    fun pendingLoginReturnsToAnyAuthenticatedDestination() {
        val backStack = mutableListOf<ProRoute>(ProPendingLoginRoute(ProPersonalSettingsRoute))

        reconcileBackStackWithSession(backStack, AuthSession(accessToken = "token-value"))

        assertEquals(listOf<ProRoute>(ProPersonalSettingsRoute), backStack)
    }

    @Test
    fun abandoningPendingLoginDiscardsTheDestination() {
        val backStack = mutableListOf<ProRoute>(
            ProFeedbackRoute,
            ProPendingLoginRoute(ProFeedbackTicketsRoute("ticket-1")),
        )

        assertTrue(discardPendingSignIn(backStack))

        assertEquals(listOf<ProRoute>(ProFeedbackRoute), backStack)
    }

    @Test
    fun popBackStack_removesCurrentRouteAndRevealsPreviousRoute() {
        val backStack = mutableListOf<ProRoute>(ProUserProfileRoute, ProEditProfileRoute)

        assertTrue(popBackStack(backStack))

        assertEquals(listOf<ProRoute>(ProUserProfileRoute), backStack)
    }

    @Test
    fun publicFeedbackRouteRemainsAvailableWhenSignedOut() {
        val backStack = mutableListOf<ProRoute>(ProFeedbackRoute)

        reconcileBackStackWithSession(backStack, session = null)

        assertEquals(listOf<ProRoute>(ProFeedbackRoute), backStack)
    }

    @Test
    fun ticketWorkspaceViewModelKeysDifferByInitialTicketIdentity() {
        val first = proRouteViewModelKey("services", ProFeedbackTicketsRoute("one"))
        val second = proRouteViewModelKey("services", ProFeedbackTicketsRoute("two"))

        assertEquals(false, first == second)
    }

    @Test
    fun notificationRequestedTicketWorkspacesHaveDistinctHostRouteKeys() {
        val first = ProRoutes.screen(ProFeedbackTicketsRoute("one")).routeKey
        val second = ProRoutes.screen(ProFeedbackTicketsRoute("two")).routeKey

        assertEquals(false, first == second)
    }
}
