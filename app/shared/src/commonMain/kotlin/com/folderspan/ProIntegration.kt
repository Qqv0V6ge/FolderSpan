package com.folderspan

import androidx.compose.runtime.*
import com.folderspan.notification.AppNotificationRouteScreenFactory
import com.folderspan.notification.ErrorLogReporter
import com.folderspan.notification.NotificationPayload
import com.folderspan.notification.RequestNotificationDispatcher
import com.folderspan.notification.openAccountNotification
import com.folderspan.notification.primaryAccountNotificationActionFromPayload
import com.folderspan.pro.core.datastore.AuthSession
import com.folderspan.pro.core.datastore.AuthSessionStore
import com.folderspan.pro.core.datastore.SessionManager
import com.folderspan.pro.core.datastore.SettingsAuthSessionStore
import com.folderspan.pro.core.network.installProWebRtcOfficialGatewayProvider
import com.folderspan.pro.core.network.installProWebRtcOfficialRoomsClient
import com.folderspan.pro.di.*
import com.folderspan.pro.domain.model.AccountNotificationPayloadKeys
import com.folderspan.pro.domain.model.AppUpdateChannel
import com.folderspan.pro.presentation.navigation.ProRoutes
import com.folderspan.pro.presentation.screen.feedback.ErrorLogFeedbackLaunch
import com.folderspan.pro.presentation.screen.sync.registerProDeviceSettingChangeSync
import com.folderspan.pro.presentation.screen.sync.registerProSnapshotChangeSync
import com.folderspan.service.account.AccountDeviceTrustRefreshTrigger
import com.folderspan.ui.components.avatar.AvatarPicturePickerProvider
import com.folderspan.ui.components.dialog.FeedbackAttachmentPickerProvider
import com.folderspan.ui.components.drawer.AppDrawerAccountHeader
import com.folderspan.ui.components.filter.FeedbackTicketFilterSheetProvider
import com.folderspan.ui.effects.OnAppResumeEffect
import com.folderspan.ui.effects.UserNotificationLifecycleEffect
import com.folderspan.ui.navigation.AppScreenRoute
import com.folderspan.ui.state.main.MainState
import com.folderspan.utils.SettingsUtils
import com.russhwolf.settings.Settings
import kotlinx.coroutines.*
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import org.koin.compose.koinInject
import org.koin.core.Koin
import org.koin.core.module.Module
import strings.AppStrings

internal const val PRO_AVAILABLE = true

internal data class ProRuntimeDependencies(
    val accountDeviceTrustRuntime: AccountDeviceTrustRuntime,
    val userNotificationRuntime: UserNotificationRuntime,
    val announcementRuntime: AnnouncementRuntime,
    val appUpdateRuntime: AppUpdateRuntime,
)

internal fun loadProRuntimeDependencies(koin: Koin) = ProRuntimeDependencies(
    accountDeviceTrustRuntime = koin.get(),
    userNotificationRuntime = koin.get(),
    announcementRuntime = koin.get(),
    appUpdateRuntime = koin.get(),
)

internal fun initializeProAuthSession(settings: Settings) {
    initializeProAuthSession(SettingsAuthSessionStore(settings))
}

internal fun initializeProAuthSession(store: AuthSessionStore) {
    SessionManager.initialize(store)
}

internal suspend fun pullProDeviceSettingsIfLoggedIn(
    settings: Settings,
    servicesProvider: () -> AppServices = { AppServices(settings) },
) {
    val token = SessionManager.currentToken()?.takeIf { it.isNotBlank() } ?: return
    val services = withContext(Dispatchers.Default) { servicesProvider() }
    runCatching {
        services.deviceSettingsSyncService.sync(settings, token)
    }
    withContext(NonCancellable + Dispatchers.Default) { services.close() }
}

@Composable
internal fun ProRuntimeEffects(dependencies: ProRuntimeDependencies, settings: Settings) {
    val accountDeviceTrustRuntime = dependencies.accountDeviceTrustRuntime
    val userNotificationRuntime = dependencies.userNotificationRuntime
    val announcementRuntime = dependencies.announcementRuntime
    val appUpdateRuntime = dependencies.appUpdateRuntime
    val scope = rememberCoroutineScope()
    var ignoreFirstResume by remember { mutableStateOf(true) }
    UserNotificationLifecycleEffect(remember(userNotificationRuntime) {
        { foreground ->
            if (foreground) userNotificationRuntime.enterForeground() else userNotificationRuntime.enterBackground()
        }
    })

    DisposableEffect(accountDeviceTrustRuntime, scope) {
        accountDeviceTrustRuntime.start(scope)
        onDispose(accountDeviceTrustRuntime::cancel)
    }

    DisposableEffect(userNotificationRuntime, scope) {
        userNotificationRuntime.start(scope)
        onDispose(userNotificationRuntime::cancel)
    }

    DisposableEffect(announcementRuntime, scope) {
        announcementRuntime.start(scope)
        onDispose(announcementRuntime::cancel)
    }

    DisposableEffect(appUpdateRuntime, scope) {
        appUpdateRuntime.start(scope)
        onDispose(appUpdateRuntime::cancel)
    }

    LaunchedEffect(Unit) {
        installProWebRtcOfficialGatewayProvider()
        installProWebRtcOfficialRoomsClient()
        initializeProAuthSession(settings)
        registerProDeviceSettingChangeSync(settings, scope)
        registerProSnapshotChangeSync(settings, scope)
        pullProDeviceSettingsIfLoggedIn(settings)
    }
    OnAppResumeEffect {
        scope.launch(Dispatchers.Default) {
            accountDeviceTrustRuntime.refreshIfNeeded(force = false)
        }
        if (!ignoreFirstResume) {
            scope.launch { announcementRuntime.refresh(reset = true) }
        }
        ignoreFirstResume = false
    }
}

