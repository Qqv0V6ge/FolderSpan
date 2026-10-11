package com.folderspan.ui.navigation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.ProvidableCompositionLocal
import androidx.compose.runtime.staticCompositionLocalOf

interface AppNavigator {
    val routes: List<AppRoute>
    val currentRoute: AppRoute?
    val canPop: Boolean

    fun push(route: AppRoute)
    fun push(screen: AppScreenRoute) {
        push(screen.toAppRoute())
    }

    fun pop(): Boolean
    fun popToRoot(): Boolean
    fun replaceRoot(route: AppRoute)
}

val ProvidableCompositionLocal<AppNavigator?>.currentOrThrow: AppNavigator
    @Composable
    get() = current ?: error("AppNavigator is not available in the current composition")

fun AppNavigator.handleBack(): Boolean = pop()

fun AppNavigator.pushSafe(route: AppRoute) {
    push(route)
}

fun AppNavigator.pushSafe(screen: AppScreenRoute) {
    push(screen)
}

fun AppNavigator.popUntilRoot(): Boolean = popToRoot()

private fun AppRoute.isDuplicateOf(route: AppRoute): Boolean =
    this == route || (this is AppRoute.Payload && route is AppRoute.Payload && routeKey == route.routeKey)

class MutableAppNavigator(
    initialRoute: AppRoute,
    private val mutableRoutes: MutableList<AppRoute> = mutableListOf(initialRoute)
) : AppNavigator {
    override val routes: List<AppRoute>
        get() = mutableRoutes.toList()

    override val currentRoute: AppRoute?
        get() = mutableRoutes.lastOrNull()

    override val canPop: Boolean
        get() = mutableRoutes.size > 1

    override fun push(route: AppRoute) {
        val current = currentRoute
        if (current?.isDuplicateOf(route) == true) {
            if (current != route) {
                AppRoutePayloadStore.release(route)
            }
            return
        }
        mutableRoutes.add(route)
    }

    override fun pop(): Boolean {
        if (!canPop) return false
        val removed = mutableRoutes.removeAt(mutableRoutes.lastIndex)
        AppRoutePayloadStore.release(removed)
        return true
    }

    override fun popToRoot(): Boolean {
        if (!canPop) return false
        val root = mutableRoutes.first()
        val removed = mutableRoutes.drop(1)
        mutableRoutes.clear()
        mutableRoutes.add(root)
        AppRoutePayloadStore.release(removed)
        return true
    }

    override fun replaceRoot(route: AppRoute) {
        val removed = mutableRoutes.filterNot { it == route }
        mutableRoutes.clear()
        mutableRoutes.add(route)
        AppRoutePayloadStore.release(removed)
    }
}

class BackStackAppNavigator(
    private val backStack: MutableList<AppRoute>
) : AppNavigator {
    override val routes: List<AppRoute>
        get() = backStack.toList()

    override val currentRoute: AppRoute?
        get() = backStack.lastOrNull()

    override val canPop: Boolean
        get() = backStack.size > 1

    override fun push(route: AppRoute) {
        val current = currentRoute
        if (current?.isDuplicateOf(route) == true) {
            if (current != route) {
                AppRoutePayloadStore.release(route)
            }
            return
        }
        backStack.add(route)
    }

    override fun pop(): Boolean {
        if (!canPop) return false
        val removed = backStack.removeAt(backStack.lastIndex)
        AppRoutePayloadStore.release(removed)
        return true
    }

    override fun popToRoot(): Boolean {
        if (!canPop) return false
        val root = backStack.first()
        val removed = backStack.drop(1)
        backStack.clear()
        backStack.add(root)
        AppRoutePayloadStore.release(removed)
        return true
    }

    override fun replaceRoot(route: AppRoute) {
        val removed = backStack.filterNot { it == route }
        backStack.clear()
        backStack.add(route)
        AppRoutePayloadStore.release(removed)
    }
}

val LocalAppNavigator = staticCompositionLocalOf<AppNavigator?> { null }
