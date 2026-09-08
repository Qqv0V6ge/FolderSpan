package com.folderspan.pro.presentation.navigation

import androidx.compose.runtime.*
import com.folderspan.createSettings
import com.folderspan.pro.core.datastore.SessionManager
import com.folderspan.pro.di.AppServices
import com.folderspan.ui.navigation.AppScreenRoute
import com.folderspan.ui.navigation.LocalAppNavigator
import com.folderspan.pro.presentation.screen.auth.LoginRoute as LoginScreenRoute
import com.folderspan.pro.presentation.screen.auth.RecoveryRoute as RecoveryScreenRoute
import com.folderspan.pro.presentation.screen.auth.RegistrationRoute as RegistrationScreenRoute
import com.folderspan.pro.presentation.screen.feedback.FeedbackHomeRoute as FeedbackHomeScreenRoute
import com.folderspan.pro.presentation.screen.feedback.FeedbackTicketListRoute as FeedbackTicketListScreenRoute
import com.folderspan.pro.presentation.screen.marketplace.MarketplaceRoute as MarketplaceScreenRoute
import com.folderspan.pro.presentation.screen.profile.ChangePasswordRoute as ChangePasswordScreenRoute
import com.folderspan.pro.presentation.screen.profile.EditProfileRoute as EditProfileScreenRoute
import com.folderspan.pro.presentation.screen.profile.LoginDevicesRoute as LoginDevicesScreenRoute
import com.folderspan.pro.presentation.screen.profile.PersonalSettingsRoute as PersonalSettingsScreenRoute
import com.folderspan.pro.presentation.screen.profile.UserProfileRoute as UserProfileScreenRoute
import com.folderspan.pro.presentation.screen.sync.ManualDataSyncRoute as ManualDataSyncScreenRoute
import strings.AppStrings

object ProRoutes {

    fun userProfileScreen(): AppScreenRoute = screen(ProUserProfileRoute)
    fun manualDataSyncScreen(): AppScreenRoute = screen(ProManualDataSyncRoute)
    fun feedbackScreen(): AppScreenRoute = screen(ProFeedbackRoute)

    fun screen(initialRoute: ProRoute): AppScreenRoute = ProScreen(initialRoute)
}

private class ProScreen(
    private val initialRoute: ProRoute,
) : AppScreenRoute {
    override val routeKey: String = initialRoute.hostRouteKey()

    @Composable
    override fun Content() {
        ProRoutesContent(initialRoute)
    }
}

