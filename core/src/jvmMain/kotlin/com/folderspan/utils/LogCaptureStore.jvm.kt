package com.folderspan.utils

internal actual object LogCaptureStore {
    actual fun persist(bytes: ByteArray) {
        runCatching {
            val path = logCaptureFilePath()
            PathUtils.createDirectoryIfNotExists(
                FileAccessPermission.Allowed,
                PathUtils.getCachePath(),
            )
            FileUtils.deleteFile(FileAccessPermission.Allowed, path)
            FileUtils.createFile(FileAccessPermission.Allowed, path)
            FileUtils.appendToFile(
                FileAccessPermission.Allowed,
                path,
                bytes.decodeToString(),
            )
        }
    }

    actual fun load(): ByteArray? {
        return runCatching {
            FileUtils.readFile(FileAccessPermission.Allowed, logCaptureFilePath()).getOrNull()
        }.getOrNull()
    }

    actual fun clear() {
        runCatching { FileUtils.deleteFile(FileAccessPermission.Allowed, logCaptureFilePath()) }
    }
}
