package com.folderspan.utils

import com.folderspan.settings.restrictOwnerOnly
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.PosixFilePermissions
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json

internal data class AuthorizedMacDirectory(val path: String, val bookmark: String)

internal interface MacDirectoryAccessBridge {
    fun selectDirectory(): AuthorizedMacDirectory?
    fun resolveDirectory(bookmark: String): AuthorizedMacDirectory
}

/** Local-only registry. Never place security scopes in synced settings or API data. */
internal class MacDirectoryAccessStore(
    private val file: Path,
    private val bridge: MacDirectoryAccessBridge,
) {
    private val serializer = MapSerializer(String.serializer(), String.serializer())
    private val bookmarks = if (Files.exists(file)) {
        Json.decodeFromString(serializer, Files.readString(file)).toMutableMap()
    } else mutableMapOf()
    private val accessible = linkedSetOf<String>()

    @Synchronized
    fun restore() {
        var changed = false
        for ((savedPath, bookmark) in bookmarks.toMap()) {
            val resolved = runCatching { bridge.resolveDirectory(bookmark) }.getOrNull() ?: continue
            accessible.add(resolved.path)
            if (savedPath != resolved.path || bookmark != resolved.bookmark) {
                bookmarks.remove(savedPath)
                bookmarks[resolved.path] = resolved.bookmark
                changed = true
            }
        }
        if (changed) persist()
    }

    @Synchronized
    fun selectDirectory(): String? {
        val selected = bridge.selectDirectory() ?: return null
        val previous = bookmarks.put(selected.path, selected.bookmark)
        try {
            persist()
        } catch (error: Exception) {
            if (previous == null) bookmarks.remove(selected.path) else bookmarks[selected.path] = previous
            throw error
        }
        accessible.add(selected.path)
        return selected.path
    }

    @Synchronized
    fun accessibleDirectories(): List<String> = accessible.toList()

    private fun persist() {
        Files.createDirectories(file.parent)
        val temporary = if (Files.getFileStore(file.parent).supportsFileAttributeView("posix")) {
            Files.createTempFile(file.parent, ".mac-folder-access-", ".tmp",
                PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")))
        } else Files.createTempFile(file.parent, ".mac-folder-access-", ".tmp")
        try {
            restrictOwnerOnly(temporary, directory = false)
            Files.writeString(temporary, Json.encodeToString(serializer, bookmarks))
            Files.move(temporary, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } finally {
            Files.deleteIfExists(temporary)
        }
    }
}
