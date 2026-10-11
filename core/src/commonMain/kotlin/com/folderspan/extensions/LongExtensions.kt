package com.folderspan.extensions

import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlin.math.abs
import kotlin.time.Clock
import kotlin.time.Instant

/**
 * 将毫秒时间戳格式化为年月日，如 "2024-3-12"。
 */
fun Long.timestampToYearMonthDay(): String {
    val instant = Instant.fromEpochMilliseconds(this)
    val localDateTime = instant.toLocalDateTime(TimeZone.currentSystemDefault())
    return "${localDateTime.year}-${localDateTime.month.ordinal + 1}-${localDateTime.day}"
}

/**
 * 将毫秒时间戳格式化为 "yyyy-MM-dd HH:mm"。
 */
fun Long.timestampToYMDHM(): String {
    val instant = Instant.fromEpochMilliseconds(this)
    val localDateTime = instant.toLocalDateTime(TimeZone.currentSystemDefault())
    return "${localDateTime.year.toString().padStart(2, '0')}-${
        (localDateTime.month.ordinal + 1).toString().padStart(2, '0')
    }-${localDateTime.day.toString().padStart(2, '0')} ${
        localDateTime.hour.toString().padStart(2, '0')
    }:${localDateTime.minute.toString().padStart(2, '0')}"
}

/**
 * 平台适配的同步时间格式。
 */
expect fun Long.timestampToSyncDate(): String

/**
 * 通用的同步时间格式，输出 "yyyy-MM-dd HH:mm:ss"。
 */
internal fun Long.timestampToSyncDateCommon(): String {
    val instant = Instant.fromEpochMilliseconds(this)
    val localDateTime = instant.toLocalDateTime(TimeZone.currentSystemDefault())
    return "${localDateTime.year.toString().padStart(2, '0')}-${
        (localDateTime.month.ordinal + 1).toString().padStart(2, '0')
    }-${localDateTime.day.toString().padStart(2, '0')} ${
        localDateTime.hour.toString().padStart(2, '0')
    }:${localDateTime.minute.toString().padStart(2, '0')}:${localDateTime.second.toString().padStart(2, '0')}"
}

/**
 * 将毫秒时间戳转换为本地时区的 LocalDateTime。
 */
fun Long.timestampToLocalDateTime(): LocalDateTime =
    Instant.fromEpochMilliseconds(this).toLocalDateTime(TimeZone.currentSystemDefault())

/**
 * 自适应显示时间：
 * - 小于一天：HH:mm:ss
 * - 小于一年：MM-dd HH:mm:ss
 * - 大于等于一年：yyyy-MM-dd HH:mm:ss
 */
fun Long.timestampToAdaptiveDateTime(): String {
    val localDateTime = Instant.fromEpochMilliseconds(this).toLocalDateTime(TimeZone.currentSystemDefault())
    val diff = abs(Clock.System.now().toEpochMilliseconds() - this)

    val month = (localDateTime.month.ordinal + 1).toString().padStart(2, '0')
    val day = localDateTime.day.toString().padStart(2, '0')
    val hour = localDateTime.hour.toString().padStart(2, '0')
    val minute = localDateTime.minute.toString().padStart(2, '0')
    val second = localDateTime.second.toString().padStart(2, '0')
    val timeText = "$hour:$minute:$second"

    val dayMillis = 24 * 60 * 60 * 1000L
    val yearMillis = 365L * dayMillis

    return when {
        diff < dayMillis -> timeText
        diff < yearMillis -> "$month-$day $timeText"
        else -> "${localDateTime.year}-$month-$day $timeText"
    }
}

/**
 * 将字节数格式化为 B/KB/MB/GB。
 */
fun Long.formatFileSize(): String {
    return if (this < 1024) {
        "$this B"
    } else if (this < 1024 * 1024) {
        val kilobytes = this / 1024
        "$kilobytes KB"
    } else if (this < 1024 * 1024 * 1024) {
        val megabytes = this / (1024 * 1024)
        "$megabytes MB"
    } else {
        val gigabytes = this / (1024 * 1024 * 1024)
        "$gigabytes GB"
    }
}

/**
 * 计算已完成字节/总字节的百分比，保留一位小数。
 */
fun Long.formatPercent(totalBytes: Long): String {
    if (totalBytes <= 0L) return "100%"
    val p = (this.toDouble() / totalBytes.toDouble()) * 100.0
    return p.coerceIn(0.0, 100.0).toOneDecimal() + "%"
}

/**
 * 将字节数格式化为可读文本。
 */
fun Long.formatBytes(): String = this.toDouble().formatBytes()

/**
 * 将毫秒时长格式化为 mm:ss 或 h:mm:ss。
 */
fun Long.formatDuration(): String {
    if (this < 0) return "--:--"
    var seconds = (this + 500) / 1000 // 四舍五入
    val h = seconds / 3600
    seconds %= 3600
    val m = seconds / 60
    val s = seconds % 60
    val mm = m.toString().padStart(2, '0')
    val ss = s.toString().padStart(2, '0')
    return if (h > 0) "$h:$mm:$ss" else "$mm:$ss"
}
