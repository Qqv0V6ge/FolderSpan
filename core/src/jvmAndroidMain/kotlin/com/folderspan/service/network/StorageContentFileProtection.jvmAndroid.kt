package com.folderspan.service.network

import com.folderspan.settings.restrictOwnerOnly
import okio.Path
import okio.Path.Companion.toPath
import java.nio.file.FileSystems
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Paths
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.BasicFileAttributes
import java.nio.file.attribute.PosixFilePermissions

internal actual object StorageContentFileProtection {
    actual fun restrictOwnerOnly(path: Path, directory: Boolean) {
        val value = Paths.get(path.toString())
        check(if (directory) Files.isDirectory(value, LinkOption.NOFOLLOW_LINKS)
            else Files.isRegularFile(value, LinkOption.NOFOLLOW_LINKS)) { "Content private path type changed" }
        restrictOwnerOnly(value, directory)
    }

    actual fun atomicMove(source: Path, target: Path) {
        check(source.parent == target.parent) { "Authenticated publication requires a sibling temporary file" }
        check(fileIdentity(source) != null) { "Authenticated publication source changed" }
        Files.move(Paths.get(source.toString()), Paths.get(target.toString()),
            StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
    }

    actual fun fileIdentity(path: Path): String? {
        val value = Paths.get(path.toString())
        if (!Files.isRegularFile(value, LinkOption.NOFOLLOW_LINKS)) return null
        return checkNotNull(Files.readAttributes(value, BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS).fileKey()) {
            "Content cache file identity is unavailable"
        }.toString()
    }

    actual fun createPrivateTempFile(parent: Path, prefix: String): Path {
        val directory = Paths.get(parent.toString())
        val file = if (FileSystems.getDefault().supportedFileAttributeViews().contains("posix")) {
            Files.createTempFile(directory, prefix, ".part", PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")))
        } else Files.createTempFile(directory, prefix, ".part")
        return try {
            restrictOwnerOnly(file, directory = false)
            file.toString().toPath()
        } catch (error: Throwable) {
            Files.deleteIfExists(file)
            throw error
        }
    }

    actual fun createPrivateDirectory(path: Path) {
        val directory = Paths.get(path.toString())
        if (FileSystems.getDefault().supportedFileAttributeViews().contains("posix")) {
            Files.createDirectories(directory, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")))
        } else Files.createDirectories(directory)
        check(Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS)) { "Content cache ownership changed" }
        restrictOwnerOnly(directory, directory = true)
    }

    actual fun normalizedAbsolutePath(path: String): Path = Paths.get(path).toAbsolutePath().normalize().toString().toPath()
}
