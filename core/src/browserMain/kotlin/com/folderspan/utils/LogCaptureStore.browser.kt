package com.folderspan.utils

internal actual object LogCaptureStore {
    private val path get() = PrivateFileStorage.getCachePath() + "/captured-log.txt"
    actual fun persist(bytes: ByteArray) = PrivateFileStorage.write(path, bytes)
    actual fun load(): ByteArray? = if (PrivateFileStorage.exists(FileAccessPermission.Allowed, path))
        PrivateFileStorage.readFile(FileAccessPermission.Allowed, path).getOrThrow() else null
    actual fun clear() { PrivateFileStorage.deleteFile(FileAccessPermission.Allowed, path).getOrThrow() }
}
