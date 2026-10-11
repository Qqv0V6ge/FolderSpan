package com.folderspan.extensions

/**
 * 检查异常是否为连接异常类型
 * 支持跨平台的连接异常检测
 */
fun Throwable.isConnectionException(): Boolean {
    val names = classNames().map { item ->  item.lowercase() }
    if (names.any { name ->
            name.endsWith("connectexception") ||
                name.endsWith("socketexception") ||
                name.endsWith("unknownhostexception")
        }
    ) {
        return true
    }

    val message = buildString {
        append(this@isConnectionException.message ?: "")
        append(' ')
        append(this@isConnectionException.toString())
    }.lowercase()

    return message.contains("failed to connect") ||
        message.contains("connection refused") ||
        message.contains("connection reset") ||
        message.contains("connection timed out")
}

expect fun Throwable.classNames(): List<String>
