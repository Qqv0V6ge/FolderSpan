package com.folderspan.ui.theme

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.runInterruptible
import org.jetbrains.skiko.SystemTheme
import org.jetbrains.skiko.currentSystemTheme

internal object DesktopSystemTheme {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val linuxReader = LinuxSystemThemeReader()

    // App windows and the tray share one poller. Cancelling the last composition's
    // subscription also interrupts any pending desktop settings command.
    val isDark = flow<Boolean?> {
        while (currentCoroutineContext().isActive) {
            val detected = runInterruptible { readSystemTheme() }
            if (detected != null) emit(detected)
            delay(1_000)
        }
    }.stateIn(scope, SharingStarted.WhileSubscribed(stopTimeoutMillis = 0), null)

    private fun readSystemTheme(): Boolean? {
        // Skiko's Linux implementation requires an unversioned libdbus development
        // symlink and uses DBus's long default timeout. Use bounded portal calls here.
        if (System.getProperty("os.name").orEmpty().startsWith("Linux", ignoreCase = true)) {
            return linuxReader.read()
        }
        return runCatching {
            when (currentSystemTheme) {
                SystemTheme.DARK -> true
                SystemTheme.LIGHT -> false
                SystemTheme.UNKNOWN -> null
            }
        }.getOrNull()
    }
}
