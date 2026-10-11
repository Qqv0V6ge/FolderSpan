package com.folderspan.utils

import com.folderspan.exception.AuthorityException
import strings.AppStrings

/**
 * 可信调用方为单次文件系统操作作出的访问决定。
 *
 * 该值不得从网络或 MCP 请求参数中反序列化，也不提供默认值。
 */
enum class FileAccessPermission {
    Allowed,
    Denied,
}

internal fun requireFileAccess(permission: FileAccessPermission) {
    if (permission == FileAccessPermission.Denied) {
        throw fileAccessDeniedException()
    }
}

internal fun hasFileAccess(permission: FileAccessPermission): Boolean =
    permission == FileAccessPermission.Allowed

internal fun fileAccessDeniedException(): AuthorityException =
    AuthorityException(AppStrings.error_path_access_denied)

internal fun <T> deniedFileAccessResult(): Result<T> =
    Result.failure(fileAccessDeniedException())
