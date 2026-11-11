package com.folderspan.ui.state.main

import com.folderspan.data.file.FileSimpleInfo
import com.folderspan.service.network.StorageReference
import korlibs.crypto.sha256
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

internal fun syncSelectedSourceKey(source: FileSimpleInfo): String = Json.encodeToString(listOf(
    source.protocol.name, source.protocolId, source.path, source.name, source.isDirectory.toString(),
    Json.encodeToString(source.storageReference),
)).encodeToByteArray().sha256().hexLower

/** Portable current locators preserve the original selection identity used by the definition fingerprint. */
fun SyncTask.resolvedSelectedSources(): List<FileSimpleInfo> = selectedSources.map { selected ->
    val reference = currentSourceReferences[syncSelectedSourceKey(selected)]
    if (reference == null || selected.isDirectory || selected.storageReference?.id.isNullOrEmpty()) selected else {
        require(reference.id.isNotEmpty() && (selected.storageReference.parentId.isNullOrEmpty() ||
            selected.storageReference.parentId == reference.parentId)) { "Current source reference leaves the selected parent" }
        selected.copy(storageReference = reference)
    }
}

/** Only known selection, configuration or committed-baseline IDs can identify the canonical selected file. */
internal suspend fun resolveSyncSelectedFile(
    selected: FileSimpleInfo,
    baseline: SyncBaseline?,
    separator: String,
    configuredReference: StorageReference? = null,
    listParent: suspend (FileSimpleInfo) -> Pair<FileSimpleInfo, List<FileSimpleInfo>>,
): Pair<FileSimpleInfo, FileSimpleInfo?> {
    val originalReference = requireNotNull(selected.storageReference)
    require(!selected.isDirectory && originalReference.id.isNotEmpty())
    require(baseline == null || baseline.relativePath == selected.name &&
        baseline.participants["source"]?.isDirectory != true) { "Selected source baseline does not match" }
    val references = listOfNotNull(baseline?.selectedSourceReference, baseline?.participants?.get("source")?.storageReference,
        configuredReference, originalReference).distinct()
    require(references.all { it.id.isNotEmpty() }) { "Selected source binding is unavailable" }
    val oldParent = originalReference.parentId
    require(references.all { oldParent.isEmpty() || it.parentId.isEmpty() || oldParent == it.parentId }) {
        "Selected source binding leaves its original parent"
    }
    val parentPath = parentPathOf(selected.path, separator)
    val derived = selected.withCopy(path = parentPath, name = fileNameOf(parentPath, separator), isDirectory = true)
    val (parent, children) = listParent(derived)
    val parentId = checkNotNull(parent.storageReference?.id?.takeIf { it.isNotEmpty() }) { "Selected parent identity is unavailable" }
    check(parent.isDirectory && !parent.isSymbolicLink && normalizePathForCompare(parent.path, separator) == normalizePathForCompare(parentPath, separator))
    require(references.all { it.parentId.isEmpty() || it.parentId == parentId }) { "Current source reference leaves the selected parent" }
    val matches = children.filter { it.name == selected.name }
    require(matches.size <= 1) { "Selected source name is ambiguous" }
    val fresh = matches.singleOrNull()
    check(fresh == null || references.any { it.id == fresh.storageReference?.id }) { "Selected source identity changed; select the source again" }
    check(fresh == null || !fresh.isSymbolicLink && fresh.isDirectory == selected.isDirectory &&
        (fresh.storageReference?.parentId.isNullOrEmpty() || fresh.storageReference.parentId == parentId) &&
        normalizePathForCompare(fresh.path, separator) == normalizePathForCompare(selected.path, separator)) {
        "Selected source type or location changed"
    }
    val reference = fresh?.storageReference ?: references.first()
    return selected.copy(storageReference = reference.copy(parentId = parentId)) to fresh
}
