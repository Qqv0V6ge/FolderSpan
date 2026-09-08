package com.folderspan.extensions

actual fun Throwable.classNames(): List<String> = buildList {
    this@classNames::class.simpleName?.let { item ->  add(item) }
    this@classNames::class.qualifiedName?.let { item ->  add(item) }
    add(this@classNames.javaClass.name)
}
