package com.folderspan.ui.state.main

import com.folderspan.PlatformType
import com.folderspan.data.main.device.DeviceType
import com.folderspan.notification.ErrorLogReporter
import com.folderspan.utils.LogCapture
import com.folderspan.utils.LogKit
import com.folderspan.utils.SettingsUtils
import com.russhwolf.settings.Settings
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
data class CrashInfo(
    val name: String,
    val message: String? = null,
    val stackTrace: String? = null,
) {
    companion object {
        fun fromThrowable(throwable: Throwable): CrashInfo {
            val raw = throwable.toString()
            val name = raw.substringBefore(':').ifBlank { "UnknownError" }
            val stackTrace = runCatching { throwable.stackTraceToString() }
                .getOrNull()
                ?.takeIf { item ->  item.isNotBlank() }
            return CrashInfo(
                name = name,
                message = throwable.message,
                stackTrace = stackTrace,
            )
        }

        fun fromDetails(name: String?, message: String?, stackTrace: String?): CrashInfo {
            val resolvedName = name
                ?.takeIf { item ->  item.isNotBlank() }
                ?: message?.substringBefore(':')?.takeIf { item ->  item.isNotBlank() }
                ?: "UnknownError"
            return CrashInfo(
                name = resolvedName,
                message = message,
                stackTrace = stackTrace?.takeIf { item ->  item.isNotBlank() },
            )
        }
    }
}

data class CrashActionAvailability(
    val canRestart: Boolean,
    val canExit: Boolean,
    val canCopy: Boolean,
)

data class CrashScreenState(
    val info: CrashInfo,
    val reportText: String,
    val canRestart: Boolean,
    val canExit: Boolean,
    val canCopy: Boolean,
)

fun CrashInfo.toReportText(): String = buildString {
    append("Exception: ").append(name)
    if (!message.isNullOrBlank()) {
        append("\nMessage: ").append(message)
    }
    if (!stackTrace.isNullOrBlank()) {
        append("\n\nStack trace:\n").append(stackTrace)
    }
}

fun CrashInfo.toScreenState(
    availability: CrashActionAvailability = defaultCrashActionAvailability(),
): CrashScreenState = CrashScreenState(
    info = this,
    reportText = toReportText(),
    canRestart = availability.canRestart,
    canExit = availability.canExit,
    canCopy = availability.canCopy,
)

fun defaultCrashActionAvailability(): CrashActionAvailability {
    val canExit = when (PlatformType) {
        DeviceType.IOS,
        DeviceType.JS -> false
        else -> true
    }
    return CrashActionAvailability(
        canRestart = true,
        canExit = canExit,
        canCopy = true,
    )
}

class CrashState(private val settings: Settings) {
    private val json = Json { ignoreUnknownKeys = true }
    private val _crashInfo = MutableStateFlow(loadPersistedCrash())
    val crashInfo = _crashInfo.asStateFlow()
    private val _restartToken = MutableStateFlow(0)
    val restartToken = _restartToken.asStateFlow()

    fun recordCrash(info: CrashInfo) {
        _crashInfo.value = info
        settings.putString(SettingsUtils.KEY_LAST_CRASH, json.encodeToString(info))
        if (LogCapture.isEnabled()) {
            val summary = info.toReportText()
            LogKit.e(summary)
            ErrorLogReporter.report(summary)
        }
    }

    fun clearCrash() {
        _crashInfo.value = null
        settings.remove(SettingsUtils.KEY_LAST_CRASH)
    }

    fun requestRestart() {
        clearCrash()
        settings.putBoolean(SettingsUtils.KEY_LAST_SESSION_FOREGROUND, false)
        _restartToken.value += 1
    }

    private fun loadPersistedCrash(): CrashInfo? {
        val raw = settings.getStringOrNull(SettingsUtils.KEY_LAST_CRASH) ?: return null
        if (raw.isBlank()) return null
        return runCatching { json.decodeFromString<CrashInfo>(raw) }.getOrNull()
    }
}
