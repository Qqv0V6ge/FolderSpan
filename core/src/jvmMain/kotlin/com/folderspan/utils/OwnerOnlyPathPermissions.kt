package com.folderspan.utils

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermission

fun restrictOwnerOnlyPath(path: Path, directory: Boolean) {
    try {
        val permissions = if (directory) {
            setOf(
                PosixFilePermission.OWNER_READ,
                PosixFilePermission.OWNER_WRITE,
                PosixFilePermission.OWNER_EXECUTE,
            )
        } else {
            setOf(
                PosixFilePermission.OWNER_READ,
                PosixFilePermission.OWNER_WRITE,
            )
        }
        Files.setPosixFilePermissions(path, permissions)
    } catch (_: UnsupportedOperationException) {
        val file = path.toFile()
        file.setReadable(false, false)
        file.setWritable(false, false)
        file.setExecutable(false, false)
        file.setReadable(true, true)
        file.setWritable(true, true)
        if (directory) {
            file.setExecutable(true, true)
        }
    }
}
