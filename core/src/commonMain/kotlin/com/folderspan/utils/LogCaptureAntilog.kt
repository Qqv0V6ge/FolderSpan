package com.folderspan.utils

import io.github.aakira.napier.Antilog
import io.github.aakira.napier.LogLevel

class LogCaptureAntilog : Antilog() {
    override fun performLog(
        priority: LogLevel,
        tag: String?,
        throwable: Throwable?,
        message: String?,
    ) {
        LogCapture.append(priority, tag, throwable, message)
    }
}
