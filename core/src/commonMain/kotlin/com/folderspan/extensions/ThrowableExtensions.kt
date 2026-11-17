package com.folderspan.extensions

import com.folderspan.exception.AuthenticationException
import com.folderspan.exception.ConnectionClosedException

/**
 * 检查异常是否为连接异常类型
 * 支持跨平台的连接异常检测
 */
fun Throwable.isConnectionException(): Boolean {
    val seen = mutableSetOf<Throwable>()
    var error: Throwable? = this
    while (error != null && seen.add(error)) {
        if (error is ConnectionClosedException || error is AuthenticationException) return true
        val names = error.classNames().map { it.lowercase() }
        if (names.any { it.endsWith("connectexception") || it.endsWith("socketexception") || it.endsWith("unknownhostexception") }) return true
        val message = "${error.message.orEmpty()} $error".lowercase()
        if (message.contains("failed to connect") || message.contains("connection refused") ||
            message.contains("connection reset") || message.contains("connection timed out")) return true
        error = error.cause
    }
    return false
}

expect fun Throwable.classNames(): List<String>
