package com.folderspan.utils

import com.folderspan.createSettings
import com.folderspan.data.file.FileSimpleInfo
import kotlin.io.encoding.Base64

/** Target-scoped application records. Quota failures propagate instead of pretending to save. */
internal actual object PrivateFileStorage {
    private const val PREFIX = "private-files:"
    private val settings by lazy { createSettings() }
    actual fun getAppPath() = "/.folderspan-browser"
    actual fun getCachePath() = "${getAppPath()}/cache"
    actual fun getPathSeparator() = "/"
    fun owns(path: String) = path == getAppPath() || path.startsWith("${getAppPath()}/")
    private fun key(path: String): String {
        require(owns(path)) { "not browser application storage" }
        require(path.split('/').none { it == ".." || it == "." }) { "invalid application storage path" }
        return PREFIX + path.trimEnd('/')
    }
    actual fun exists(permission: FileAccessPermission, path: String): Boolean {
        requireFileAccess(permission)
        return settings.hasKey(key(path)) || settings.keys.any { it.startsWith(key(path) + "/") }
    }
    actual fun createDirectoryIfNotExists(permission: FileAccessPermission, path: String) {
        requireFileAccess(permission)
        check(settings.getStringOrNull(key(path))?.startsWith("f") != true) { "application path is a file" }
        settings.putString(key(path), "d")
    }
    actual fun createFile(permission: FileAccessPermission, path: String): Result<Boolean> = runCatching {
        requireFileAccess(permission)
        check(settings.getStringOrNull(key(path)) != "d") { "application path is a directory" }
        if (!settings.hasKey(key(path))) settings.putString(key(path), "f")
        true
    }
    actual fun deleteFile(permission: FileAccessPermission, path: String): Result<Boolean> = runCatching {
        requireFileAccess(permission)
        val prefix = key(path)
        settings.keys.filter { it == prefix || it.startsWith("$prefix/") }.forEach(settings::remove)
        true
    }
    actual fun deleteDirectory(permission: FileAccessPermission, path: String) {
        deleteFile(permission, path).getOrThrow()
    }
    actual fun readFile(permission: FileAccessPermission, path: String): Result<ByteArray> = runCatching {
        requireFileAccess(permission)
        val value = requireNotNull(settings.getStringOrNull(key(path))) { "application record missing" }
        check(value.startsWith('f')) { "not an application record" }
        Base64.decode(value.drop(1))
    }
    fun write(path: String, bytes: ByteArray) = settings.putString(key(path), "f" + Base64.encode(bytes))
    actual fun readFileLines(permission: FileAccessPermission, path: String): List<String> =
        readFile(permission, path).getOrThrow().decodeToString().lines()
    actual fun appendToFile(permission: FileAccessPermission, path: String, content: String) {
        requireFileAccess(permission)
        val previous = if (exists(permission, path)) readFile(permission, path).getOrThrow() else byteArrayOf()
        write(path, previous + content.encodeToByteArray())
    }
    actual fun rename(permission: FileAccessPermission, path: String, oldName: String, newName: String): Result<Boolean> = runCatching {
        requireFileAccess(permission)
        require(oldName.isNotBlank() && newName.isNotBlank() && '/' !in oldName && '/' !in newName)
        val source = key("$path/$oldName")
        val target = key("$path/$newName")
        if (source == target) return@runCatching true
        check(!settings.hasKey(target)) { "application target already exists" }
        val entries = settings.keys.filter { it == source || it.startsWith("$source/") }
        require(entries.isNotEmpty()) { "application record missing" }
        entries.forEach { settings.putString(target + it.removePrefix(source), settings.getString(it, "")) }
        entries.forEach(settings::remove)
        true
    }
    fun info(path: String): FileSimpleInfo {
        val value = settings.getStringOrNull(key(path))
        require(value != null || exists(FileAccessPermission.Allowed, path)) { "application record missing" }
        return FileSimpleInfo.nullFileSimpleInfo().copy(
            path = path, name = path.substringAfterLast('/'), isDirectory = value == null || value == "d",
            size = if (value?.startsWith('f') == true) Base64.decode(value.drop(1)).size.toLong() else 0,
            isSymbolicLinkKnown = true,
        )
    }
    actual fun getFileAndFolder(permission: FileAccessPermission, path: String): Result<List<FileSimpleInfo>> = runCatching {
        requireFileAccess(permission)
        require(exists(permission, path)) { "application directory missing" }
        val prefix = key(path) + "/"
        settings.keys.filter { it.startsWith(prefix) }.map { it.removePrefix(prefix).substringBefore('/') }
            .distinct().map { info(path.trimEnd('/') + "/" + it) }
    }
}
