package com.folderspan.crash

import com.folderspan.createSettings
import com.folderspan.ui.state.main.CrashInfo
import com.folderspan.utils.SettingsUtils
import com.russhwolf.settings.Settings
import platform.Foundation.NSNotificationCenter
import platform.UIKit.UIApplicationDidEnterBackgroundNotification
import platform.UIKit.UIApplicationWillEnterForegroundNotification
import platform.UIKit.UIApplicationWillTerminateNotification
import platform.posix.exit

actual fun installPlatformCrashHandler(onCrash: (CrashInfo) -> Unit) {
    // Kotlin/Native doesn't expose a stable unhandled exception hook in this toolchain.
    // Use a foreground/session heuristic to surface unexpected terminations on next launch.
    val settings = createSettings()
    if (shouldReportUnexpectedTermination(settings)) {
        onCrash(
            CrashInfo.fromDetails(
                name = "UnexpectedTermination",
                message = "App terminated while in foreground (iOS crash info unavailable).",
                stackTrace = null,
            )
        )
    }
    settings.putBoolean(SettingsUtils.KEY_LAST_SESSION_FOREGROUND, true)
    observeAppLifecycle(settings)
}

actual fun exitApp() {
    exit(0)
}

private fun shouldReportUnexpectedTermination(settings: Settings): Boolean {
    return settings.getStringOrNull(SettingsUtils.KEY_LAST_CRASH) == null && settings.getBoolean(SettingsUtils.KEY_LAST_SESSION_FOREGROUND, false)
}

private fun observeAppLifecycle(settings: Settings) {
    val center = NSNotificationCenter.defaultCenter
    center.addObserverForName(UIApplicationDidEnterBackgroundNotification, null, null) { _ ->
        settings.putBoolean(SettingsUtils.KEY_LAST_SESSION_FOREGROUND, false)
    }
    center.addObserverForName(UIApplicationWillEnterForegroundNotification, null, null) { _ ->
        settings.putBoolean(SettingsUtils.KEY_LAST_SESSION_FOREGROUND, true)
    }
    center.addObserverForName(UIApplicationWillTerminateNotification, null, null) { _ ->
        settings.putBoolean(SettingsUtils.KEY_LAST_SESSION_FOREGROUND, false)
    }
}
