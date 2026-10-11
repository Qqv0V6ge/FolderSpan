package com.folderspan.notification

import com.folderspan.pro.core.datastore.SessionManager
import com.folderspan.pro.presentation.navigation.ProFeedbackRoute
import com.folderspan.pro.presentation.navigation.ProFeedbackTicketsRoute
import com.folderspan.pro.presentation.navigation.ProPendingLoginRoute
import com.folderspan.pro.presentation.navigation.ProPersonalSettingsRoute
import com.folderspan.pro.presentation.navigation.ProRoute
import com.folderspan.pro.presentation.navigation.ProRoutes
import com.folderspan.pro.presentation.navigation.ProUserProfileRoute
import com.folderspan.ui.navigation.AppScreenRoute
import com.folderspan.ui.screen.main.NotificationScreen
import com.folderspan.ui.screen.settings.PermissionSettingsScreen
import com.folderspan.ui.screen.settings.SettingsScreen

internal val appNotificationRouteRegistry = NotificationRouteRegistry(
    AppNotificationRouteScreenFactory(),
)

internal class AppNotificationRouteScreenFactory(
    private val isAuthenticated: () -> Boolean = { SessionManager.currentSession() != null },
) : NotificationRouteScreenFactory {
    override fun feedbackTickets(ticketUuid: String?): AppScreenRoute =
        authenticatedScreen(ProFeedbackTicketsRoute(ticketUuid))

    fun feedbackForm(): AppScreenRoute = authenticatedScreen(ProFeedbackRoute)

    override fun notificationCenter(): AppScreenRoute = NotificationScreen()

    override fun userProfile(): AppScreenRoute = authenticatedScreen(ProUserProfileRoute)

    override fun personalSettings(): AppScreenRoute = authenticatedScreen(ProPersonalSettingsRoute)

    override fun permissionSettings(): AppScreenRoute = PermissionSettingsScreen()

    override fun settings(): AppScreenRoute = SettingsScreen()

    private fun authenticatedScreen(destination: ProRoute): AppScreenRoute =
        ProRoutes.screen(if (isAuthenticated()) destination else ProPendingLoginRoute(destination))
}
