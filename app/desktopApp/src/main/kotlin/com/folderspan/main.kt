package com.folderspan

import com.folderspan.clipboard.ClipboardTextOpenBus
import androidx.compose.runtime.*
import androidx.compose.ui.ComposeUiFlags
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.pollSystemTheme
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.WindowPlacement
import androidx.compose.ui.window.application
import androidx.compose.ui.window.v2.Window
import androidx.compose.ui.window.v2.WindowBoundsProvider
import androidx.compose.ui.window.v2.WindowPositionProvider
import androidx.compose.ui.window.v2.WindowSizeProvider
import androidx.compose.ui.window.v2.WindowState
import androidx.compose.ui.window.v2.rememberWindowState
import com.folderspan.cleanup.ApplicationDataCleanup
import com.folderspan.data.file.FileSimpleInfo
import com.folderspan.data.main.device.DeviceCategory
import com.folderspan.data.main.device.DeviceConnectType
import com.folderspan.db.FolderSpanDatabase
import com.folderspan.di.initKoin
import com.folderspan.notification.LocalNotifier
import com.folderspan.notification.initializeDesktopNotificationBackend
import com.folderspan.service.data.ConnectType
import com.folderspan.service.data.SocketDevice
import com.folderspan.service.http.server.startBackgroundShareServices
import com.folderspan.ui.components.drawer.DeviceConnectNewDialog
import com.folderspan.ui.navigation.matchesScreen
import com.folderspan.ui.screen.file.share.FileShareScreen
import com.folderspan.ui.state.file.FileShareState
import com.folderspan.ui.state.file.FileState
import com.folderspan.ui.state.file.ClipboardUrlDownloadCoordinator
import com.folderspan.ui.state.file.normalizeShareListDropFiles
import com.folderspan.ui.state.main.DeviceState
import com.folderspan.ui.state.main.HomeState
import com.folderspan.ui.state.main.MainState
import com.folderspan.ui.state.settings.SettingsState
import com.folderspan.ui.tray.FolderSpanTray
import com.folderspan.utils.DesktopDpiSupport
import com.folderspan.utils.DesktopFileDropHandler
import com.folderspan.utils.awaitDatabaseReady
import com.folderspan.composeapp.generated.resources.Res
import com.folderspan.composeapp.generated.resources.app_icon
import com.mmk.kmpnotifier.KMPNotifier
import com.mmk.kmpnotifier.local.LocalNotifications
import com.mmk.kmpnotifier.notification.configuration.NotificationPlatformConfiguration
import com.folderspan.utils.installAppLogging
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.jetbrains.compose.resources.painterResource
import org.koin.core.context.GlobalContext
import java.awt.EventQueue
import java.awt.SystemTray
import java.awt.event.ComponentAdapter
import java.awt.event.ComponentEvent
import java.awt.event.WindowAdapter
import java.awt.event.WindowEvent
import java.beans.PropertyChangeListener
import java.io.File
import java.nio.file.Files
import strings.AppStrings
import java.awt.Window as AwtWindow

private var desktopNotificationsInitialized = false

