package com.folderspan.utils

import io.github.aakira.napier.Napier
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Clock

/**
 * Simple Napier wrapper with unified log template:
 * [YYYY-MM-DD HH:MM:SS] [LEVEL] [file:line] message
 */
object LogKit {
    fun v(message: String) {
        val callerLocation = callerLocation()
        Napier.v { "[${ts()}] [VERBOSE] [$callerLocation] $message" }
    }

    fun i(message: String) {
        val callerLocation = callerLocation()
        Napier.i { "[${ts()}] [INFO] [$callerLocation] $message" }
    }

    fun d(message: String) {
        val callerLocation = callerLocation()
        Napier.d { "[${ts()}] [DEBUG] [$callerLocation] $message" }
    }

    fun w(message: String, throwable: Throwable? = null) {
        val callerLocation = callerLocation()
        if (throwable != null) {
            Napier.w(throwable) { "[${ts()}] [WARNING] [$callerLocation] $message" }
        } else {
            Napier.w { "[${ts()}] [WARNING] [$callerLocation] $message" }
        }
    }

    fun e(message: String, throwable: Throwable? = null) {
        val callerLocation = callerLocation()
        if (throwable != null) {
            Napier.e(throwable) { "[${ts()}] [ERROR] [$callerLocation] $message" }
        } else {
            Napier.e { "[${ts()}] [ERROR] [$callerLocation] $message" }
        }
    }

    private fun ts(): String {
        val ldt = Clock.System.now().toLocalDateTime(TimeZone.currentSystemDefault())
        val y = ldt.year.toString().padStart(4, '0')
        val m = (ldt.month.ordinal + 1).toString().padStart(2, '0')
        val d = ldt.day.toString().padStart(2, '0')
        val hh = ldt.hour.toString().padStart(2, '0')
        val mm = ldt.minute.toString().padStart(2, '0')
        val ss = ldt.second.toString().padStart(2, '0')
        return "$y-$m-$d $hh:$mm:$ss"
    }
}

expect fun callerLocation(stackDepth: Int = 3): String
