package com.folderspan.utils

import com.folderspan.exception.AuthorityException
import java.nio.channels.FileChannel
import java.nio.file.FileSystemException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.NoSuchFileException
import java.nio.file.OpenOption
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.StandardOpenOption
import strings.AppStrings

internal object NoFollowFileChannels {
    fun openRead(path: String): FileChannel {
        return open(path, StandardOpenOption.READ)
    }

    fun openReadWrite(path: String, create: Boolean = true): FileChannel {
        val options = mutableSetOf<OpenOption>(StandardOpenOption.READ, StandardOpenOption.WRITE)
        if (create) options += StandardOpenOption.CREATE
        return open(path, *options.toTypedArray())
    }

    fun openWriteTruncate(path: String, create: Boolean = true): FileChannel {
        val options = mutableSetOf<OpenOption>(StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING)
        if (create) options += StandardOpenOption.CREATE
        return open(path, *options.toTypedArray())
    }

    fun openAppend(path: String): FileChannel {
        return open(path, StandardOpenOption.WRITE, StandardOpenOption.APPEND, StandardOpenOption.CREATE)
    }

    private fun open(path: String, vararg options: OpenOption): FileChannel {
        val nioPath = Paths.get(path)
        return try {
            FileChannel.open(nioPath, setOf(*options, LinkOption.NOFOLLOW_LINKS))
        } catch (error: Exception) {
            throw mapOpenError(nioPath, path, error)
        }
    }

    private fun mapOpenError(nioPath: Path, path: String, error: Exception): Exception {
        if (error is NoSuchFileException) return Exception(AppStrings.ui_file_does_not_exist)
        if (isSymlinkRejection(nioPath, error)) {
            return AuthorityException(AppStrings.ui_not_allowed_to_read_write_symbolic_links_arg0.format(arg0 = (path).toString()))
        }
        return error
    }

    private fun isSymlinkRejection(nioPath: Path, error: Exception): Boolean {
        if (Files.isSymbolicLink(nioPath)) return true
        val detail = buildString {
            append(error.message.orEmpty())
            if (error is FileSystemException) {
                append(' ')
                append(error.reason.orEmpty())
            }
        }.lowercase()
        return "too many levels" in detail || "symbolic link" in detail || "symlink" in detail
    }
}