@OptIn(ExperimentalComposeUiApi::class)
fun main(args: Array<String>) {
    ComposeUiFlags.pollSystemTheme = true

    ApplicationDataCleanup.completePendingCleanup().onFailure { error ->
        System.err.println("FolderSpan local data cleanup failed: ${error.message}")
    }
    installAppLogging()
    val startupSystemScale = DesktopDpiSupport.readSystemScale()
    DesktopDpiSupport.applyDesktopUiScaleProperty(systemScale = startupSystemScale)

    application {
        initKoin()

        val koin = remember { GlobalContext.get() }
        val externalPathArguments = remember(args) { args.toList() }
        var platformRuntimeReady by remember { mutableStateOf(false) }
        var isWindowVisible by remember { mutableStateOf(true) }
        var appWindow: AwtWindow? by remember { mutableStateOf(null) }
        var activeWindowScale by remember { mutableStateOf(startupSystemScale) }
        var trayConnectDevice by remember { mutableStateOf<SocketDevice?>(null) }
        val isTraySupported = remember { SystemTray.isSupported() }
        val openApp = {
            EventQueue.invokeLater {
                isWindowVisible = true
                appWindow?.apply {
                    isVisible = true
                    toFront()
                    requestFocus()
                }
            }
        }

        val initialWindowScale = startupSystemScale ?: 1f
        val windowState = rememberWindowState(
            initialBoundsProvider = WindowBoundsProvider(
                positionProvider = WindowPositionProvider.Default,
                sizeProvider = WindowSizeProvider.Fixed(
                    DpSize(
                        width = 800.dp * initialWindowScale,
                        height = 600.dp * initialWindowScale,
                    )
                ),
            ),
        )
        val minimumWindowScale = activeWindowScale ?: initialWindowScale

        Window(
            title = AppStrings.app_name,
            icon = painterResource(Res.drawable.app_icon),
            state = windowState,
            visible = isWindowVisible,
            minSize = DpSize(
                width = 350.dp * minimumWindowScale,
                height = 600.dp * minimumWindowScale,
            ),
            onCloseRequest = {
                if (isTraySupported) {
                    isWindowVisible = false
                } else {
                    exitApplication()
                }
            },
        ) {
            val windowScale = rememberWindowSystemScale(
                window = window,
                startupSystemScale = startupSystemScale
            )
            SideEffect {
                activeWindowScale = windowScale
            }
            SynchronizeDesktopWindowScale(
                windowState = windowState,
                systemScale = windowScale,
                initialWindowScale = initialWindowScale
            )

            ProvideDesktopDensity(systemScale = windowScale) {
                LaunchedEffect(window) {
                    appWindow = window
                }

                App(
                    onFirstFrameRendered = {
                        initializeDesktopNotifications()
                        platformRuntimeReady = true
                    },
                )

                if (platformRuntimeReady) {
                    val fileState = remember(koin) { koin.get<FileState>() }
                    val homeState = remember(koin) { koin.get<HomeState>() }

                    DisposableEffect(fileState, homeState, window) {
                        val dispose = DesktopFileDropHandler.install(
                            window, fileState, homeState, ClipboardTextOpenBus::publish,
                        )
                        onDispose { dispose() }
                    }
                }

                val pendingDevice = trayConnectDevice
                if (platformRuntimeReady && pendingDevice != null) {
                    val connectScope = rememberCoroutineScope()
                    val database = remember(koin) { koin.get<FolderSpanDatabase>() }
                    val deviceState = remember(koin) { koin.get<DeviceState>() }
                    DeviceConnectNewDialog(
                        socketDevice = pendingDevice,
                        onConnect = { isAuto ->
                            connectScope.launch {
                                database.deviceConnectQueries.upsert(
                                    id = pendingDevice.id,
                                    connectionType = if (isAuto) {
                                        DeviceConnectType.AUTO_CONNECT
                                    } else {
                                        DeviceConnectType.WAITING
                                    },
                                    category = DeviceCategory.CLIENT,
                                    roleId = -1L,
                                ).awaitDatabaseReady()
                                deviceState.updateSocketDeviceConnectType(pendingDevice, ConnectType.Loading)
                                deviceState.connectInBackground(pendingDevice)
                                trayConnectDevice = null
                            }
                        },
                        onCancel = { trayConnectDevice = null },
                    )
                }
            }
        }

        if (platformRuntimeReady) {
            val settingsState = remember(koin) { koin.get<SettingsState>() }
            val resolvedLanguage by settingsState.resolvedAppLanguage.collectAsState()

            key(resolvedLanguage) {
                FolderSpanTray(
                    isTraySupported = isTraySupported,
                    onOpenApp = openApp,
                    onRequestNewDevice = { item -> trayConnectDevice = item },
                    onExit = { exitApplication() },
                )
            }

            val fileShareState = remember(koin) { koin.get<FileShareState>() }
            LaunchedEffect(fileShareState) {
                launch(Dispatchers.IO) {
                    startBackgroundShareServices(fileShareState)
                }
            }

            val clipboardUrlDownloadCoordinator = remember(koin) {
                koin.get<ClipboardUrlDownloadCoordinator>()
            }
            DisposableEffect(clipboardUrlDownloadCoordinator) {
                onDispose { clipboardUrlDownloadCoordinator.teardown() }
            }

            val mainState = remember(koin) { koin.get<MainState>() }
            LaunchedEffect(externalPathArguments) {
                val entries = withContext(Dispatchers.IO) {
                    resolveDesktopExternalEntries(externalPathArguments)
                }
                if (entries.isNotEmpty()) {
                    fileShareState.updateIncomingFiles(normalizeShareListDropFiles(entries))
                    if (fileShareState.incomingFiles.isNotEmpty() &&
                        !mainState.currentRoute.matchesScreen(FileShareScreen)
                    ) {
                        mainState.requestOpenScreen(FileShareScreen)
                    }
                }
            }
        }
    }
}

private fun resolveDesktopExternalEntries(arguments: List<String>): List<FileSimpleInfo> {
    val seen = mutableSetOf<String>()
    return arguments.mapNotNull { argument ->
        val file = File(argument)
        if (!file.isFile || !file.canRead()) return@mapNotNull null
        val normalizedPath = runCatching { file.canonicalPath }.getOrElse { file.absolutePath }
        if (!seen.add(normalizedPath)) return@mapNotNull null
        val modifiedAt = file.lastModified().coerceAtLeast(0L)
        FileSimpleInfo(
            name = file.name.ifBlank { "external-file" },
            isDirectory = false,
            isHidden = file.isHidden,
            path = normalizedPath,
            mineType = runCatching { Files.probeContentType(file.toPath()) }.getOrNull().orEmpty(),
            size = file.length().coerceAtLeast(0L),
            createdDate = modifiedAt,
            updatedDate = modifiedAt,
        )
    }
}

