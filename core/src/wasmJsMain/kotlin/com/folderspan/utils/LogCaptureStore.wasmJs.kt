package com.folderspan.utils

internal actual object LogCaptureStore {
    actual fun persist(bytes: ByteArray) = Unit

    actual fun load(): ByteArray? = null

    actual fun clear() = Unit
}
