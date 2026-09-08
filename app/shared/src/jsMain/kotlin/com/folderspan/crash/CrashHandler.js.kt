package com.folderspan.crash

import com.folderspan.ui.state.main.CrashInfo
import kotlinx.browser.window

actual fun installPlatformCrashHandler(onCrash: (CrashInfo) -> Unit) {
    val win = window.asDynamic()
    val existingOnError = win.onerror
    win.onerror = { message: dynamic, source: dynamic, line: dynamic, column: dynamic, error: dynamic ->
        val name = error?.name as? String
        val stack = error?.stack as? String
        val msg = message?.toString() ?: error?.message?.toString()
        onCrash(CrashInfo.fromDetails(name, msg, stack))
        existingOnError?.invoke(message, source, line, column, error)
        scheduleReload()
        false
    }
    val existingOnUnhandled = win.onunhandledrejection
    win.onunhandledrejection = { event: dynamic ->
        val reason = event?.reason
        val name = reason?.name as? String
        val stack = reason?.stack as? String
        val msg = reason?.message?.toString() ?: reason?.toString()
        onCrash(CrashInfo.fromDetails(name, msg, stack))
        existingOnUnhandled?.invoke(event)
        scheduleReload()
        null
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
