package com.folderspan.ui.navigation

import androidx.compose.runtime.Composable
import com.folderspan.ui.state.main.MainState
import com.folderspan.utils.WindowSizeClass
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

private data object RootRoute : AppRoute
private data object SettingsRoute : AppRoute
private data object DetailsRoute : AppRoute
private data object PayloadScreenRoute : AppScreenRoute {
    @Composable
    override fun Content() = Unit
}

class AppNavigatorTest {
    @Test
    fun mutableNavigatorStartsWithInitialRoute() {
        val navigator = MutableAppNavigator(RootRoute)

        assertSame(RootRoute, navigator.currentRoute)
        assertEquals(listOf(RootRoute), navigator.routes)
        assertFalse(navigator.canPop)
    }

    @Test
    fun pushSuppressesDuplicateCurrentRoute() {
        val navigator = MutableAppNavigator(RootRoute)

        navigator.push(SettingsRoute)
        navigator.push(SettingsRoute)

        assertEquals(listOf(RootRoute, SettingsRoute), navigator.routes)
        assertSame(SettingsRoute, navigator.currentRoute)
    }

    @Test
    fun screenPayloadRoutesAreStoredAsSerializableRouteKeys() {
        val route = PayloadScreenRoute.toAppRoute()

        val payloadRoute = assertIs<AppRoute.Payload>(route)
        assertEquals(PayloadScreenRoute.routeKey, payloadRoute.routeKey)
        assertSame(PayloadScreenRoute, AppRoutePayloadStore.resolve(payloadRoute))
    }

    @Test
    fun duplicateScreenPayloadRouteIsReleasedWhenPushIsSuppressed() {
        val navigator = MutableAppNavigator(RootRoute)

        navigator.push(PayloadScreenRoute)
        val currentPayload = assertIs<AppRoute.Payload>(navigator.currentRoute)

        val duplicatePayload = assertIs<AppRoute.Payload>(PayloadScreenRoute.toAppRoute())
        navigator.push(duplicatePayload)

        assertEquals(listOf(RootRoute, currentPayload), navigator.routes)
        assertSame(PayloadScreenRoute, AppRoutePayloadStore.resolve(currentPayload))
        assertNull(AppRoutePayloadStore.resolve(duplicatePayload))
    }

    @Test
    fun popAndPopToRootReportWhetherStackChanged() {
        val navigator = MutableAppNavigator(RootRoute)

        assertFalse(navigator.pop())
        navigator.push(SettingsRoute)
        navigator.push(DetailsRoute)

        assertTrue(navigator.pop())
        assertSame(SettingsRoute, navigator.currentRoute)
        assertTrue(navigator.popToRoot())
        assertEquals(listOf(RootRoute), navigator.routes)
        assertFalse(navigator.popToRoot())
    }

    @Test
    fun replaceRootClearsStackAndUsesNewRoot() {
        val navigator = MutableAppNavigator(RootRoute)

        navigator.push(SettingsRoute)
        navigator.replaceRoot(DetailsRoute)

        assertEquals(listOf(DetailsRoute), navigator.routes)
        assertSame(DetailsRoute, navigator.currentRoute)
        assertFalse(navigator.canPop)
    }

    @Test
    fun mainStateDeliversPendingRouteWhenNavigatorRegisters() {
        val mainState = MainState()
        val navigator = MutableAppNavigator(RootRoute)

        mainState.requestOpenScreen(SettingsRoute)

        assertNull(mainState.currentRoute)

        mainState.updateNavigator(navigator)

        assertSame(SettingsRoute, mainState.currentRoute)
        assertEquals(listOf(RootRoute, SettingsRoute), navigator.routes)
    }

    @Test
    fun mainStateQueuesDirectPushUntilNavigatorRegisters() {
        val mainState = MainState()
        val navigator = MutableAppNavigator(RootRoute)

        mainState.pushScreen(SettingsRoute)

        assertNull(mainState.currentRoute)

        mainState.updateNavigator(navigator)

        assertSame(SettingsRoute, mainState.currentRoute)
        assertEquals(listOf(RootRoute, SettingsRoute), navigator.routes)
    }

    @Test
    fun staleNavigatorReleaseDoesNotClearNewNavigator() {
        val mainState = MainState()
        val oldNavigator = MutableAppNavigator(RootRoute)
        val newNavigator = MutableAppNavigator(RootRoute)
        mainState.updateNavigator(oldNavigator)
        mainState.updateNavigator(newNavigator)

        assertFalse(mainState.clearNavigator(oldNavigator))

        mainState.pushScreen(SettingsRoute)

        assertSame(newNavigator, mainState.navigator)
        assertSame(SettingsRoute, newNavigator.currentRoute)
        assertSame(RootRoute, oldNavigator.currentRoute)
    }

    @Test
    fun currentNavigatorReleaseClearsNavigator() {
        val mainState = MainState()
        val navigator = MutableAppNavigator(RootRoute)
        mainState.updateNavigator(navigator)

        assertTrue(mainState.clearNavigator(navigator))

        assertNull(mainState.navigator)
        assertNull(mainState.currentRoute)
    }

    @Test
    fun mainStateCollapsesDrawerOnCompactPush() {
        val mainState = MainState().apply {
            windowSize = WindowSizeClass.Compact
        }
        val navigator = MutableAppNavigator(RootRoute)
        mainState.updateNavigator(navigator)

        mainState.pushScreen(SettingsRoute)

        assertFalse(mainState.isExpandDrawer.value)
        assertSame(SettingsRoute, navigator.currentRoute)
    }

    @Test
    fun mainStateCollapsesDrawerOnModalPush() {
        val mainState = MainState().apply {
            windowSize = WindowSizeClass.Medium
            updateNavigationDrawerPresentation(usesModalDrawer = true)
        }
        val navigator = MutableAppNavigator(RootRoute)
        mainState.updateNavigator(navigator)

        mainState.pushScreen(SettingsRoute)

        assertFalse(mainState.isExpandDrawer.value)
        assertSame(SettingsRoute, navigator.currentRoute)
    }

    @Test
    fun mainStateKeepsDrawerOpenOnInlinePush() {
        val mainState = MainState().apply {
            windowSize = WindowSizeClass.Expanded
            updateNavigationDrawerPresentation(usesModalDrawer = false)
        }
        val navigator = MutableAppNavigator(RootRoute)
        mainState.updateNavigator(navigator)

        mainState.pushScreen(SettingsRoute)

        assertTrue(mainState.isExpandDrawer.value)
        assertSame(SettingsRoute, navigator.currentRoute)
    }

    @Test
    fun rootBackFallsThroughAndNonRootBackPops() {
        val navigator = MutableAppNavigator(RootRoute)

        assertFalse(navigator.handleBack())

        navigator.push(SettingsRoute)

        assertTrue(navigator.handleBack())
        assertSame(RootRoute, navigator.currentRoute)
    }
}
