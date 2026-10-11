package com.folderspan.ui.navigation

import androidx.compose.runtime.Composable
import androidx.navigation3.runtime.NavKey
import kotlinx.serialization.Serializable

interface AppRoute : NavKey {
    @Serializable
    data object Home : AppRoute

    @Serializable
    data class Payload(
        val payloadId: String,
        val routeKey: String
    ) : AppRoute
}

interface AppScreenRoute {
    val routeKey: String
        get() = this::class.simpleName ?: toString()

    @Composable
    fun Content()
}

object AppRoutePayloadStore {
    private var nextPayloadId = 0L
    private val payloads = mutableMapOf<String, AppScreenRoute>()

    fun routeFor(screen: AppScreenRoute): AppRoute.Payload {
        val routeKey = screen.routeKey
        val payloadId = "$routeKey:${nextPayloadId++}"
        payloads[payloadId] = screen
        return AppRoute.Payload(
            payloadId = payloadId,
            routeKey = routeKey
        )
    }

    fun resolve(route: AppRoute.Payload): AppScreenRoute? =
        payloads[route.payloadId]

    fun release(route: AppRoute) {
        if (route is AppRoute.Payload) {
            payloads.remove(route.payloadId)
        }
    }

    fun release(routes: Iterable<AppRoute>) {
        routes.forEach(::release)
    }
}

fun AppScreenRoute.toAppRoute(): AppRoute =
    AppRoutePayloadStore.routeFor(this)

fun AppRoute?.matchesScreen(screen: AppScreenRoute): Boolean =
    this is AppRoute.Payload && routeKey == screen.routeKey
