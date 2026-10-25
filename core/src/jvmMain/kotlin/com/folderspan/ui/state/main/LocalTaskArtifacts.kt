package com.folderspan.ui.state.main

import com.folderspan.utils.FileAccessPermission
import com.folderspan.utils.NativePathUtils
import com.folderspan.utils.nativeSensitivePathDeniedException
import com.sun.jna.Function
import com.sun.jna.Memory
import com.sun.jna.NativeLibrary
import com.sun.jna.Platform
import com.sun.jna.Pointer
import com.sun.jna.WString
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

    actual fun describe(path: String, cacheArtifact: Boolean): LocalTaskArtifactDescription? {
        val attributes = try {
            Files.readAttributes(guarded(path, cacheArtifact), BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
        } catch (_: java.nio.file.NoSuchFileException) { return null }
        check(attributes.isRegularFile || attributes.isDirectory) { "ownership_changed" }
        return LocalTaskArtifactDescription(if (attributes.isRegularFile) attributes.size() else null)
    }

    actual fun inspect(path: String, cacheArtifact: Boolean): LocalTaskArtifactIdentity? {
        val target = guarded(path, cacheArtifact)
        val attributes = try {
            Files.readAttributes(target, BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
        } catch (_: java.nio.file.NoSuchFileException) { return null }
        check(attributes.isRegularFile || attributes.isDirectory) { "ownership_changed" }
        val key = fileIdentity(target, attributes)
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
        return LocalTaskArtifactIdentity(key, fingerprint)
    }

    actual fun deliver(path: String, finalPath: String, identity: String) {
        val target = guarded(path)
        val attributes = Files.readAttributes(target, BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
        check(attributes.isRegularFile && fileIdentity(target, attributes) == identity) { "ownership_changed" }
        // Preserve existing conflict behavior for a pre-existing destination, without ever claiming it.
        Files.move(guarded(path), guarded(finalPath), StandardCopyOption.REPLACE_EXISTING)
    }

    actual fun delete(path: String, identity: String, fingerprint: String, directory: Boolean, cacheArtifact: Boolean) {
        val current = inspect(path, cacheArtifact) ?: return
        check(current.identity == identity && current.fingerprint == fingerprint) { "ownership_changed" }
        // Files.delete is non-recursive and rejects a non-empty directory.
        Files.delete(guarded(path, cacheArtifact))
    }

    private fun fileIdentity(path: java.nio.file.Path, attributes: BasicFileAttributes): String =
        attributes.fileKey()?.toString() ?: if (Platform.isWindows()) {
            WindowsTaskArtifactIdentity.read(path)
        } else error("ownership_unverified")
}

private object WindowsTaskArtifactIdentity {
    private val kernel32 by lazy { NativeLibrary.getInstance("kernel32") }

    fun read(path: java.nio.file.Path): String {
        // JDK's Windows provider can omit fileKey. FILE_ID_INFO combines the volume
        // serial number and a 128-bit file ID, including on ReFS:
        // https://learn.microsoft.com/windows/win32/api/winbase/ns-winbase-file_id_info
        // Match JDK 17 WindowsPath's normalization before adding a long-path prefix.
        val pathText = path.toString().let { original ->
            if (original.length <= 247 || original.startsWith("\\\\?\\")) original else {
                Memory(32768L * 2).use { buffer ->
                    val length = kernel32.getFunction("GetFullPathNameW", Function.ALT_CONVENTION)
                        .invokeInt(arrayOf(WString(original), 32768, buffer, null))
                    check(length in 1 until 32768) { "ownership_unverified" }
                    buffer.getWideString(0)
                }
            }
        }
        val nativePath = pathText.let {
            when {
                // Match JDK 17 WindowsPath: adding \\?\ to a short path would
                // change trailing-dot normalization relative to Java's file IO.
                it.length <= 247 -> it
                it.startsWith("\\\\?\\") -> it
                it.startsWith("\\\\") -> "\\\\?\\UNC\\" + it.drop(2)
                else -> "\\\\?\\$it"
            }
        }
        val handle = kernel32.getFunction("CreateFileW", Function.ALT_CONVENTION).invokePointer(
            arrayOf(WString(nativePath), 0x80, 0x7, null, 3, 0x02200000, null),
        )
        check(handle != null && Pointer.nativeValue(handle) != -1L) { "ownership_unverified" }
        try {
            // OPEN_EXISTING, FILE_FLAG_BACKUP_SEMANTICS and FILE_FLAG_OPEN_REPARSE_POINT
            // open files/directories without following a leaf reparse point.
            return Memory(24).use { info ->
                check(kernel32.getFunction("GetFileInformationByHandleEx", Function.ALT_CONVENTION)
                    .invokeInt(arrayOf(handle, 18, info, 24)) != 0) { "ownership_unverified" }
                val fileId = info.getByteArray(8, 16)
                check(fileId.any { it != 0.toByte() }) { "ownership_unverified" }
                "windows:${java.lang.Long.toUnsignedString(info.getLong(0), 16)}:" +
                    fileId.joinToString("") { "%02x".format(it) }
            }
        } finally {
            kernel32.getFunction("CloseHandle", Function.ALT_CONVENTION).invokeInt(arrayOf(handle))
        }
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
