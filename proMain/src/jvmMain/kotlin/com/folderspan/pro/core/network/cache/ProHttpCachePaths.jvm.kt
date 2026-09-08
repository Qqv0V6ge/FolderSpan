package com.folderspan.pro.core.network.cache

import com.folderspan.cleanup.resolveDesktopApplicationDataDirectory
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermission

internal actual fun resolveProHttpCacheRootDirectory(): String {
    val directory = resolveDesktopApplicationDataDirectory().resolve("pro_http_cache")
    Files.createDirectories(directory)
    restrictOwnerOnlyDirectory(directory)
    return directory.toString()
}

internal actual fun restrictProHttpCachePermissions(directory: String, filePath: String) {
    restrictOwnerOnlyDirectory(Path.of(directory))
    runCatching {
        Files.setPosixFilePermissions(
            Path.of(filePath),
            setOf(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE),
        )
    }
}

private fun restrictOwnerOnlyDirectory(directory: Path) {
    runCatching {
        Files.setPosixFilePermissions(
            directory,
            setOf(
                PosixFilePermission.OWNER_READ,
                PosixFilePermission.OWNER_WRITE,
                PosixFilePermission.OWNER_EXECUTE,
            ),
        )
    }
}
