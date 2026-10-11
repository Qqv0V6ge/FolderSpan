package com.folderspan.crash

import com.folderspan.ui.state.main.CrashInfo
import kotlin.system.exitProcess

actual fun installPlatformCrashHandler(onCrash: (CrashInfo) -> Unit) {
    Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
        runCatching { onCrash(CrashInfo.fromThrowable(throwable)) }
    }
}

actual fun exitApp() {
    exitProcess(0)
}
