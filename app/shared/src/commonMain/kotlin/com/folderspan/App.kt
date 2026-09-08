package com.folderspan

import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import com.folderspan.crash.installPlatformCrashHandler
import com.folderspan.data.main.device.DeviceType
import com.folderspan.data.main.share.Share
import com.folderspan.data.main.share.ShareProtocol
import com.folderspan.notification.ErrorLogReporter
import com.folderspan.notification.NotificationDeepLinkHandler
import com.folderspan.notification.StartupPermissionReminderCoordinator
import com.folderspan.notification.initializeNotifications
import com.folderspan.utils.LogCapture
import com.folderspan.service.http.client.HttpRouteClientManager.Companion.PORT
import com.folderspan.service.http.server.SocketClientIPEnum
import com.folderspan.service.http.server.getAllIPAddresses
import com.folderspan.service.mcp.McpServerSettingsStore
import com.folderspan.service.mcp.http.McpHttpServiceLifecycle
import com.folderspan.service.mcp.http.McpHttpServiceInterface
import com.folderspan.service.session.usesTriggeredDeviceDiscovery
import com.folderspan.ui.components.crash.CrashBoundary
import com.folderspan.ui.components.dialog.HttpDeviceConnectionFailureDialog
import com.folderspan.ui.components.image.ImagePreviewHost
import com.folderspan.ui.effects.OnAppResumeEffect
import com.folderspan.ui.effects.McpServerLifecycleEffect
import com.folderspan.ui.navigation.AppRoute
import com.folderspan.ui.navigation.matchesScreen
import com.folderspan.ui.navigator.rememberHomeNavigatorContent
import com.folderspan.ui.screen.crash.CrashScreen
import com.folderspan.ui.screen.main.HomeScreen
import com.folderspan.ui.screen.main.MainScreen
import com.folderspan.ui.screen.onboarding.OnboardingScreen
import com.folderspan.ui.screen.onboarding.WelcomeAgreementScreen
import com.folderspan.ui.state.file.FileShareState
import com.folderspan.ui.state.file.FileState
import com.folderspan.ui.state.main.*
import com.folderspan.ui.state.settings.SettingsState
import com.folderspan.ui.state.settings.ThemeMode
import com.folderspan.ui.theme.*
import com.folderspan.utils.SettingsUtils
import com.folderspan.utils.calculateWindowSizeClass
import com.russhwolf.settings.Settings
import kotlinx.coroutines.*
import org.koin.compose.getKoin
import org.koin.compose.koinInject
import org.koin.core.Koin
import kotlin.time.Duration.Companion.milliseconds

private const val SYSTEM_SHARE_RESUME_REFRESH_RETRY_INTERVAL_MS = 500L
private const val SYSTEM_SHARE_RESUME_REFRESH_MAX_ATTEMPTS = 10

private data class AppRuntimeDependencies(
    val deviceState: DeviceState,
    val fileState: FileState,
    val notificationState: NotificationState,
    val fileShareState: FileShareState,
    val settings: Settings,
    val mcpHttpService: McpHttpServiceInterface,
    val mcpSettingsStore: McpServerSettingsStore,
    val pro: ProRuntimeDependencies,
)

private suspend fun loadAppRuntimeDependencies(koin: Koin): AppRuntimeDependencies =
    withContext(Dispatchers.Default) {
        AppRuntimeDependencies(
            deviceState = koin.get(),
            fileState = koin.get(),
            notificationState = koin.get(),
            fileShareState = koin.get(),
            settings = koin.get(),
            mcpHttpService = koin.get(),
            mcpSettingsStore = koin.get(),
            pro = loadProRuntimeDependencies(koin),
        )
    }

