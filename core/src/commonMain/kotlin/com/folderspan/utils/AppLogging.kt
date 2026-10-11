package com.folderspan.utils

import io.github.aakira.napier.DebugAntilog
import io.github.aakira.napier.Napier

fun installAppLogging() {
    Napier.takeLogarithm()
    Napier.base(DebugAntilog())
    Napier.base(LogCaptureAntilog())
}
