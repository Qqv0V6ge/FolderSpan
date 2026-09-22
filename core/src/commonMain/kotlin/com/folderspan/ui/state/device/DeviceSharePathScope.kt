package com.folderspan.ui.state.device

import com.folderspan.data.file.FileSimpleInfo
import com.folderspan.utils.PathUtils
import com.folderspan.utils.isAndroidContentUriPath

data class DeviceSharePathGrant(
    val path: String,
    val isDirectory: Boolean,
    val displayName: String = "",
)

data class DeviceShareVirtualRoot(
    val name: String,
    val grant: DeviceSharePathGrant,
) {
    val path: String = "/$name"
}

class DeviceSharePathScope(
    grants: List<DeviceSharePathGrant>,
    private val pathSeparator: String = PathUtils.getPathSeparator(),
) {
    val grants: List<DeviceSharePathGrant> = grants
        .mapNotNull { grant ->
            normalizeDeviceSharePath(grant.path, pathSeparator)
                .takeIf(String::isNotBlank)
                ?.let { normalized -> grant.copy(path = normalized) }
        }
        .distinctBy { grant -> pathKey(grant.path) to grant.isDirectory }

    val virtualRoots: List<DeviceShareVirtualRoot> = buildVirtualRoots(this.grants)

    fun matchingContentGrant(path: String): DeviceSharePathGrant? {
        val normalized = normalizeDeviceSharePath(path, pathSeparator)
        if (normalized.isBlank()) return null
        return grants.firstOrNull { grant ->
            pathsEqual(normalized, grant.path) ||
                (grant.isDirectory && isChildPath(normalized, grant.path))
        }
    }

    fun allowsContentPath(path: String): Boolean = matchingContentGrant(path) != null

    fun allowsListingPath(path: String): Boolean {
        val normalized = normalizeDeviceSharePath(path, pathSeparator)
        if (normalized.isBlank()) return false
        return allowsContentPath(normalized) || grants.any { grant -> isChildPath(grant.path, normalized) }
    }

    fun filterListing(entries: List<FileSimpleInfo>): List<FileSimpleInfo> {
        return entries.filter { entry ->
            allowsContentPath(entry.path) || grants.any { grant -> isChildPath(grant.path, entry.path) }
        }
    }

    /**
     * 将 Share 的虚拟路径（始终以 `/` 为根）解析为本机授权路径。
     * `/` 本身只是虚拟容器，不对应任何本机目录。
     */
    fun resolveVirtualContentPath(path: String): String? {
        val segments = parseVirtualPath(path) ?: return null
        if (segments.isEmpty()) return null
        val root = virtualRoots.firstOrNull { item -> virtualNamesEqual(item.name, segments.first()) }
            ?: return null
        if (segments.size > 1 && !root.grant.isDirectory) return null
        val resolved = segments.drop(1).fold(root.grant.path) { parent, segment ->
            val normalizedParent = if (isRootPath(parent)) parent else parent.trimEnd('/', '\\')
            if (normalizedParent.endsWith(pathSeparator)) normalizedParent + segment
            else normalizedParent + pathSeparator + segment
        }
        return resolved.takeIf(::allowsContentPath)
    }

    private fun buildVirtualRoots(grants: List<DeviceSharePathGrant>): List<DeviceShareVirtualRoot> {
        val usedNames = mutableSetOf<String>()
        return grants.map { grant ->
            val baseName = shareRootName(grant)
            var candidate = baseName
            var suffix = 2
            while (!usedNames.add(virtualNameKey(candidate))) {
                candidate = "$baseName ($suffix)"
                suffix++
            }
            DeviceShareVirtualRoot(candidate, grant)
        }
    }

    private fun shareRootName(grant: DeviceSharePathGrant): String {
        return shareFileNameFromGrant(grant)
    }

    private fun parseVirtualPath(path: String): List<String>? {
        if (!path.startsWith('/') || (isWindowsPath() && '\\' in path)) return null
        if (path == "/") return emptyList()
        val segments = path.removePrefix("/").split('/')
        if (segments.any { segment -> segment.isBlank() || segment == "." || segment == ".." }) return null
        return segments
    }

    private fun virtualNamesEqual(first: String, second: String): Boolean {
        return if (isWindowsPath()) first.equals(second, ignoreCase = true) else first == second
    }

    private fun virtualNameKey(name: String): String = if (isWindowsPath()) name.lowercase() else name

    private fun pathsEqual(first: String, second: String): Boolean {
        return if (isWindowsPath()) first.equals(second, ignoreCase = true) else first == second
    }

    private fun isChildPath(path: String, parent: String): Boolean {
        if (pathsEqual(path, parent)) return false
        val parentWithSeparator = if (isRootPath(parent)) parent else parent.trimEnd('/', '\\') + pathSeparator
        return if (isWindowsPath()) {
            path.startsWith(parentWithSeparator, ignoreCase = true)
        } else {
            path.startsWith(parentWithSeparator)
        }
    }

    private fun pathKey(path: String): String = if (isWindowsPath()) path.lowercase() else path

    private fun isWindowsPath(): Boolean = pathSeparator == "\\"

    private fun isRootPath(path: String): Boolean = path == "/" || path == "\\" ||
        (isWindowsPath() && Regex("^[a-zA-Z]:\\\\$").matches(path))
}

