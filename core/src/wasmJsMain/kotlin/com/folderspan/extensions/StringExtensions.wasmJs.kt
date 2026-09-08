package com.folderspan.extensions

actual fun String.parsePath(): List<String> =
    split('/', '\\')
        .map { item ->  item.trim() }
        .filter { item ->  item.isNotEmpty() }
