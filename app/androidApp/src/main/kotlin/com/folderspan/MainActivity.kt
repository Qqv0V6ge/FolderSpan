package com.folderspan

import android.app.Application
import android.content.Intent
import android.content.res.Configuration
import android.os.Build
import android.os.Bundle
import android.view.View
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.tooling.preview.Preview
import com.folderspan.cleanup.ApplicationDataCleanup
import com.folderspan.data.main.share.Share
import com.folderspan.data.main.share.ShareProtocol
import com.folderspan.di.initKoin
import com.folderspan.localization.AppLanguageMode
import com.folderspan.localization.DefaultAppLanguagePlatform
import com.folderspan.notification.LocalNotifier
import com.folderspan.permission.PlatformPermissionProvider
import com.folderspan.root.RootManager
import com.folderspan.service.BackgroundService
import com.folderspan.share.ShareIntentHandler
import com.folderspan.shizuku.ShizukuManager
import com.folderspan.ui.navigation.AppRoute
import com.folderspan.ui.screen.file.share.FileShareScreen
import com.folderspan.ui.state.file.FileShareState
import com.folderspan.ui.state.file.FileState
import com.folderspan.ui.state.main.MainState
import com.folderspan.ui.state.settings.SettingsState
import com.folderspan.utils.PathUtils
import com.folderspan.utils.SettingsUtils
import com.folderspan.utils.installAppLogging
import com.mmk.kmpnotifier.KMPNotifier
import com.mmk.kmpnotifier.extensions.initialize
import com.mmk.kmpnotifier.extensions.onCreateOrOnNewIntent
import com.mmk.kmpnotifier.local.LocalNotifications
import com.mmk.kmpnotifier.notification.configuration.NotificationPlatformConfiguration
import io.github.aakira.napier.Napier
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import org.koin.java.KoinJavaComponent.inject
import strings.AppStrings

class AndroidApp : Application() {
    @OptIn(DelicateCoroutinesApi::class)
    override fun onCreate() {
        super.onCreate()
        AndroidContextHolder.initialize(this)
        KMPNotifier.initialize(
            this,
            NotificationPlatformConfiguration.Android(
                notificationIconResId = applicationInfo.icon,
                notificationChannelData = NotificationPlatformConfiguration.Android.NotificationChannelData(
                    id = "request_notifications",
                    name = AppStrings.android_request_notification_channel,
                    description = AppStrings.android_request_notification_channel_description,
                ),
            ),
            LocalNotifications,
        )
        LocalNotifier.initialize(askPermissionOnStart = false)
        ApplicationDataCleanup.completePendingCleanup()
            .onFailure { error -> Napier.e(AppStrings.ui_clear_folderspan_local_data_failed, error) }
        initKoin()
        ShizukuManager.initialize(this)
        RootManager.initialize(this)
        if (SettingsUtils.isRootRequestOnStartupEnabled() && !RootManager.hasPermission()) {
            GlobalScope.launch(Dispatchers.IO) {
                RootManager.requestPermission()
            }
        }
    }
}

class MainActivity : ComponentActivity() {
    companion object {
        const val ACTION_OPEN_FILE_SHARE = "com.folderspan.action.OPEN_FILE_SHARE"
        const val EXTRA_OPEN_FILE_SHARE = "com.folderspan.extra.OPEN_FILE_SHARE"
        const val SHORTCUT_ID_OPEN_FILE_SHARE = "open_file_share"
    }

