package com.folderspan.extensions

import com.folderspan.data.file.FileSimpleInfo
import com.folderspan.utils.FileAccessPermission
import com.folderspan.utils.PathUtils

fun String.replaceLast(oldValue: String, newValue: String): String {
    val lastIndex = this.lastIndexOf(oldValue)
    if (lastIndex < 0) return this
    return this.substring(0, lastIndex) + newValue + this.substring(lastIndex + oldValue.length)
}

fun String.getFileAndFolder(permission: FileAccessPermission): Result<List<FileSimpleInfo>> =
    PathUtils.getFileAndFolder(permission, this)

fun String.pathLevel(): Int {
    val normalized = this.replace('\\', '/').trim('/')
    if (normalized.isEmpty()) return 1
    return normalized.split('/').size
}

fun String.parentPath(separator: String): String {
    val normalizedPath = if (this.endsWith(separator)) this.dropLast(separator.length) else this
    val parent = normalizedPath.substringBeforeLast(separator, separator)
    return parent.ifEmpty { separator }
}

/**
 * 生成当前 IP 所在子网的所有 IP 地址。
 * 当前方法假设子网掩码为 /24，即最后一段 IP 范围为 0~255。
 *
 * @return 返回包含子网中所有 IP 地址的列表。如果当前字符串格式不为有效 IP 格式，则返回空列表。
 */
fun String.getSubnetIps(): List<String> {
    // 假设使用 /24 网段
    // 1. 将传入的 IP 切分为四段
    val parts = split(".")
    if (parts.size != 4) {
        // 如果格式不正确，直接返回空列表或根据需要抛出异常
        return emptyList()
    }

    // 2. 取前三段作为固定部分，最后一段作为子网内可变段
    val prefix = "${parts[0]}.${parts[1]}.${parts[2]}"
    val result = mutableListOf<String>()

    // 3. 生成 0～255 共 256 个 IP（可根据需要排除 0 或 255）
    for (i in 0..255) {
        val completeIp = "$prefix.$i"
        result.add(completeIp)
    }
    return result

}

/**
 * 判断两个 IPv4 地址是否在同一 /24 子网（前三段相同）。
 */
fun String.isSame24Subnet(other: String): Boolean {
    val a = this.split('.')
    val b = other.split('.')
    return !(a.size != 4 || b.size != 4) && a[0] == b[0] && a[1] == b[1] && a[2] == b[2]
}

expect fun String.parsePath(): List<String>
