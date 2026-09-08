package com.folderspan.utils

import com.folderspan.data.file.FileSimpleInfo
import com.folderspan.data.file.PathInfo
import kotlinx.coroutines.flow.Flow

internal const val FILE_LISTING_ISSUE_LIMIT = 20

data class FileListingIssue(
    val path: String,
    val message: String,
)

data class FileListingResult(
    val entries: List<FileSimpleInfo>,
    val issueCount: Int = 0,
    val issues: List<FileListingIssue> = emptyList(),
)

internal fun Throwable.toFileListingIssueMessage(): String =
    message?.takeIf { value -> value.isNotBlank() }
        ?: this::class.simpleName
        ?: "Error"

/**
 * 剥离 Win32 扩展长度/设备路径前缀，避免 `\\?\C:\...`、`\\.\C:\...`、`\??\C:\...`
 * 这类原义路径在纯词法比较中绕过安全边界，而文件系统仍按同一真实文件打开。
 * `\\?\UNC\server\share` 还原为 `\\server\share` 的规范化形式。
 */
private fun stripWindowsVerbatimPathPrefix(value: String): String {
    if (!value.startsWith('/')) return value
    val lower = value.lowercase()
    return when {
        lower.startsWith("//?/unc/") -> "/" + value.substring(VERBATIM_UNC_PREFIX_LENGTH)
        lower.startsWith("//?/") || lower.startsWith("//./") || value.startsWith("/??/") ->
            value.substring(VERBATIM_PREFIX_LENGTH)
        else -> value
    }
}

private const val VERBATIM_PREFIX_LENGTH = 4
private const val VERBATIM_UNC_PREFIX_LENGTH = 8
private const val DARWIN_PRIVATE_VAR_PREFIX_LENGTH = "/private/var/".length

internal fun normalizePathForSecurityBoundary(path: String, separator: String): String? {
    if (path.isBlank()) return null
    val windowsStyle = separator == "\\"
    val value = if (windowsStyle) stripWindowsVerbatimPathPrefix(path.trim().replace('\\', '/')) else path.trim()
    val drivePrefix = if (windowsStyle && value.length >= 2 && value[1] == ':') {
        value.substring(0, 2).lowercase()
    } else {
        ""
    }
    val remainder = if (drivePrefix.isNotEmpty()) value.drop(2) else value
    if (!remainder.startsWith('/')) return null

    val components = mutableListOf<String>()
    for (component in remainder.split('/')) {
        when (component) {
            "", "." -> Unit
            ".." -> if (components.isEmpty()) return null else components.removeAt(components.lastIndex)
            else -> components += component
        }
    }
    val root = if (drivePrefix.isEmpty()) "/" else "$drivePrefix/"
    val normalized = if (components.isEmpty()) root else root + components.joinToString("/")
    return if (windowsStyle) normalized else foldDarwinPrivateVarAlias(normalized)
}

/**
 * Darwin 上 `/var` 是 `/private/var` 的符号链接。词法安全边界把两者折成 `/var`，
 * 避免规则根登记 `/var/...` 时请求 `/private/var/...`（或 realpath 之后）被当成普通路径。
 */
internal fun foldDarwinPrivateVarAlias(path: String): String {
    val lower = path.lowercase()
    return when {
        lower == "/private/var" -> "/var"
        lower.startsWith("/private/var/") -> "/var/" + path.substring(DARWIN_PRIVATE_VAR_PREFIX_LENGTH)
        else -> path
    }
}

internal fun isPathInsideRootLexically(rootPath: String, targetPath: String, separator: String): Boolean {
    val root = normalizePathForSecurityBoundary(rootPath, separator) ?: return false
    val target = normalizePathForSecurityBoundary(targetPath, separator) ?: return false
    return target == root || target.startsWith(if (root.endsWith('/')) root else "$root/")
}

internal fun isRestrictedAliasFilesystemPath(path: String, separator: String): Boolean {
    val normalized = normalizePathForSecurityBoundary(path, separator) ?: return true
    return RESTRICTED_UNIX_FILESYSTEM_ROOTS.any { root ->
        normalized == root || normalized.startsWith("$root/")
    }
}

private val RESTRICTED_UNIX_FILESYSTEM_ROOTS = listOf("/proc", "/sys", "/dev")

/**
 * 路径工具类，用于处理路径和目录操作的多平台实现。
 */
expect object PathUtils {
    /**
     * 获取指定路径下的所有文件和文件夹。
     *
     * @param path 目录路径。
     * @return 包含文件列表的 Result，如果失败返回错误信息。
     */
    fun getFileAndFolder(permission: FileAccessPermission, path: String): Result<List<FileSimpleInfo>>

    /**
     * 获取目录项，同时返回读取单个目录项元数据时发生的有限条错误详情。
     * issueCount 保存完整错误数量，issues 最多保留 FILE_LISTING_ISSUE_LIMIT 条，避免错误集合占用过多内存。
     */
    fun getFileAndFolderWithIssues(permission: FileAccessPermission, path: String): Result<FileListingResult>

    /**
     * 判断路径本身是否为符号链接，不跟随链接目标。
     */
    fun isSymbolicLink(permission: FileAccessPermission, path: String): Boolean

    /**
     * 校验目标路径位于根内，且根以下的现有路径组件不包含符号链接。
     * allowNonExistentLeaf 仅允许最终一级不存在，父目录仍必须可验证。
     */
    fun isPathWithinRoot(
        permission: FileAccessPermission,
        rootPath: String,
        targetPath: String,
        allowNonExistentLeaf: Boolean = false,
    ): Boolean

    /**
     * 解析本地路径用于敏感访问检查：跟随已存在的符号链接，返回规范路径。
     * allowNonExistentLeaf 时解析已存在的父目录并拼上最后一级名称。
     * 无法解析或权限不足时返回 null。
     */
    fun resolveCanonicalPath(
        permission: FileAccessPermission,
        path: String,
        allowNonExistentLeaf: Boolean = false,
    ): String?

    /**
     * 获取应用程序当前工作目录。
     *
     * @return 应用程序目录路径。
     */
    fun getAppPath(): String

    /**
     * 获取用户主目录。
     *
     * @return 用户主目录路径。
     */
    fun getHomePath(): String

    /**
     * 获取系统缓存目录。
     *
     * @return 缓存目录路径。
     */
    fun getCachePath(): String

    /**
     * 获取当前系统的路径分隔符。
     *
     * @return 路径分隔符（例如：Unix 系统为 "/"，Windows 系统为 "\"）。
     */
    fun getPathSeparator(): String

    /**
     * 获取系统的所有根路径。
     *
     * @return 包含根路径列表的 Result，每个根路径包含路径、总空间和可用空间信息。
     */
    fun getRootPaths(permission: FileAccessPermission): Result<List<PathInfo>>

    /**
     * 递归遍历指定路径下的所有文件和文件夹。
     *
     * @param path 要遍历的目录路径。
     * @return Flow，发射每个子目录的文件列表。
     */
    fun traverse(permission: FileAccessPermission, path: String): Flow<Result<List<FileSimpleInfo>>>

    /**
     * 检查文件或目录是否存在。
     *
     * @param path 文件或目录路径。
     * @return 如果存在返回 true，否则返回 false。
     */
    fun exists(permission: FileAccessPermission, path: String): Boolean

    /**
     * 如果目录不存在则创建目录。
     *
     * @param path 目录路径。
     */
    fun createDirectoryIfNotExists(permission: FileAccessPermission, path: String)

    /**
     * 删除目录及其所有内容。
     *
     * @param path 目录路径。
     */
    fun deleteDirectory(permission: FileAccessPermission, path: String)
}
