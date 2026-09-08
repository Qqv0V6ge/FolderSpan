package com.folderspan

import androidx.compose.runtime.Composable
import com.folderspan.notification.NotificationPayload
import com.folderspan.notification.RequestNotificationDispatcher
import com.folderspan.service.account.AccountDeviceTrustRefreshTrigger
import com.folderspan.service.account.NoOpAccountDeviceTrustRefreshTrigger
import com.folderspan.ui.components.drawer.AppDrawerAccountHeader
import com.folderspan.ui.effects.UserNotificationLifecycleEffect
import com.folderspan.ui.navigation.AppScreenRoute
import com.folderspan.ui.state.main.MainState
import com.russhwolf.settings.Settings
import org.koin.core.Koin
import org.koin.core.module.Module
import strings.AppStrings

internal const val PRO_AVAILABLE = false
internal object ProRuntimeDependencies
internal fun loadProRuntimeDependencies(koin: Koin) = ProRuntimeDependencies

@Composable
internal fun ProRuntimeEffects(dependencies: ProRuntimeDependencies, settings: Settings) {
    UserNotificationLifecycleEffect(RequestNotificationDispatcher::setAppInForeground)
}

@Composable
internal fun ProContentProviders(content: @Composable () -> Unit) = content()

@Composable
internal fun proNotificationUnreadCount(): Int = 0

@Composable
internal fun isProAuthenticated(): Boolean = false

internal fun proFeedbackScreen(report: String? = null): AppScreenRoute? = null
internal fun proManualDataSyncScreen(): AppScreenRoute? = null
internal fun handleProNotificationClick(data: NotificationPayload, mainState: MainState): Boolean = false

internal fun Module.proBindings() {
    single<AccountDeviceTrustRefreshTrigger> { NoOpAccountDeviceTrustRefreshTrigger }
}

@Composable
internal fun rememberProAccountHeader() = AppDrawerAccountHeader(title = AppStrings.app_name)

fun appDrawerAccountHeaderClickRoute(): AppScreenRoute? = null
