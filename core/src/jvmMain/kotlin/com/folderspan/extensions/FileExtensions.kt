package com.folderspan.extensions

import androidx.compose.ui.text.intl.Locale
import androidx.compose.ui.text.toLowerCase
import com.folderspan.data.file.FileSimpleInfo
import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Paths
import java.nio.file.attribute.BasicFileAttributes

fun File.toFileSimpleInfo(includeDirectoryEntryCount: Boolean = true): Result<FileSimpleInfo> {
    val path = Paths.get(absolutePath)
    val isSymbolicLink = Files.isSymbolicLink(path)
    // 获取基本文件属性
    val attrs: BasicFileAttributes = runCatching {
        Files.readAttributes(path, BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
    }.getOrElse { error -> return Result.failure(error) }
    var mineType = ""
    val isDirectoryWithoutFollowingLinks = attrs.isDirectory && !isSymbolicLink
    if (attrs.isRegularFile && !isSymbolicLink) {
        val extension = extension.toLowerCase(Locale.current)
        if (extension.isNotEmpty()) {
            mineType = ".$extension"
        }
    }

//    // 获取 POSIX 文件属性（如果支持）
//    val posixAttrs: PosixFileAttributes? = try {
//        Files.readAttributes(path, PosixFileAttributes::class.java)
//    } catch (e: Exception) {
//        null
//    }
//    println("path = $path")
//    println("逻辑大小: ${attrs?.size()} 字节 ${Files.size(path)}")
//    println("创建时间: ${attrs?.creationTime()}")
//    println("最后修改时间: ${attrs?.lastModifiedTime()}")
//    println("最后访问时间: ${attrs?.lastAccessTime()}")
//
//    // 打印权限信息（如果支持）
//    posixAttrs?.let { attributes ->
//        val owner: UserPrincipal = attributes.owner()
//        val group: GroupPrincipal = attributes.group()
//        val permissions: Set<PosixFilePermission> = attributes.permissions()
//
//        println("文件拥有者: $owner")
//        println("文件所属组: $group")
//        println("权限: ${permissions.joinToString(", ") { permission -> permission.name }}")
//    } ?: run {
//        println("不支持 POSIX 文件属性")
//    }

    return Result.success(
        FileSimpleInfo(
            name = name,
            description = "",
            isDirectory = isDirectoryWithoutFollowingLinks,
            isHidden = isHidden,
            path = absolutePath,
            mineType = mineType,
            // 普通列表保留“子项数量”语义；递归统计会关闭它，避免每个目录被额外列举一次。
            size = if (isDirectoryWithoutFollowingLinks) {
                if (includeDirectoryEntryCount) (listFiles() ?: emptyArray<File>()).size.toLong() else 0L
            } else {
                attrs.size()
            },
            createdDate = attrs.creationTime().toMillis(),
            updatedDate = attrs.lastModifiedTime().toMillis(),
            isSymbolicLink = isSymbolicLink,
            isSymbolicLinkKnown = true,
        )
    )
}
