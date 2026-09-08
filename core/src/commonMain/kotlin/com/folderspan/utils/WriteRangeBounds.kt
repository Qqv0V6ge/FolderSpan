package com.folderspan.utils

/**
 * 判断 `[offset, offset + length)` 是否完全落在 `[0, fileSize]` 内。
 *
 * 使用减法比较，避免 `offset + length` 的 Long 溢出把越界范围误判为合法。
 * `fileSize == 0` 时仅允许 `offset == 0 && length == 0`（空截断）。
 */
internal fun isWriteRangeWithinFile(fileSize: Long, offset: Long, length: Long): Boolean {
    if (fileSize < 0L || offset < 0L || length < 0L) return false
    if (offset > fileSize) return false
    return length <= fileSize - offset
}
