package com.folderspan

import com.folderspan.crash.installPlatformCrashHandler
import com.folderspan.notification.ErrorLogReporter
import com.folderspan.service.webui.WebUiAttachment
import com.folderspan.ui.state.main.CrashState
import com.folderspan.ui.state.main.NotificationState
import com.folderspan.utils.LogCapture
import com.folderspan.utils.SettingsUtils
import com.russhwolf.settings.Settings
import org.koin.core.Koin

/** Business effects run on the client owning the application state. */
suspend fun runAppBusinessServices(koin: Koin) {
    val settings = koin.get<Settings>()
    initializeProAuthSession(settings)
    ErrorLogReporter.attach(koin.get<NotificationState>())
    LogCapture.setEnabled(SettingsUtils.getBoolean(SettingsUtils.KEY_AUTO_CAPTURE_LOGS, false))
    val crashState = koin.get<CrashState>()
    installPlatformCrashHandler(crashState::recordCrash)
    runProRuntime(loadProRuntimeDependencies(koin), settings)
}
