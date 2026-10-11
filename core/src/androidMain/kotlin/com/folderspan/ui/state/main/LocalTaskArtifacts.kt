package com.folderspan.ui.state.main

import com.folderspan.utils.FileAccessPermission
import com.folderspan.utils.NativePathUtils
import com.folderspan.utils.nativeSensitivePathDeniedException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Paths
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.BasicFileAttributes
import java.security.MessageDigest

internal actual object LocalTaskArtifacts {
    actual val supported: Boolean = true

    private fun guarded(path: String, cacheArtifact: Boolean = false): java.nio.file.Path {
        if (cacheArtifact) {
            val root = NativePathUtils.getCachePath().trimEnd('/', '\\') + NativePathUtils.getPathSeparator() + "sync-stage"
            check(NativePathUtils.isPathWithinRoot(FileAccessPermission.Allowed, root, path, allowNonExistentLeaf = true))
        } else nativeSensitivePathDeniedException(path)?.let { throw it }
        val target = Paths.get(path).toAbsolutePath().normalize()
        var component = target.root
        for (part in target) {
            component = component.resolve(part)
            check(!Files.isSymbolicLink(component)) { "ownership_changed" }
        }
        return target
    }

    actual fun create(path: String, directory: Boolean, cacheArtifact: Boolean): LocalTaskArtifactIdentity {
        val target = guarded(path, cacheArtifact)
        if (directory) Files.createDirectory(target) else {
            Files.newByteChannel(target, setOf(StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE,
                LinkOption.NOFOLLOW_LINKS)).use { }
        }
        return inspect(path, cacheArtifact) ?: error("ownership_unverified")
    }

    actual fun inspect(path: String, cacheArtifact: Boolean): LocalTaskArtifactIdentity? {
        val target = guarded(path, cacheArtifact)
        val attributes = try {
            Files.readAttributes(target, BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
        } catch (_: java.nio.file.NoSuchFileException) { return null }
        check(attributes.isRegularFile || attributes.isDirectory) { "ownership_changed" }
        val key = attributes.fileKey() ?: error("ownership_unverified")
        val fingerprint = if (attributes.isDirectory) {
            val permissions = runCatching { Files.getPosixFilePermissions(target, LinkOption.NOFOLLOW_LINKS)
                .map { it.name }.sorted().joinToString(",") }.getOrNull()
            val owner = runCatching { Files.getOwner(target, LinkOption.NOFOLLOW_LINKS).name }.getOrNull()
            val readOnly = runCatching { Files.getAttribute(target, "dos:readonly", LinkOption.NOFOLLOW_LINKS) }.getOrNull()
            "directory:$permissions:$owner:$readOnly"
        } else {
            val digest = MessageDigest.getInstance("SHA-256")
            Files.newInputStream(target, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS).use { input ->
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    digest.update(buffer, 0, count)
                }
            }
            val changeTime = runCatching { Files.getAttribute(target, "unix:ctime", LinkOption.NOFOLLOW_LINKS) }.getOrNull()
            attributes.lastModifiedTime().toString() + ":" + attributes.creationTime() + ":" +
                changeTime + ":" + digest.digest().joinToString("") { "%02x".format(it) }
        }
        return LocalTaskArtifactIdentity(key.toString(), fingerprint)
    }

    actual fun deliver(path: String, finalPath: String, identity: String) {
        val attributes = Files.readAttributes(guarded(path), BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
        check(attributes.isRegularFile && attributes.fileKey()?.toString() == identity) { "ownership_changed" }
        // Preserve existing conflict behavior for a pre-existing destination, without ever claiming it.
        Files.move(guarded(path), guarded(finalPath), StandardCopyOption.REPLACE_EXISTING)
    }

    actual fun delete(path: String, identity: String, fingerprint: String, directory: Boolean, cacheArtifact: Boolean) {
        val current = inspect(path, cacheArtifact) ?: return
        check(current.identity == identity && current.fingerprint == fingerprint) { "ownership_changed" }
        // Files.delete is non-recursive and rejects a non-empty directory.
        Files.delete(guarded(path, cacheArtifact))
    }
}

internal actual fun replaceTaskCleanupFile(path: String, content: String) {
    val target = Paths.get(path)
    val temporary = Paths.get("$path.tmp")
    Files.write(temporary, content.toByteArray(), StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING,
        StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS)
    Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
}

internal actual fun taskCleanupPathExists(path: String): Boolean = try {
    val attributes = Files.readAttributes(Paths.get(path), BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
    check(!attributes.isSymbolicLink) { "journal_unreadable" }
    true
} catch (_: java.nio.file.NoSuchFileException) { false }