@Composable
fun FolderSpanAppTheme(
    content: @Composable () -> Unit
) {
    val mainState = koinInject<MainState>()
    val settingsState = koinInject<SettingsState>()

    val remoteColorScheme by mainState.remoteColorScheme.collectAsState()
    val themeMode by settingsState.themeMode.collectAsState()
    val resolvedAppLanguage by settingsState.resolvedAppLanguage.collectAsState()
    val dynamicColorEnabled by settingsState.dynamicColorEnabled.collectAsState()
    val customColorEnabled by settingsState.customColorEnabled.collectAsState()
    val customSeedHex by settingsState.customSeedColor.collectAsState()
    val systemDarkTheme = isSystemInDarkTheme()
    val resolvedDarkTheme = when (themeMode) {
        ThemeMode.System -> systemDarkTheme
        ThemeMode.Light -> false
        ThemeMode.Dark -> true
    }

    LaunchedEffect(resolvedDarkTheme) {
        mainState.setCurrentDarkTheme(resolvedDarkTheme)
    }

    val customSeedColor = parseHexColor(customSeedHex)
    val customColorScheme = customSeedColor?.let { item ->  CustomColorScheme(seed = item) }
    val customColorsActive = customColorEnabled && customColorScheme != null
    val isAndroid = PlatformType == DeviceType.Android
    val allowDynamicColor = dynamicColorEnabled && isAndroid && !customColorsActive

    val baseLightColorScheme = if (allowDynamicColor) {
        dynamicColorScheme(false) ?: getDefaultColorScheme(false)
    } else {
        getDefaultColorScheme(false)
    }
    val baseDarkColorScheme = if (allowDynamicColor) {
        dynamicColorScheme(true) ?: getDefaultColorScheme(true)
    } else {
        getDefaultColorScheme(true)
    }

    val lightColorScheme = if (customColorsActive) {
        baseLightColorScheme.withCustomColors(customColorScheme, darkTheme = false)
    } else {
        baseLightColorScheme
    }
    val darkColorScheme = if (customColorsActive) {
        baseDarkColorScheme.withCustomColors(customColorScheme, darkTheme = true)
    } else {
        baseDarkColorScheme
    }

    val overrideColorScheme = remoteColorScheme ?: if (customColorsActive) {
        if (resolvedDarkTheme) darkColorScheme else lightColorScheme
    } else null

    LaunchedEffect(lightColorScheme, darkColorScheme) {
        mainState.setCurrentColorSchemes(ColorSchemes(lightColorScheme, darkColorScheme))
    }

    key(resolvedAppLanguage) {
        FolderSpanTheme(
            darkTheme = resolvedDarkTheme,
            dynamicColor = allowDynamicColor,
            overrideColorScheme = overrideColorScheme
        ) {
            content()
        }
    }
}

internal fun shouldRefreshFileScreenOnResume(
    ignoreFirstResume: Boolean,
    currentRoute: AppRoute?,
    isFileLoading: Boolean
): Boolean {
    return !(ignoreFirstResume || isFileLoading) && (currentRoute == AppRoute.Home || currentRoute.matchesScreen(HomeScreen))
}

internal fun shouldRetrySystemShareResumeRefresh(previousPaths: Set<String>, currentPaths: Set<String>): Boolean {
    return previousPaths == currentPaths
}

internal fun shouldShowWelcomeAgreement(): Boolean =
    !SettingsUtils.isWelcomeAgreementAccepted()

internal fun shouldShowStandaloneOnboarding(): Boolean =
    SettingsUtils.isWelcomeAgreementAccepted() && !SettingsUtils.isOnboardingCompleted()

