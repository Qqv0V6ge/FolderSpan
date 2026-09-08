package com.folderspan.utils

import io.github.aakira.napier.LogLevel
import kotlinx.coroutines.sync.Mutex
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Clock

const val LOG_CAPTURE_MAX_BYTES: Int = 2 * 1024 * 1024
const val LOG_CAPTURE_FILE_NAME: String = "folderspan-capture.log"

data class ErrorLogFeedbackPayload(
    val summary: String,
    val logBytes: ByteArray,
) {
    override fun equals(other: Any?): Boolean =
        other is ErrorLogFeedbackPayload &&
            summary == other.summary &&
            logBytes.contentEquals(other.logBytes)

    override fun hashCode(): Int = 31 * summary.hashCode() + logBytes.contentHashCode()
}

object LogCapture {
    private val mutex = Mutex()
    private val chunks = ArrayDeque<String>()
    private var byteCount: Int = 0
    private var enabled: Boolean = false
    private var suppressing: Boolean = false
    private var persistedBytes: Int = 0
    private var errorListener: ((String) -> Unit)? = null

    fun isEnabled(): Boolean = enabled

    fun setErrorListener(listener: ((String) -> Unit)?) {
        errorListener = listener
    }

    fun setEnabled(value: Boolean) {
        if (!mutex.tryLock()) return
        try {
            if (enabled == value) return
            enabled = value
            if (value) {
                loadLocked()
            } else {
                clearLocked()
            }
        } finally {
            mutex.unlock()
        }
    }

    fun append(
        priority: LogLevel,
        tag: String?,
        throwable: Throwable?,
        message: String?,
    ) {
        if (!enabled || suppressing) return
        if (!mutex.tryLock()) return
        val line = formatLine(priority, tag, throwable, message)
        val shouldNotify = priority == LogLevel.ERROR
        val notifySummary = message?.trim().orEmpty().ifBlank { line.trim() }
        try {
            appendLineLocked(line)
            maybePersistLocked()
        } finally {
            mutex.unlock()
        }
        if (shouldNotify) {
            notifyError(notifySummary)
        }
    }

    fun snapshot(): ByteArray {
        if (!mutex.tryLock()) {
            return LogCaptureStore.load() ?: ByteArray(0)
        }
        try {
            val bytes = joinedLocked().encodeToByteArray()
            persistLocked(bytes)
            return bytes
        } finally {
            mutex.unlock()
        }
    }

    fun clear() {
        if (!mutex.tryLock()) return
        try {
            clearLocked()
        } finally {
            mutex.unlock()
        }
    }

    private fun notifyError(summary: String) {
        val listener = errorListener ?: return
        suppressing = true
        try {
            listener(summary)
        } finally {
            suppressing = false
        }
    }

    private fun appendLineLocked(line: String) {
        chunks.addLast(line)
        byteCount += line.utf8Size()
        while (byteCount > LOG_CAPTURE_MAX_BYTES && chunks.isNotEmpty()) {
            val removed = chunks.removeFirst()
            byteCount -= removed.utf8Size()
        }
        if (byteCount < 0) byteCount = 0
    }

    private fun joinedLocked(): String = chunks.joinToString(separator = "")

    private fun loadLocked() {
        val existing = LogCaptureStore.load()?.decodeToString().orEmpty()
        chunks.clear()
        byteCount = 0
        if (existing.isNotEmpty()) {
            appendLineLocked(existing)
        }
        persistedBytes = byteCount
    }

    private fun clearLocked() {
        chunks.clear()
        byteCount = 0
        persistedBytes = 0
        LogCaptureStore.clear()
    }

    private fun maybePersistLocked() {
        if (byteCount - persistedBytes < 64 * 1024) return
        persistLocked(joinedLocked().encodeToByteArray())
    }

    private fun persistLocked(bytes: ByteArray) {
        LogCaptureStore.persist(bytes)
        persistedBytes = bytes.size
    }
}

internal expect object LogCaptureStore {
    fun persist(bytes: ByteArray)
    fun load(): ByteArray?
    fun clear()
}

internal fun logCaptureFilePath(): String {
    val separator = PathUtils.getPathSeparator()
    val cache = PathUtils.getCachePath().trimEnd('/', '\\')
    return "$cache$separator$LOG_CAPTURE_FILE_NAME"
}

private fun formatLine(
    priority: LogLevel,
    tag: String?,
    throwable: Throwable?,
    message: String?,
): String = buildString {
    append('[').append(captureTimestamp()).append("] [").append(priority.label()).append(']')
    val location = tag?.takeIf { item -> item.isNotBlank() }
    if (location != null) {
        append(" [").append(location).append(']')
    }
    append(' ')
    append(message.orEmpty())
    if (throwable != null) {
        append('\n')
        append(throwable.stackTraceToString())
    }
    append('\n')
}

private fun LogLevel.label(): String = when (this) {
    LogLevel.VERBOSE -> "VERBOSE"
    LogLevel.DEBUG -> "DEBUG"
    LogLevel.INFO -> "INFO"
    LogLevel.WARNING -> "WARNING"
    LogLevel.ERROR -> "ERROR"
    LogLevel.ASSERT -> "ASSERT"
}

private fun captureTimestamp(): String {
    val ldt = Clock.System.now().toLocalDateTime(TimeZone.currentSystemDefault())
    val y = ldt.year.toString().padStart(4, '0')
    val m = (ldt.month.ordinal + 1).toString().padStart(2, '0')
    val d = ldt.day.toString().padStart(2, '0')
    val hh = ldt.hour.toString().padStart(2, '0')
    val mm = ldt.minute.toString().padStart(2, '0')
    val ss = ldt.second.toString().padStart(2, '0')
    return "$y-$m-$d $hh:$mm:$ss"
}

private fun String.utf8Size(): Int = encodeToByteArray().size
