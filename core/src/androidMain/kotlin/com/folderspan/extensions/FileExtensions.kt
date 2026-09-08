package com.folderspan.extensions

import android.os.Build
import android.system.Os
import android.system.OsConstants
import androidx.compose.ui.text.intl.Locale
import androidx.compose.ui.text.toLowerCase
import com.folderspan.data.file.FileSimpleInfo
import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Paths
import java.nio.file.attribute.BasicFileAttributes

fun File.toFileSimpleInfo(includeDirectoryEntryCount: Boolean = true): Result<FileSimpleInfo> {
    val isSymbolicLink = runCatching {
        OsConstants.S_ISLNK(Os.lstat(absolutePath).st_mode)
    }.getOrDefault(false)
    val attrs = getFileAttributesCompat()
    var mineType = ""
    val isDirectoryWithoutFollowingLinks = !isSymbolicLink && isDirectory
    if (!isSymbolicLink && isFile) {
        val extension = extension.toLowerCase(Locale.current)
        if (extension.isNotEmpty()) {
            mineType = ".$extension"
        }
    }

    return Result.success(
        FileSimpleInfo(
            name = name,
            description = "",
            isDirectory = isDirectoryWithoutFollowingLinks,
            isHidden = isHidden,
            path = absolutePath,
            mineType = mineType,
            size = if (isDirectoryWithoutFollowingLinks) {
                if (includeDirectoryEntryCount) (listFiles() ?: emptyArray<File>()).size.toLong() else 0L
            } else {
                attrs["size"] ?: 0L
            },
            createdDate = attrs["creationTime"] ?: 0L,
            updatedDate = attrs["lastModifiedTime"] ?: 0L,
            isSymbolicLink = isSymbolicLink,
            isSymbolicLinkKnown = true,
        )
    )
}

fun File.getFileAttributesCompat(): Map<String, Long> {
    // 在 API 26+ 使用 NIO
    val path = Paths.get(absolutePath)
    val attrs = Files.readAttributes(path, BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
    return mapOf(
        "creationTime" to attrs.creationTime().toMillis(),
        "lastModifiedTime" to attrs.lastModifiedTime().toMillis(),
        "size" to attrs.size(),
    )
}
