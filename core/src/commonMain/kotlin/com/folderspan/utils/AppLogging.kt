package com.folderspan.utils

import com.folderspan.PlatformType
import com.folderspan.data.main.device.DeviceType
import io.github.aakira.napier.Antilog
import io.github.aakira.napier.DebugAntilog
import io.github.aakira.napier.LogLevel
import io.github.aakira.napier.Napier

fun installAppLogging() {
    Napier.takeLogarithm()
    val debugAntilog = DebugAntilog()
    Napier.base(if (PlatformType == DeviceType.Android) DefaultTagAntilog(debugAntilog) else debugAntilog)
    Napier.base(LogCaptureAntilog())
}

internal class DefaultTagAntilog(private val delegate: Antilog) : Antilog() {
    override fun isEnable(priority: LogLevel, tag: String?): Boolean = delegate.isEnable(priority, tag ?: "app")

    override fun performLog(priority: LogLevel, tag: String?, throwable: Throwable?, message: String?) {
        // Napier 2.7.1 的 Android 自动标签在堆栈恰好 9 帧时越界；显式标签绕过该路径。
        delegate.log(priority, tag ?: "app", throwable, message)
    }
}
