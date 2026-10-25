package com.folderspan.ui.state.main

import com.folderspan.utils.FileAccessPermission
import com.folderspan.utils.PrivateFileStorage

internal actual object LocalTaskArtifacts {
    actual val supported: Boolean = false
    actual fun create(path: String, directory: Boolean, cacheArtifact: Boolean): LocalTaskArtifactIdentity = error("ownership_unverified")
    actual fun inspect(path: String, cacheArtifact: Boolean): LocalTaskArtifactIdentity? = error("ownership_unverified")
    actual fun describe(path: String, cacheArtifact: Boolean): LocalTaskArtifactDescription? = error("ownership_unverified")
    actual fun deliver(path: String, finalPath: String, identity: String): Unit = error("ownership_unverified")
    actual fun delete(path: String, identity: String, fingerprint: String, directory: Boolean, cacheArtifact: Boolean): Unit = error("ownership_unverified")
}

internal actual fun replaceTaskCleanupFile(path: String, content: String) {
    val temporary = "$path.tmp"
    if (PrivateFileStorage.exists(FileAccessPermission.Allowed, temporary)) {
        PrivateFileStorage.deleteFile(FileAccessPermission.Allowed, temporary).getOrThrow()
    }
    PrivateFileStorage.createFile(FileAccessPermission.Allowed, temporary).getOrThrow()
    PrivateFileStorage.appendToFile(FileAccessPermission.Allowed, temporary, content)
    if (PrivateFileStorage.exists(FileAccessPermission.Allowed, path)) {
        PrivateFileStorage.deleteFile(FileAccessPermission.Allowed, path).getOrThrow()
    }
    check(PrivateFileStorage.rename(FileAccessPermission.Allowed, path.substringBeforeLast(PrivateFileStorage.getPathSeparator()),
        temporary.substringAfterLast(PrivateFileStorage.getPathSeparator()), path.substringAfterLast(PrivateFileStorage.getPathSeparator())).getOrThrow())
}

internal actual fun taskCleanupPathExists(path: String): Boolean = PrivateFileStorage.exists(FileAccessPermission.Allowed, path)
