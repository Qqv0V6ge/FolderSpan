@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class, kotlinx.cinterop.BetaInteropApi::class)

package com.folderspan.service.network

import kotlinx.cinterop.ObjCObjectVar
import kotlinx.cinterop.alloc
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.toKString
import kotlinx.cinterop.usePinned
import kotlinx.cinterop.value
import kotlinx.cinterop.addressOf
import okio.FileSystem
import okio.Path
import okio.Path.Companion.toPath
import platform.Foundation.NSError
import platform.Foundation.NSFileManager
import platform.Foundation.NSFileProtectionCompleteUntilFirstUserAuthentication
import platform.Foundation.NSFileProtectionKey
import platform.Foundation.NSNumber
import platform.Foundation.NSURL
import platform.Foundation.NSURLIsExcludedFromBackupKey
import platform.posix.S_IFDIR
import platform.posix.S_IFMT
import platform.posix.S_IFREG
import platform.posix.chmod
import platform.posix.close
import platform.posix.lstat
import platform.posix.mkdir
import platform.posix.mkstemp
import platform.posix.rename
import platform.posix.rmdir
import platform.posix.stat
import platform.posix.unlink

internal actual object StorageContentFileProtection {
    actual fun restrictOwnerOnly(path: Path, directory: Boolean) {
        memScoped {
            val attributes = alloc<stat>()
            check(lstat(path.toString(), attributes.ptr) == 0) { "Content private path is unavailable" }
            val kind = if (directory) S_IFDIR else S_IFREG
            check(attributes.st_mode.toInt() and S_IFMT == kind) { "Content private path type changed" }
            val mode = if (directory) 448 else 384
            check(chmod(path.toString(), mode.toUShort()) == 0) { "Content private permissions could not be set" }
            check(lstat(path.toString(), attributes.ptr) == 0 &&
                attributes.st_mode.toInt() and 511 == mode) { "Content private permissions were not applied" }
        }
        applyPrivateFileProtection(path)
    }

    actual fun atomicMove(source: Path, target: Path) {
        check(source.parent == target.parent) { "Authenticated publication requires a sibling temporary file" }
        check(fileIdentity(source) != null) { "Authenticated publication source changed" }
        check(rename(source.toString(), target.toString()) == 0) { "Atomic authenticated publication failed" }
    }

    actual fun fileIdentity(path: Path): String? = memScoped {
        val attributes = alloc<stat>()
        if (lstat(path.toString(), attributes.ptr) != 0 || attributes.st_mode.toInt() and S_IFMT != S_IFREG) null
        else "${attributes.st_dev}:${attributes.st_ino}"
    }

    actual fun createPrivateTempFile(parent: Path, prefix: String): Path {
        require(prefix.isNotBlank() && '/' !in prefix)
        val buffer = (parent.resolve(prefix + "XXXXXX").toString() + "\u0000").encodeToByteArray()
        val created = buffer.usePinned { pinned ->
            val descriptor = mkstemp(pinned.addressOf(0))
            check(descriptor >= 0) { "Could not create private content temporary file" }
            close(descriptor)
            pinned.addressOf(0).toKString().toPath()
        }
        return try {
            restrictOwnerOnly(created, directory = false)
            created
        } catch (error: Throwable) {
            unlink(created.toString())
            throw error
        }
    }

    actual fun createPrivateDirectory(path: Path) {
        var created = false
        if (FileSystem.SYSTEM.metadataOrNull(path) == null) {
            path.parent?.let { parent ->
                if (FileSystem.SYSTEM.metadataOrNull(parent) == null) createPrivateDirectory(parent)
            }
            created = mkdir(path.toString(), 448u) == 0
            if (!created) {
                check(FileSystem.SYSTEM.metadataOrNull(path)?.let { it.isDirectory && it.symlinkTarget == null } == true) {
                    "Could not create private content directory"
                }
            }
        }
        try {
            restrictOwnerOnly(path, directory = true)
        } catch (error: Throwable) {
            // Remove only the empty directory created by this call, never an existing cache or its contents.
            if (created) rmdir(path.toString())
            throw error
        }
    }

    private fun applyPrivateFileProtection(path: Path) = memScoped {
        val value = path.toString()
        val error = alloc<ObjCObjectVar<NSError?>>()
        error.value = null
        val excluded = NSURL.fileURLWithPath(value).setResourceValue(
            NSNumber(bool = true), forKey = NSURLIsExcludedFromBackupKey, error = error.ptr)
        check(excluded && error.value == null) { "Content cache could not be excluded from backups" }

        error.value = null
        val protected = NSFileManager.defaultManager.setAttributes(
            mapOf(NSFileProtectionKey to NSFileProtectionCompleteUntilFirstUserAuthentication),
            ofItemAtPath = value, error = error.ptr)
        check(protected && error.value == null) { "Content file protection could not be applied" }

        error.value = null
        val resources = NSURL.fileURLWithPath(value).resourceValuesForKeys(listOf(NSURLIsExcludedFromBackupKey), error.ptr)
        val backupExcluded = when (val applied = resources?.get(NSURLIsExcludedFromBackupKey)) {
            is NSNumber -> applied.boolValue
            is Boolean -> applied
            else -> false
        }
        check(error.value == null && backupExcluded) { "Content backup exclusion could not be verified" }

        error.value = null
        val attributes = NSFileManager.defaultManager.attributesOfItemAtPath(value, error.ptr)
        val protection = attributes?.get(NSFileProtectionKey)
        // Simulator Foundation accepts the protection class but exposes no protection attribute.
        // Device binaries must always read back the exact requested class; neither target accepts another class.
        val verified = protection == NSFileProtectionCompleteUntilFirstUserAuthentication ||
            !storageContentSupportsFileProtectionReadback && protection == null
        check(error.value == null && attributes != null && verified) {
            "Content file protection could not be verified (value=$protection, error=${error.value?.code})"
        }
    }

    actual fun normalizedAbsolutePath(path: String): Path =
        (if (path.toPath().isAbsolute) path else NSFileManager.defaultManager.currentDirectoryPath + "/" + path).toPath(normalize = true)
}

/** Selected by the compiled target, never by an environment override or runtime device name. */
internal expect val storageContentSupportsFileProtectionReadback: Boolean
