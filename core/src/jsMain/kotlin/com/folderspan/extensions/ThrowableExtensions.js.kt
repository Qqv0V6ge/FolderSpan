package com.folderspan.extensions

actual fun Throwable.classNames(): List<String> = listOf(toString())
