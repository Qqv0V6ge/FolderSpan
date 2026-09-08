@file:OptIn(ExperimentalWasmJsInterop::class)

package com.folderspan.extensions

external interface JsDate {
    fun getFullYear(): Int
    fun getMonth(): Int
    fun getDate(): Int
    fun getHours(): Int
    fun getMinutes(): Int
    fun getSeconds(): Int
}

@JsFun("(ms) => new Date(ms)")
private external fun dateFromMs(ms: Double): JsDate

actual fun Long.timestampToSyncDate(): String {
    val date = dateFromMs(this.toDouble())
    val year = date.getFullYear()
    val month = date.getMonth() + 1
    val day = date.getDate()
    val hour = date.getHours()
    val minute = date.getMinutes()
    val second = date.getSeconds()
    return "${pad2(year)}-${pad2(month)}-${pad2(day)} ${pad2(hour)}:${pad2(minute)}:${pad2(second)}"
}

private fun pad2(value: Int): String = value.toString().padStart(2, '0')