internal fun normalizeDeviceSharePath(path: String, pathSeparator: String = PathUtils.getPathSeparator()): String {
    val trimmed = path.trim()
    if (trimmed.isEmpty()) return ""
    // content:// 的空段（scheme 后的 `//`）不能按文件系统路径折叠，否则会变成 content:/ 并被安全策略判为 invalid_path。
    if (isAndroidContentUriPath(trimmed)) return trimmed
    val windows = pathSeparator == "\\"
    val separator = if (windows) '\\' else '/'
    val unified = if (windows) trimmed.replace('/', '\\') else trimmed.replace('\\', '/')
    val drive = if (windows && unified.length >= 2 && unified[1] == ':' && unified[0].isLetter()) {
        unified.substring(0, 2).lowercase()
    } else {
        ""
    }
    val absolute = drive.isNotEmpty() || unified.startsWith(separator)
    val withoutPrefix = if (drive.isNotEmpty()) unified.substring(2) else unified
    val segments = mutableListOf<String>()
    withoutPrefix.split(separator).forEach { rawSegment ->
        when (val segment = rawSegment.trim()) {
            "", "." -> Unit
            ".." -> if (segments.isNotEmpty()) segments.removeAt(segments.lastIndex)
            else -> segments += segment
        }
    }
    val prefix = when {
        drive.isNotEmpty() -> "$drive$separator"
        absolute -> separator.toString()
        else -> ""
    }
    val normalized = prefix + segments.joinToString(separator.toString())
    return when {
        normalized.isNotEmpty() -> normalized
        absolute -> prefix
        else -> ""
    }
}

internal fun shareFileNameFromGrant(grant: DeviceSharePathGrant): String {
    return sanitizeShareFileName(grant.displayName)
        ?: fileNameFromShareLocator(grant.path)
        ?: "root"
}

internal fun shareListedFileName(metadataName: String, virtualRootName: String): String {
    return sanitizeShareFileName(metadataName) ?: virtualRootName
}

private fun fileNameFromShareLocator(path: String): String? {
    if (isAndroidContentUriPath(path)) {
        val lastSegment = path.substringAfterLast('/').substringAfterLast('\\')
        val decoded = decodeShareUriComponent(lastSegment)
        val nested = decoded
            .substringAfterLast('/')
            .substringAfterLast('\\')
            .substringAfterLast(':')
        return sanitizeShareFileName(nested) ?: sanitizeShareFileName(decoded)
    }
    return sanitizeShareFileName(path)
}

private fun sanitizeShareFileName(value: String): String? {
    val trimmed = value.trim()
    if (trimmed.isEmpty() || trimmed == "." || trimmed == "..") return null
    if (isAndroidContentUriPath(trimmed)) return null
    val last = trimmed.substringAfterLast('/').substringAfterLast('\\').trim()
    if (last.isEmpty() || last == "." || last == "..") return null
    if (isAndroidContentUriPath(last)) return null
    return last
}

private fun decodeShareUriComponent(value: String): String {
    if ('%' !in value) return value
    val bytes = ArrayList<Byte>(value.length)
    var index = 0
    while (index < value.length) {
        val char = value[index]
        if (char == '%' && index + 2 < value.length) {
            val high = hexValue(value[index + 1])
            val low = hexValue(value[index + 2])
            if (high >= 0 && low >= 0) {
                bytes.add(((high shl 4) + low).toByte())
                index += 3
                continue
            }
        }
        value[index].toString().encodeToByteArray().forEach { item -> bytes.add(item) }
        index += 1
    }
    return bytes.toByteArray().decodeToString()
}

private fun hexValue(char: Char): Int = when (char) {
    in '0'..'9' -> char.code - '0'.code
    in 'a'..'f' -> char.code - 'a'.code + 10
    in 'A'..'F' -> char.code - 'A'.code + 10
    else -> -1
}
