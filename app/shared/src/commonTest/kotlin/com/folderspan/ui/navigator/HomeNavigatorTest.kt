package com.folderspan.ui.navigator

import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.v2.runComposeUiTest
import com.folderspan.shouldRefreshFileScreenOnResume
import com.folderspan.shouldShowStandaloneOnboarding
import com.folderspan.shouldShowWelcomeAgreement
import com.folderspan.test.createInMemorySettings
import com.folderspan.ui.navigation.AppRoute
import com.folderspan.ui.navigation.AppRoutePayloadStore
import com.folderspan.ui.navigation.AppScreenRoute
import com.folderspan.ui.navigation.BackStackAppNavigator
import com.folderspan.ui.navigation.LocalAppNavigator
import com.folderspan.ui.navigation.toAppRoute
import com.folderspan.ui.screen.main.ToolboxScreen
import com.folderspan.ui.state.main.MainState
import com.folderspan.utils.SettingsUtils
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

private const val PAYLOAD_CONTENT = "retained payload content"

private object TestPayloadScreen : AppScreenRoute {
    @Composable
    override fun Content() {
        Text(PAYLOAD_CONTENT)
    }
}

@OptIn(ExperimentalTestApi::class)
class HomeNavigatorTest {
    @Test
    fun homeNavigatorInitialScreenIsAlwaysHome() {
        SettingsUtils.init(createInMemorySettings())

        assertTrue(resolveHomeInitialRoute() is AppRoute.Home)
    }

    @Test
    fun resumeRefreshUsesHomeRouteInsteadOfVoyagerScreen() {
        assertTrue(
            shouldRefreshFileScreenOnResume(
                ignoreFirstResume = false,
                currentRoute = AppRoute.Home,
                isFileLoading = false
            )
        )
        assertFalse(
            shouldRefreshFileScreenOnResume(
                ignoreFirstResume = false,
                currentRoute = ToolboxScreen().toAppRoute(),
                isFileLoading = false
            )
        )
        assertFalse(
            shouldRefreshFileScreenOnResume(
                ignoreFirstResume = true,
                currentRoute = AppRoute.Home,
                isFileLoading = false
            )
        )
        assertFalse(
            shouldRefreshFileScreenOnResume(
                ignoreFirstResume = false,
                currentRoute = AppRoute.Home,
                isFileLoading = true
            )
        )
    }

    @Test
    fun appRootShowsWelcomeAgreementBeforeGuideWhenAgreementHasNotAccepted() {
        SettingsUtils.init(createInMemorySettings())

        assertTrue(shouldShowWelcomeAgreement())
        assertFalse(shouldShowStandaloneOnboarding())
    }

    @Test
    fun appRootShowsStandaloneOnboardingAfterAgreementWhenGuideHasNotCompleted() {
        SettingsUtils.init(
            createInMemorySettings(SettingsUtils.KEY_WELCOME_AGREEMENT_ACCEPTED to true)
        )

        assertFalse(shouldShowWelcomeAgreement())
        assertTrue(shouldShowStandaloneOnboarding())
    }

    @Test
    fun appRootDoesNotShowStandaloneOnboardingWhenGuideHasCompleted() {
        SettingsUtils.init(
            createInMemorySettings(
                SettingsUtils.KEY_WELCOME_AGREEMENT_ACCEPTED to true,
                SettingsUtils.KEY_ONBOARDING_COMPLETED to true
            )
        )

        assertFalse(shouldShowWelcomeAgreement())
        assertFalse(shouldShowStandaloneOnboarding())
    }

    @Test
    fun payloadEntryRetainsScreenAfterRoutePayloadIsReleased() = runComposeUiTest {
        val route = TestPayloadScreen.toAppRoute() as AppRoute.Payload
        val entry = homeNavEntry(route)

        AppRoutePayloadStore.release(route)
        assertNull(AppRoutePayloadStore.resolve(route))

        setContent {
            entry.Content()
        }

        onNodeWithText(PAYLOAD_CONTENT).assertIsDisplayed()
    }

    @Test
    fun missingPayloadEntryRecoversToHomeWithoutCrashing() = runComposeUiTest {
        val route = TestPayloadScreen.toAppRoute() as AppRoute.Payload
        AppRoutePayloadStore.release(route)
        val navigator = BackStackAppNavigator(mutableListOf(AppRoute.Home, route))
        val entry = homeNavEntry(route)

        setContent {
            CompositionLocalProvider(LocalAppNavigator provides navigator) {
                entry.Content()
            }
        }
        waitForIdle()

        assertSame(AppRoute.Home, navigator.currentRoute)
        assertNull(AppRoutePayloadStore.resolve(route))
    }

    @Test
    fun disposingNavigatorHostClearsRegistrationAndReleasesPayloads() = runComposeUiTest {
        val route = TestPayloadScreen.toAppRoute() as AppRoute.Payload
        val navigator = BackStackAppNavigator(mutableListOf(AppRoute.Home, route))
        val mainState = MainState()
        val mounted = mutableStateOf(true)

        setContent {
            if (mounted.value) {
                HomeNavigatorBindingEffect(mainState, navigator)
            }
        }
        waitForIdle()
        assertSame(navigator, mainState.navigator)
        assertSame(TestPayloadScreen, AppRoutePayloadStore.resolve(route))

        mounted.value = false
        waitForIdle()

        assertNull(mainState.navigator)
        assertNull(AppRoutePayloadStore.resolve(route))
    }
}
