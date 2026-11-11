package com.folderspan.service.network

import okio.Path

/** File operations that must preserve private ownership and atomic publication on every host. */
internal expect object StorageContentFileProtection {
    fun restrictOwnerOnly(path: Path, directory: Boolean)
    fun atomicMove(source: Path, target: Path)
    fun fileIdentity(path: Path): String?
    fun createPrivateTempFile(parent: Path, prefix: String): Path
    fun createPrivateDirectory(path: Path)
    fun normalizedAbsolutePath(path: String): Path
}
