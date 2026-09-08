package com.folderspan

import androidx.compose.ui.window.ComposeUIViewController
import com.folderspan.cleanup.ApplicationDataCleanup
import com.folderspan.di.initKoin
import com.folderspan.service.http.server.startBackgroundShareServices
import com.folderspan.ui.state.file.FileShareState
import com.folderspan.utils.LogKit
import com.folderspan.utils.installAppLogging
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.koin.mp.KoinPlatformTools
import strings.AppStrings

private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
private var backgroundShareServicesStarted = false
private var loggingInitialized = false

fun MainViewController() = run {
    initLoggingOnce()
    ApplicationDataCleanup.completePendingCleanup()
        .onFailure { error -> LogKit.e(AppStrings.ui_clear_folderspan_local_data_failed, error) }
    initKoin()
    ComposeUIViewController {
        App(onFirstFrameRendered = ::startBackgroundShareServicesOnce)
    }
}

private fun initLoggingOnce() {
    if (loggingInitialized) return
    installAppLogging()
    loggingInitialized = true
}

private fun startBackgroundShareServicesOnce() {
    if (backgroundShareServicesStarted) return
    backgroundShareServicesStarted = true
    val fileShareState = KoinPlatformTools.defaultContext().get().get<FileShareState>()
    serviceScope.launch {
        startBackgroundShareServices(fileShareState)
    }
}