@Composable
fun App(
    overlayContent: @Composable () -> Unit = {},
    onFirstFrameRendered: () -> Unit = {},
) {
    val mainState = koinInject<MainState>()
    val crashState = koinInject<CrashState>()
    val crashInfo by crashState.crashInfo.collectAsState()
    val restartToken by crashState.restartToken.collectAsState()
    var showWelcomeAgreement by remember { mutableStateOf(shouldShowWelcomeAgreement()) }
    var showStandaloneOnboarding by remember { mutableStateOf(shouldShowStandaloneOnboarding()) }
    val runtimeReady = rememberAppRuntimeReady(onFirstFrameRendered)
    val koin = getKoin()
    var runtimeDependencies by remember(koin) { mutableStateOf<AppRuntimeDependencies?>(null) }

    LaunchedEffect(runtimeReady, koin) {
        if (runtimeReady && runtimeDependencies == null) {
            runtimeDependencies = loadAppRuntimeDependencies(koin)
        }
    }

    runtimeDependencies?.let { dependencies ->
        AppRuntimeEffects(mainState, crashState, dependencies)
    }

    val homeNavigatorContent = rememberHomeNavigatorContent()

    FolderSpanAppTheme {
        BoxWithConstraints(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background)
                .windowInsetsPadding(WindowInsets.safeDrawing)
        ) {
            val windowSizeClass = calculateWindowSizeClass(maxWidth, maxHeight)
            mainState.windowSize = windowSizeClass
            val crashScreenState = crashInfo?.toScreenState()
            val settingsState = koinInject<SettingsState>()
            val autoCaptureLogs by settingsState.autoCaptureLogs.collectAsState()

            if (crashScreenState != null) {
                CrashScreen(
                    state = crashScreenState,
                    onRestart = { crashState.requestRestart() },
                    showFeedback = PRO_AVAILABLE && autoCaptureLogs,
                    onFeedback = {
                        proFeedbackScreen(crashScreenState.reportText)?.let { screen ->
                            crashState.clearCrash()
                            mainState.requestOpenScreen(screen)
                        }
                    },
                )
            } else {
                key(restartToken) {
                    CrashBoundary {
                        ImagePreviewHost {
                            Box(modifier = Modifier.fillMaxSize()) {
                            when {
                                showWelcomeAgreement -> {
                                    WelcomeAgreementScreen(
                                        onAccepted = {
                                            showWelcomeAgreement = false
                                            showStandaloneOnboarding = shouldShowStandaloneOnboarding()
                                        }
                                    ).Content()
                                }

                                showStandaloneOnboarding -> {
                                    OnboardingScreen(
                                        onCompleted = {
                                            showStandaloneOnboarding = false
                                        }
                                    ).Content()
                                }

                                else -> {
                                    MainScreen(windowSizeClass, homeNavigatorContent)
                                }
                            }
                                overlayContent()
                            }
                        }
                    }
                }
            }

            runtimeDependencies?.let { dependencies ->
                AppRuntimeOverlay(deviceState = dependencies.deviceState)
            }
        }
    }
}

@Composable
internal fun rememberAppRuntimeReady(
    onFirstFrameRendered: () -> Unit,
): Boolean {
    var runtimeReady by remember { mutableStateOf(false) }
    val currentOnFirstFrameRendered by rememberUpdatedState(onFirstFrameRendered)

    LaunchedEffect(Unit) {
        withFrameNanos { }
        withFrameNanos { }
        currentOnFirstFrameRendered()
        runtimeReady = true
    }

    return runtimeReady
}