    private val mainState: MainState by inject(MainState::class.java)
    private val fileState: FileState by inject(FileState::class.java)
    private val fileShareState: FileShareState by inject(FileShareState::class.java)
    private val settingsState: SettingsState by inject(SettingsState::class.java)
    private val launcherIconManager by lazy { LauncherIconManager(this) }
    private lateinit var shareIntentHandler: ShareIntentHandler
    private var platformRuntimeInitialized = false
    private var pendingDynamicLauncherIcon: Boolean? = null
    private val backPressedCallback = object : OnBackPressedCallback(true) {
        override fun handleOnBackPressed() {
            val navigator = mainState.navigator
            // 若还未建立导航，或已经在首页，则将应用移至后台；否则交由通用导航返回上一页
            when {
                navigator == null -> moveTaskToBack(true)
                navigator.canPop -> navigator.pop()
                else -> {
                    val currentPath = fileState.path.value
                    val deskType = fileState.deskType.value
                    val root = when (deskType) {
                        is Share -> "/"
                        else -> fileState.rootPath.value.path
                    }
                    if (currentPath == root) {
                        // 在首页且已是根目录，退至后台
                        moveTaskToBack(true)
                    } else {
                        if (deskType is Share && deskType.protocol == ShareProtocol.System) {
                            fileState.mainScope.launch {
                                fileState.updatePath("/")
                            }
                            return
                        }
                        // 在首页但非根目录，返回到上一级目录
                        val sep = PathUtils.getPathSeparator()
                        val trimmed = if (currentPath.endsWith(sep)) currentPath.removeSuffix(sep) else currentPath
                        val lastIndex = trimmed.lastIndexOf(sep)
                        val parent = if (lastIndex <= 0) root else trimmed.take(lastIndex)
                        fileState.mainScope.launch {
                            fileState.updatePath(if (parent.length < root.length) root else parent)
                        }
                    }
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        installAppLogging()

        super.onCreate(savedInstanceState)
        KMPNotifier.onCreateOrOnNewIntent(intent)
        PlatformPermissionProvider.bind(this)
        val launchIntent = intent
        if (launchIntent.hasExternalEntryAction()) {
            ensureShareIntentHandler()
            prepareInitialScreen(launchIntent)
        }

        onBackPressedDispatcher.addCallback(this, backPressedCallback)
        enableEdgeToEdge()
        setContent {
            val dynamicColorEnabled by settingsState.dynamicColorEnabled.collectAsState()
            val customColorEnabled by settingsState.customColorEnabled.collectAsState()
            val useDynamicLauncherIcon = dynamicColorEnabled && !customColorEnabled

            SideEffect {
                pendingDynamicLauncherIcon = useDynamicLauncherIcon
            }
            App(
                onFirstFrameRendered = {
                    reportFullyDrawn()
                    initializePlatformRuntime(launchIntent)
                },
            )
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        KMPNotifier.onCreateOrOnNewIntent(intent)
        ensureShareIntentHandler()
        prepareInitialScreen(intent)
        handleEntryIntent(intent)
    }

    override fun onResume() {
        super.onResume()
        synchronizeAppLanguage()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        synchronizeAppLanguage()
    }

    override fun onStop() {
        super.onStop()
        pendingDynamicLauncherIcon?.let { enabled ->
            pendingDynamicLauncherIcon = null
            launcherIconManager.setDynamicColorEnabled(enabled)
        }
    }

    private fun synchronizeAppLanguage() {
        DefaultAppLanguagePlatform.currentApplicationMode()?.let { platformMode ->
            settingsState.synchronizePlatformLanguageMode(platformMode)
        }
        if (settingsState.appLanguageMode.value == AppLanguageMode.System) {
            settingsState.refreshSystemLanguage()
        }
    }

    override fun onDestroy() {
        if (::shareIntentHandler.isInitialized) {
            shareIntentHandler.releaseDropPermissions()
        }
        super.onDestroy()
    }

    private fun setupDragAndDrop() {
        findViewById<View?>(android.R.id.content)?.setOnDragListener { _, event ->
            shareIntentHandler.handleDragEvent(event)
        }
    }

    private fun initializePlatformRuntime(initialIntent: Intent?) {
        ensureShareIntentHandler()
        if (!platformRuntimeInitialized) {
            platformRuntimeInitialized = true
            setupDragAndDrop()
            startBackgroundService()
        }
        prepareInitialScreen(initialIntent)
        handleEntryIntent(initialIntent)
    }

    private fun ensureShareIntentHandler() {
        if (::shareIntentHandler.isInitialized) return
        shareIntentHandler = ShareIntentHandler(
            activity = this,
            fileState = fileState,
            fileShareState = fileShareState,
            mainState = mainState,
        )
    }

    private fun prepareInitialScreen(intent: Intent?) {
        if (shareIntentHandler.hasShareData(intent)) {
            shareIntentHandler.prepare(intent)
            if (shareIntentHandler.opensFileShareScreen(intent)) {
                mainState.requestOpenScreen(FileShareScreen)
            } else {
                mainState.requestOpenScreen(AppRoute.Home)
            }
        }
    }

    private fun handleEntryIntent(intent: Intent?) {
        if (shareIntentHandler.handle(intent)) {
            return
        }
        handleShortcutIntent(intent)
    }

    private fun handleShortcutIntent(intent: Intent?) {
        val actionMatches = intent?.action == ACTION_OPEN_FILE_SHARE
        val extraMatches = intent?.getBooleanExtra(EXTRA_OPEN_FILE_SHARE, false) == true
        val shortcutIdMatches = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && intent?.getStringExtra(Intent.EXTRA_SHORTCUT_ID) == SHORTCUT_ID_OPEN_FILE_SHARE
        if (actionMatches || extraMatches || shortcutIdMatches) {
            mainState.requestOpenScreen(FileShareScreen)
        }
    }

    private fun startBackgroundService() {
        BackgroundService.start(this)
    }

    private fun Intent?.hasExternalEntryAction(): Boolean = when (this?.action) {
        Intent.ACTION_SEND,
        Intent.ACTION_SEND_MULTIPLE,
        Intent.ACTION_VIEW -> true
        else -> false
    }
}

@Preview
@Composable
fun AppAndroidPreview() {
    App()
}
