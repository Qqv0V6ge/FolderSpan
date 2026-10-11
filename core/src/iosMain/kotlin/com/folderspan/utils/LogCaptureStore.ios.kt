package com.folderspan.utils

internal actual object LogCaptureStore {
    actual fun persist(bytes: ByteArray) {
        runCatching {
            val path = logCaptureFilePath()
            PrivateFileStorage.createDirectoryIfNotExists(
                FileAccessPermission.Allowed,
                PrivateFileStorage.getCachePath(),
            )
            PrivateFileStorage.deleteFile(FileAccessPermission.Allowed, path)
            PrivateFileStorage.createFile(FileAccessPermission.Allowed, path)
            PrivateFileStorage.appendToFile(
                FileAccessPermission.Allowed,
                path,
                bytes.decodeToString(),
            )
        }
    }

    actual fun load(): ByteArray? {
        return runCatching {
            PrivateFileStorage.readFile(FileAccessPermission.Allowed, logCaptureFilePath()).getOrNull()
        }.getOrNull()
    }

    actual fun clear() {
        runCatching { PrivateFileStorage.deleteFile(FileAccessPermission.Allowed, logCaptureFilePath()) }
    }
}