@Composable
private fun AppRuntimeEffects(
    mainState: MainState,
    crashState: CrashState,
    dependencies: AppRuntimeDependencies,
) {
    val deviceState = dependencies.deviceState
    val fileState = dependencies.fileState
    val notificationState = dependencies.notificationState
    val fileShareState = dependencies.fileShareState
    val settings = dependencies.settings
    val mcpHttpService = dependencies.mcpHttpService
    val mcpSettingsStore = dependencies.mcpSettingsStore
    val scope = rememberCoroutineScope()
    val triggeredDeviceDiscovery = PlatformType.usesTriggeredDeviceDiscovery()
    val loadingDevices by deviceState.loadingDevices.collectAsState()
    val isFileLoading by fileState.isLoading.collectAsState()
    val isExternalShareHandling by fileState.isExternalShareHandling.collectAsState()
    var ignoreFirstResume by remember { mutableStateOf(true) }
    var pendingResumeRefreshJob by remember { mutableStateOf<Job?>(null) }
    val mcpServiceLifecycle = remember(mcpHttpService, mcpSettingsStore) {
        McpHttpServiceLifecycle(mcpHttpService, mcpSettingsStore::read)
    }

    McpServerLifecycleEffect(mcpServiceLifecycle)
    ProRuntimeEffects(dependencies.pro, settings)

    LaunchedEffect(Unit) {
        ErrorLogReporter.attach(notificationState)
        LogCapture.setEnabled(SettingsUtils.getBoolean(SettingsUtils.KEY_AUTO_CAPTURE_LOGS, false))
        installPlatformCrashHandler { item -> crashState.recordCrash(item) }
    }

    // 浏览器从后台/隐藏恢复时刷新 WebRTC 设备发现；原生端持续监听 LAN beacon。
    OnAppResumeEffect {
        val isFirstResume = ignoreFirstResume
        if (ignoreFirstResume) {
            ignoreFirstResume = false
        }

        if (isExternalShareHandling) {
            pendingResumeRefreshJob?.cancel()
            return@OnAppResumeEffect
        }

        val currentRoute = mainState.currentRoute
        if (shouldRefreshFileScreenOnResume(isFirstResume, currentRoute, isFileLoading)) {
            pendingResumeRefreshJob?.cancel()
            val resumeDesk = fileState.deskType.value
            val resumePath = fileState.path.value
            val initialVisiblePaths = fileState.fileAndFolder.map { item -> item.path }.toSet()
            val isSystemShareDesk = resumeDesk is Share && resumeDesk.protocol == ShareProtocol.System

            pendingResumeRefreshJob = scope.launch {
                if (!isSystemShareDesk) {
                    fileState.updateFileAndFolder()
                    return@launch
                }

                repeat(SYSTEM_SHARE_RESUME_REFRESH_MAX_ATTEMPTS) { attempt ->
                    val latestRoute = mainState.currentRoute
                    if (!shouldRefreshFileScreenOnResume(false, latestRoute, fileState.isLoading.value)) {
                        return@launch
                    }
                    if (fileState.deskType.value != resumeDesk || fileState.path.value != resumePath) {
                        return@launch
                    }

                    fileState.updateFileAndFolder()

                    val currentVisiblePaths = fileState.fileAndFolder.map { item -> item.path }.toSet()
                    val isLastAttempt = attempt == SYSTEM_SHARE_RESUME_REFRESH_MAX_ATTEMPTS - 1
                    if (!shouldRetrySystemShareResumeRefresh(initialVisiblePaths, currentVisiblePaths) || isLastAttempt) {
                        return@launch
                    }

                    delay(SYSTEM_SHARE_RESUME_REFRESH_RETRY_INTERVAL_MS.milliseconds)
                }
            }
        }

        if (!triggeredDeviceDiscovery || isFirstResume || loadingDevices) return@OnAppResumeEffect

        scope.launch(Dispatchers.Default) {
            deviceState.scanner(
                getAllIPAddresses(type = SocketClientIPEnum.IPV4_UP),
                settings.getInt(SettingsUtils.KEY_FILE_SHARE_PORT, PORT)
            )
        }
    }

    LaunchedEffect(Unit) {
        initializeNotifications()
        NotificationDeepLinkHandler.register(
            mainState,
            notificationState,
            deviceState,
            fileShareState
        )
        StartupPermissionReminderCoordinator.evaluateAndNotify(notificationState)
    }

    LaunchedEffect(Unit) {
        if (!triggeredDeviceDiscovery) return@LaunchedEffect
        withContext(Dispatchers.Default) {
            deviceState.scanner(
                getAllIPAddresses(type = SocketClientIPEnum.IPV4_UP),
                settings.getInt(SettingsUtils.KEY_FILE_SHARE_PORT, PORT)
            )
        }
    }
}

@Composable
private fun AppRuntimeOverlay(deviceState: DeviceState) {
    val httpDeviceConnectionFailure by deviceState.httpDeviceConnectionFailure.collectAsState()

    httpDeviceConnectionFailure?.let { failure ->
        HttpDeviceConnectionFailureDialog(
            failure = failure,
            onDismiss = deviceState::consumeHttpDeviceConnectionFailure,
            onTrustNewCertificate = deviceState::trustHttpDeviceConnectionFailureNewCertificateAndConnect,
        )
    }
}

expect fun openUrl(url: String?)