private fun initializeDesktopNotifications() {
    if (desktopNotificationsInitialized) return
    desktopNotificationsInitialized = true
    KMPNotifier.initialize(
        NotificationPlatformConfiguration.Desktop(notificationIconPath = null),
        LocalNotifications,
    )
    initializeDesktopNotificationBackend()
    LocalNotifier.initialize(askPermissionOnStart = false)
}

@OptIn(ExperimentalComposeUiApi::class)
@Composable
private fun SynchronizeDesktopWindowScale(
    windowState: WindowState,
    systemScale: Float?,
    initialWindowScale: Float
) {
    val targetScale = systemScale ?: initialWindowScale
    var appliedScale by remember(windowState) { mutableFloatStateOf(initialWindowScale) }
    val isWindowStateInitialized = windowState.isInitialized
    val windowPlacement = if (isWindowStateInitialized) windowState.placement else null

    LaunchedEffect(windowState, isWindowStateInitialized, windowPlacement, targetScale) {
        if (!isWindowStateInitialized || windowPlacement != WindowPlacement.Floating) {
            return@LaunchedEffect
        }
        DesktopDpiSupport.resolveWindowResizeRatio(
            currentScale = appliedScale,
            targetScale = targetScale
        )?.let { resizeRatio ->
            val currentSize = windowState.size
            if (currentSize.width.value.isFinite() && currentSize.height.value.isFinite()) {
                windowState.requestSize(
                    DpSize(
                        width = currentSize.width * resizeRatio,
                        height = currentSize.height * resizeRatio,
                    )
                )
            }
        }
        appliedScale = targetScale
    }
}

@Composable
private fun rememberWindowSystemScale(
    window: AwtWindow,
    startupSystemScale: Float?
): Float? {
    var systemScale by remember(window, startupSystemScale) {
        mutableStateOf(startupSystemScale)
    }
    val coroutineScope = rememberCoroutineScope()

    DisposableEffect(window, startupSystemScale, coroutineScope) {
        var refreshJob: Job? = null

        fun updateFromWindow(fallbackSystemScale: Float?) {
            DesktopDpiSupport.readWindowScale(
                graphicsConfiguration = window.graphicsConfiguration,
                fallbackSystemScale = fallbackSystemScale
            )?.let { detectedScale ->
                systemScale = detectedScale
            }
        }

        fun requestSystemScaleRefresh(delayMillis: Long = 0L) {
            refreshJob?.cancel()
            refreshJob = coroutineScope.launch {
                if (delayMillis > 0L) delay(delayMillis)
                val refreshedSystemScale = withContext(Dispatchers.IO) {
                    DesktopDpiSupport.readSystemScale()
                }
                updateFromWindow(refreshedSystemScale ?: systemScale)
            }
        }

        val graphicsConfigurationListener = PropertyChangeListener { event ->
            if (event.propertyName != "graphicsConfiguration") return@PropertyChangeListener
            updateFromWindow(systemScale)
            requestSystemScaleRefresh()
        }
        val componentListener = object : ComponentAdapter() {
            override fun componentMoved(event: ComponentEvent) {
                updateFromWindow(systemScale)
                requestSystemScaleRefresh(delayMillis = 300L)
            }

            override fun componentShown(event: ComponentEvent) {
                updateFromWindow(systemScale)
                requestSystemScaleRefresh()
            }
        }
        val focusListener = object : WindowAdapter() {
            override fun windowGainedFocus(event: WindowEvent) {
                updateFromWindow(systemScale)
                requestSystemScaleRefresh()
            }
        }

        updateFromWindow(startupSystemScale)
        window.addPropertyChangeListener("graphicsConfiguration", graphicsConfigurationListener)
        window.addComponentListener(componentListener)
        window.addWindowFocusListener(focusListener)

        onDispose {
            refreshJob?.cancel()
            window.removePropertyChangeListener("graphicsConfiguration", graphicsConfigurationListener)
            window.removeComponentListener(componentListener)
            window.removeWindowFocusListener(focusListener)
        }
    }

    return systemScale
}

@Composable
private fun ProvideDesktopDensity(
    systemScale: Float?,
    content: @Composable () -> Unit
) {
    val baseDensity = LocalDensity.current
    val overrideDensity = remember(systemScale, baseDensity.density) {
        DesktopDpiSupport.resolveDensityOverride(
            currentDensity = baseDensity.density,
            systemScale = systemScale
        )
    }

    if (overrideDensity == null) {
        content()
        return
    }

    val density = remember(overrideDensity, baseDensity.fontScale) {
        Density(density = overrideDensity, fontScale = baseDensity.fontScale)
    }

    CompositionLocalProvider(LocalDensity provides density) {
        content()
    }
}