@Composable
internal fun ProContentProviders(content: @Composable () -> Unit) {
    FeedbackTicketFilterSheetProvider {
        FeedbackAttachmentPickerProvider {
            AvatarPicturePickerProvider(content)
        }
    }
}

@Composable
internal fun proNotificationUnreadCount(): Int =
    koinInject<UserNotificationRuntime>().state.collectAsState().value.unreadTotal

@Composable
internal fun isProAuthenticated(): Boolean = SessionManager.session.collectAsState().value != null

internal fun proFeedbackScreen(report: String? = null): AppScreenRoute? {
    if (report == null) return ProRoutes.feedbackScreen()
    ErrorLogFeedbackLaunch.offer(ErrorLogReporter.consumePendingFeedback(report))
    return AppNotificationRouteScreenFactory().feedbackForm()
}

internal fun proManualDataSyncScreen(): AppScreenRoute? = ProRoutes.manualDataSyncScreen()

internal fun handleProNotificationClick(data: NotificationPayload, mainState: MainState): Boolean {
    if (data[AccountNotificationPayloadKeys.Source] != AccountNotificationPayloadKeys.SourceAccount) return false
    openAccountNotification(mainState = mainState, action = primaryAccountNotificationActionFromPayload(data))
    return true
}

internal fun Module.proBindings() {
    single { AccountDeviceTrustRuntime(get(), get(), get()) }
    single {
        UserNotificationRuntime(
            onForegroundChanged = RequestNotificationDispatcher::setAppInForeground,
        )
    }
    single { AnnouncementRuntime() }
    single {
        get<Settings>()
        AppUpdateRuntime(
            notificationState = get(),
            readChannel = { AppUpdateChannel.fromToken(SettingsUtils.appUpdateChannel()) },
            writeChannel = { channel -> SettingsUtils.setAppUpdateChannel(channel.token) },
        )
    }
    single<AccountDeviceTrustRefreshTrigger> { get<AccountDeviceTrustRuntime>() }
}

@Composable
internal fun rememberProAccountHeader(): AppDrawerAccountHeader {
    val session by SessionManager.session.collectAsState()
    return remember(session) { appDrawerAccountHeader(session) }
}

fun appDrawerAccountHeaderClickRoute(): AppScreenRoute? =
    ProRoutes.userProfileScreen()

fun appDrawerAccountHeader(session: AuthSession?): AppDrawerAccountHeader {
    if (session == null) return AppDrawerAccountHeader(title = AppStrings.app_name)

    val loginData = session.loginData
    val profileSnapshot = loginData.objectValue("profile")
    val name = profileSnapshot.stringValue("name")
        ?: profileSnapshot.stringValue("nickname")
        ?: profileSnapshot.stringValue("username")
        ?: loginData.stringValue("name")
        ?: loginData.stringValue("nickname")
        ?: loginData.stringValue("username")
        ?: loginData.objectValue("user").stringValue("name")
        ?: loginData.objectValue("user").stringValue("nickname")
        ?: loginData.objectValue("user").stringValue("username")
    val email = profileSnapshot.stringValue("email")
        ?: session.userEmail
        ?: loginData.stringValue("email")
        ?: loginData.stringValue("userEmail")
        ?: loginData.objectValue("user").stringValue("email")
    val title = name ?: email ?: AppStrings.ui_logged_user
    val avatarUrl = if (profileSnapshot?.containsKey("avatar") == true) {
        profileSnapshot.stringValue("avatar")
    } else {
        loginData.stringValue("avatar")
            ?: loginData.stringValue("avatarUrl")
            ?: loginData.objectValue("user").stringValue("avatar")
            ?: loginData.objectValue("user").stringValue("avatarUrl")
    }

    return AppDrawerAccountHeader(
        title = title,
        subtitle = email?.takeIf { it != title },
        avatarLabel = accountAvatarLabel(title),
        avatarUrl = avatarUrl,
    )
}

private fun accountAvatarLabel(title: String): String =
    title.trim().firstOrNull()?.toString()?.uppercase() ?: "#"

private fun JsonObject?.stringValue(name: String): String? =
    (this?.get(name) as? JsonPrimitive)
        ?.contentOrNull
        ?.trim()
        ?.takeIf { it.isNotEmpty() }

private fun JsonObject?.objectValue(name: String): JsonObject? =
    this?.get(name) as? JsonObject
