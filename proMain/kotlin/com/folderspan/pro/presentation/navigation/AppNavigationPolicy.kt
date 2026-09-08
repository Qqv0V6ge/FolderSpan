package com.folderspan.pro.presentation.navigation

import com.folderspan.pro.core.datastore.AuthSession

fun reconcileBackStackWithSession(
    backStack: MutableList<in ProRoute>,
    session: AuthSession?,
) {
    val currentRoute = backStack.lastOrNull() as? ProRoute
    when {
        backStack.isEmpty() -> backStack.replaceWith(if (session == null) ProLoginRoute else ProUserProfileRoute)
        session == null && currentRoute is ProFeedbackTicketsRoute ->
            backStack.replaceLastWith(ProPendingLoginRoute(currentRoute))
        session == null && currentRoute.isAuthenticatedRoute() -> backStack.replaceWith(ProLoginRoute)
        session != null && currentRoute is ProPendingLoginRoute ->
            backStack.replaceLastWith(currentRoute.destination)
        session != null && currentRoute == ProLoginRoute -> backStack.replaceWith(ProUserProfileRoute)
        session != null && currentRoute.isUnauthenticatedRoute() -> backStack.replaceWith(ProMarketplaceRoute)
    }
}

fun popBackStack(backStack: MutableList<in ProRoute>): Boolean {
    if (backStack.size <= 1) return false
    backStack.removeAt(backStack.lastIndex)
    return true
}

fun discardPendingSignIn(backStack: MutableList<in ProRoute>): Boolean {
    if (backStack.lastOrNull() !is ProPendingLoginRoute) return false
    backStack.removeAt(backStack.lastIndex)
    return true
}

fun MutableList<in ProRoute>.replaceWith(route: ProRoute) {
    clear()
    add(route)
}

private fun MutableList<in ProRoute>.replaceLastWith(route: ProRoute) {
    if (isNotEmpty()) removeAt(lastIndex)
    add(route)
}

fun proRouteViewModelKey(serviceScopeKey: String, route: ProRoute): String =
    "ProViewModel:$serviceScopeKey:${route::class.simpleName ?: "Route"}:${route}"

private fun ProRoute?.isAuthenticatedRoute(): Boolean =
    when (this) {
        ProMarketplaceRoute,
        ProUserProfileRoute,
        ProLoginDevicesRoute,
        ProEditProfileRoute,
        ProChangePasswordRoute,
        ProPersonalSettingsRoute,
        ProManualDataSyncRoute,
        is ProFeedbackTicketsRoute,
        -> true

        ProLoginRoute,
        ProRegistrationRoute,
        ProRecoveryRoute,
        ProFeedbackRoute,
        is ProPendingLoginRoute,
        null,
        -> false
    }

private fun ProRoute?.isUnauthenticatedRoute(): Boolean =
    when (this) {
        ProLoginRoute,
        ProRegistrationRoute,
        ProRecoveryRoute,
        -> true

        ProMarketplaceRoute,
        ProUserProfileRoute,
        ProLoginDevicesRoute,
        ProEditProfileRoute,
        ProChangePasswordRoute,
        ProPersonalSettingsRoute,
        ProManualDataSyncRoute,
        ProFeedbackRoute,
        is ProFeedbackTicketsRoute,
        is ProPendingLoginRoute,
        null,
        -> false
    }
