package com.folderspan.crash

import com.folderspan.ui.state.main.CrashInfo
import kotlinx.browser.window

actual fun installPlatformCrashHandler(onCrash: (CrashInfo) -> Unit) {
    window.addEventListener("error") {
        onCrash(CrashInfo.fromDetails("Error", null, null))
        scheduleReload()
    }
    window.addEventListener("unhandledrejection") {
        onCrash(CrashInfo.fromDetails("UnhandledRejection", null, null))
        scheduleReload()
    }
}

actual fun exitApp() {
    window.location.reload()
}

private fun scheduleReload() {
    val guardKey = "folderspan.crash.reloaded"
    val storage = window.sessionStorage
    if (storage.getItem(guardKey) != null) return
    storage.setItem(guardKey, "1")
    window.location.reload()
}
