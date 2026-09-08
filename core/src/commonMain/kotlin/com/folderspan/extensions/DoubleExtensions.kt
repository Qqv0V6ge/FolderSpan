package com.folderspan.extensions

import kotlin.math.abs
import kotlin.math.round

// 基于类型的通用文件/传输显示扩展

// Double → 速度（Bytes/s）
fun Double.formatSpeed(): String = this.formatBytes() + "/s"

// Double → 一位小数（无小数补 .0）
fun Double.toOneDecimal(): String {
    val rounded = round(this * 10.0) / 10.0
    val longPart = rounded.toLong()
    val frac = abs(rounded - longPart)
    return if (frac == 0.0) "$longPart.0" else rounded.toString()
}

// Double/Long → 可读字节大小
fun Double.formatBytes(): String {
    val units = arrayOf("B", "KB", "MB", "GB", "TB")
    var value = this
    var idx = 0
    while (value >= 1024 && idx < units.lastIndex) {
        value /= 1024
        idx++
    }
    return if (idx == 0) "${value.toLong()} ${units[idx]}" else "${value.toOneDecimal()} ${units[idx]}"
}