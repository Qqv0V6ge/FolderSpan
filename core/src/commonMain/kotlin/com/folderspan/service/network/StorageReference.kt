package com.folderspan.service.network

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.protobuf.ProtoNumber

@OptIn(ExperimentalSerializationApi::class)
@Serializable
data class StorageReference(
    @ProtoNumber(1) val id: String = "",
    @ProtoNumber(2) val parentId: String = "",
    @ProtoNumber(3) val ancestorId: String = "",
    @ProtoNumber(4) val ancestorPath: String = "",
    @ProtoNumber(5) val content: StorageContentEntryReference? = null,
)

@OptIn(ExperimentalSerializationApi::class)
@Serializable
data class StorageContentEntryReference(
    @ProtoNumber(1) val vaultId: String,
    @ProtoNumber(2) val entryId: String,
    @ProtoNumber(3) val revisionId: String = "",
    @ProtoNumber(4) val ordinaryPath: String = "",
)

@Serializable
data class StoragePath(val path: String, val reference: StorageReference? = null)

internal fun StorageReference.forPath(oldPath: String, newPath: String, directory: Boolean): StorageReference? {
    if (oldPath == newPath) return this
    content?.let { entry ->
        if (directory && newPath.startsWith(oldPath.trimEnd('/') + "/")) {
            val relative = newPath.removePrefix(oldPath.trimEnd('/') + "/")
            val suffix = listOf(entry.ordinaryPath.trim('/'), relative).filter(String::isNotEmpty).joinToString("/")
            return StorageReference(content = entry.copy(revisionId = "", ordinaryPath = suffix))
        }
        return null
    }
    if (directory && id.isNotEmpty() && newPath.startsWith(oldPath.trimEnd('/') + "/")) {
        return StorageReference(ancestorId = id, ancestorPath = oldPath)
    }
    val parent = oldPath.substringBeforeLast('/', "/").ifEmpty { "/" }
    if (parentId.isNotEmpty() && newPath == parent) return StorageReference(id = parentId)
    if (parentId.isNotEmpty() && newPath.substringBeforeLast('/', "/").ifEmpty { "/" } == parent) {
        return StorageReference(ancestorId = parentId, ancestorPath = parent)
    }
    if (ancestorId.isNotEmpty() && newPath == ancestorPath) return StorageReference(id = ancestorId)
    if (ancestorId.isNotEmpty() && newPath.startsWith(ancestorPath.trimEnd('/') + "/")) {
        return copy(id = "", parentId = "")
    }
    return null
}
