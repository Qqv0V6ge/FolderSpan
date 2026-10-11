package com.folderspan.ui.navigator

import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.movableContentOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.navigation3.runtime.NavBackStack
import androidx.navigation3.runtime.NavEntry
import androidx.navigationevent.NavigationEventInfo
import androidx.navigationevent.compose.NavigationBackHandler
import androidx.navigationevent.compose.rememberNavigationEventState
import com.folderspan.ui.navigation.AppNavigator
import com.folderspan.ui.navigation.AppRoute
import com.folderspan.ui.navigation.AppRoutePayloadStore
import com.folderspan.ui.navigation.BackStackAppNavigator
import com.folderspan.ui.navigation.LocalAppNavigator
import com.folderspan.ui.navigation.currentOrThrow
import com.folderspan.ui.screen.main.HomeScreen
import com.folderspan.ui.state.main.MainState
import org.koin.compose.koinInject

internal fun resolveHomeInitialRoute(): AppRoute = AppRoute.Home

@Composable
fun HomeNavigator() {
    val content = rememberHomeNavigatorContent()
    content()
}

@Composable
internal fun rememberHomeNavigatorContent(): @Composable () -> Unit {
    val mainState = koinInject<MainState>()
    val backStack = remember { NavBackStack(resolveHomeInitialRoute()) }
    val appNavigator = remember(backStack) { BackStackAppNavigator(backStack) }

    HomeNavigatorBindingEffect(mainState, appNavigator)

    return remember(backStack, appNavigator) {
        movableContentOf {
            HomeNavigatorContent(backStack, appNavigator)
        }
    }
}

@Composable
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
private fun HomeNavigatorContent(
    backStack: List<AppRoute>,
    appNavigator: AppNavigator,
) {
    CompositionLocalProvider(LocalAppNavigator provides appNavigator) {
        val currentRoute = backStack.last()
        val navigationEventState = rememberNavigationEventState(
            currentInfo = HomeNavigationEventInfo(currentRoute),
            backInfo = backStack
                .dropLast(1)
                .asReversed()
                .map(::HomeNavigationEventInfo),
        )
        NavigationBackHandler(
            state = navigationEventState,
            isBackEnabled = backStack.size > 1,
            onBackCompleted = { appNavigator.pop() },
        )

        val currentEntry = homeNavEntry(currentRoute)
        var previousBackStackSize by remember { mutableIntStateOf(backStack.size) }
        var hasRenderedRoute by remember { mutableStateOf(false) }
        val enterDirection = remember(currentEntry.contentKey) {
            if (backStack.size >= previousBackStackSize) 1f else -1f
        }
        val enterProgress = remember(currentEntry.contentKey) {
            Animatable(if (hasRenderedRoute) 0f else 1f)
        }
        val enterOffset = with(LocalDensity.current) { 24.dp.toPx() }
        val motionScheme = MaterialTheme.motionScheme

        SideEffect {
            previousBackStackSize = backStack.size
            hasRenderedRoute = true
        }
        LaunchedEffect(currentEntry.contentKey, motionScheme) {
            if (enterProgress.value < 1f) {
                enterProgress.animateTo(
                    targetValue = 1f,
                    animationSpec = motionScheme.defaultSpatialSpec(),
                )
            }
        }

        Box(
            modifier = Modifier
                .fillMaxSize()
                .clipToBounds()
                .graphicsLayer {
                    val progress = enterProgress.value.coerceIn(0f, 1f)
                    alpha = 0.88f + progress * 0.12f
                    translationX = enterDirection * (1f - progress) * enterOffset
                    scaleX = 0.992f + progress * 0.008f
                    scaleY = 0.992f + progress * 0.008f
                },
        ) {
            key(currentEntry.contentKey) {
                currentEntry.Content()
            }
        }
    }
}

private data class HomeNavigationEventInfo(
    val route: AppRoute,
) : NavigationEventInfo()

@Composable
internal fun HomeNavigatorBindingEffect(
    mainState: MainState,
    appNavigator: AppNavigator
) {
    DisposableEffect(mainState, appNavigator) {
        mainState.updateNavigator(appNavigator)
        onDispose {
            mainState.clearNavigator(appNavigator)
            AppRoutePayloadStore.release(appNavigator.routes)
        }
    }
}

internal fun homeNavEntry(route: AppRoute): NavEntry<AppRoute> =
    when (route) {
        AppRoute.Home -> NavEntry(
            key = route,
            contentKey = "home"
        ) {
            HomeScreen.Content()
        }

        is AppRoute.Payload -> {
            val screen = AppRoutePayloadStore.resolve(route)
            NavEntry(
                key = route,
                contentKey = route.payloadId
            ) {
                if (screen != null) {
                    screen.Content()
                } else {
                    MissingPayloadRouteContent(route)
                }
            }
        }

        else -> error("Unknown app route: $route")
    }

@Composable
private fun MissingPayloadRouteContent(route: AppRoute.Payload) {
    val navigator = LocalAppNavigator.currentOrThrow

    LaunchedEffect(route.payloadId) {
        navigator.replaceRoot(AppRoute.Home)
    }
}