@Composable
private fun ProRoutesContent(initialRoute: ProRoute) {
    val settings = remember { createSettings() }
    val services = remember(settings) { AppServices(settings) }
    val serviceScopeKey = remember(services) { services.hashCode().toString() }
    val hostNavigator = LocalAppNavigator.current
    val session by SessionManager.session.collectAsState()
    val backStack = remember(initialRoute) { mutableStateListOf(initialRoute) }

    DisposableEffect(services) {
        onDispose {
            services.close()
        }
    }

    LaunchedEffect(session) {
        reconcileBackStackWithSession(backStack, session)
    }

    fun push(route: ProRoute) {
        backStack.add(route)
    }

    fun pop() {
        if (!popBackStack(backStack)) {
            hostNavigator?.pop()
        }
    }

    fun replaceLast(route: ProRoute) {
        if (backStack.isNotEmpty()) backStack.removeAt(backStack.lastIndex)
        backStack.add(route)
    }

    fun logout() {
        SessionManager.clear()
        backStack.replaceWith(ProLoginRoute)
    }

    when (backStack.lastOrNull()) {
        ProLoginRoute -> LoginScreenRoute(
            viewModelKey = proRouteViewModelKey(serviceScopeKey, ProLoginRoute),
            repository = services.authRepository,
            onAuthorized = { backStack.replaceWith(ProUserProfileRoute) },
            onNavigateBack = ::pop,
            onNavigateToRegistration = { push(ProRegistrationRoute) },
            onNavigateToRecovery = { push(ProRecoveryRoute) },
            onSessionAuthorized = { token ->
                services.deviceSettingsSyncService.sync(settings, token)
            },
        )

        ProRegistrationRoute -> RegistrationScreenRoute(
            viewModelKey = proRouteViewModelKey(serviceScopeKey, ProRegistrationRoute),
            repository = services.authRepository,
            onAuthorized = { backStack.replaceWith(ProMarketplaceRoute) },
            onNavigateBack = ::pop,
            onNavigateToLogin = { backStack.replaceWith(ProLoginRoute) },
            onSessionAuthorized = { token ->
                services.deviceSettingsSyncService.sync(settings, token)
            },
        )

        ProRecoveryRoute -> RecoveryScreenRoute(
            viewModelKey = proRouteViewModelKey(serviceScopeKey, ProRecoveryRoute),
            repository = services.authRepository,
            onNavigateBack = ::pop,
            onNavigateToLogin = { backStack.replaceWith(ProLoginRoute) },
        )

        is ProPendingLoginRoute -> {
            val route = backStack.last() as ProPendingLoginRoute
            LoginScreenRoute(
                viewModelKey = proRouteViewModelKey(serviceScopeKey, route),
                repository = services.authRepository,
                prompt = AppStrings.ui_notification_sign_in_prompt,
                onAuthorized = { replaceLast(route.destination) },
                onNavigateBack = {
                    discardPendingSignIn(backStack)
                    if (backStack.isEmpty()) hostNavigator?.pop()
                },
                onNavigateToRegistration = { push(ProRegistrationRoute) },
                onNavigateToRecovery = { push(ProRecoveryRoute) },
                onSessionAuthorized = { token -> services.deviceSettingsSyncService.sync(settings, token) },
            )
        }

        ProMarketplaceRoute -> MarketplaceScreenRoute(
            viewModelKey = proRouteViewModelKey(serviceScopeKey, ProMarketplaceRoute),
            repository = services.pluginRepository,
            onNavigateToUserProfile = { push(ProUserProfileRoute) },
        )

        ProUserProfileRoute -> UserProfileScreenRoute(
            viewModelKey = proRouteViewModelKey(serviceScopeKey, ProUserProfileRoute),
            repository = services.userRepository,
            onNavigateBack = ::pop,
            onNavigateToLoginDevices = { push(ProLoginDevicesRoute) },
            onNavigateToEditProfile = { push(ProEditProfileRoute) },
            onNavigateToChangePassword = { push(ProChangePasswordRoute) },
            onNavigateToPersonalSettings = { push(ProPersonalSettingsRoute) },
            onLogout = ::logout,
        )

        ProLoginDevicesRoute -> LoginDevicesScreenRoute(
            viewModelKey = proRouteViewModelKey(serviceScopeKey, ProUserProfileRoute),
            repository = services.userRepository,
            onNavigateBack = ::pop,
            onLogout = ::logout,
        )

        ProEditProfileRoute -> EditProfileScreenRoute(
            viewModelKey = proRouteViewModelKey(serviceScopeKey, ProEditProfileRoute),
            repository = services.userRepository,
            onNavigateBack = ::pop,
            onLogout = ::logout,
        )

        ProChangePasswordRoute -> ChangePasswordScreenRoute(
            viewModelKey = proRouteViewModelKey(serviceScopeKey, ProChangePasswordRoute),
            repository = services.userRepository,
            onNavigateBack = ::pop,
            onLogout = ::logout,
        )

        ProPersonalSettingsRoute -> PersonalSettingsScreenRoute(
            viewModelKey = proRouteViewModelKey(serviceScopeKey, ProPersonalSettingsRoute),
            repository = services.settingRepository,
            userRepository = services.userRepository,
            settings = settings,
            deviceSettingsSyncService = services.deviceSettingsSyncService,
            onNavigateBack = ::pop,
            onLogout = ::logout,
        )

        ProManualDataSyncRoute -> ManualDataSyncScreenRoute(
            viewModelKey = proRouteViewModelKey(serviceScopeKey, ProManualDataSyncRoute),
            settings = settings,
            deviceSettingsSyncService = services.deviceSettingsSyncService,
            onNavigateBack = ::pop,
        )

        ProFeedbackRoute -> FeedbackHomeScreenRoute(
            viewModelKey = proRouteViewModelKey(serviceScopeKey, ProFeedbackRoute),
            repository = services.feedbackRepository,
            sessionService = services.feedbackSessionService,
            onNavigateBack = ::pop,
            onOpenTickets = {
                val destination = ProFeedbackTicketsRoute()
                push(if (session == null) ProPendingLoginRoute(destination) else destination)
            },
            onUnauthorized = { SessionManager.clear() },
        )

        is ProFeedbackTicketsRoute -> {
            val route = backStack.last() as ProFeedbackTicketsRoute
            FeedbackTicketListScreenRoute(
                initialTicketUuid = route.initialTicketUuid,
                viewModelKey = proRouteViewModelKey(serviceScopeKey, route),
                sessionService = services.feedbackSessionService,
                onNavigateBack = ::pop,
                onSignIn = { replaceLast(ProPendingLoginRoute(route)) },
                onUnauthorized = { SessionManager.clear() },
            )
        }

        null -> Unit
    }
}

private fun ProRoute.hostRouteKey(): String {
    val baseRouteKey = "Pro:${this::class.simpleName ?: toString()}"
    return when (this) {
        is ProFeedbackTicketsRoute -> initialTicketUuid
            ?.let { "$baseRouteKey:${it.hashCode()}" }
            ?: baseRouteKey

        is ProPendingLoginRoute -> "$baseRouteKey:${destination.hostRouteKey()}"
        else -> baseRouteKey
    }
}
