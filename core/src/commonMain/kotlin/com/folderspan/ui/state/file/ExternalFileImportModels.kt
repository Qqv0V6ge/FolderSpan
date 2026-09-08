package com.folderspan.ui.state.file

import com.folderspan.data.file.FileProtocol
import com.folderspan.data.file.FileSimpleInfo
import com.folderspan.data.main.DiskBase
import com.folderspan.utils.FileAccessPermission
import com.folderspan.utils.FileUtils
import com.folderspan.utils.PathUtils
import kotlin.random.Random
import kotlin.time.Clock

const val EXTERNAL_FILE_LEASE_ID_TASK_VALUE = "externalFileLeaseId"
const val EXTERNAL_FILE_LEASE_ROOT_TASK_VALUE = "externalFileLeaseRoot"

enum class ExternalFileSkipReason {
    Duplicate,
    Unsupported,
    Unreadable,
    InvalidName,
}

data class ExternalFileSkip(
    val reason: ExternalFileSkipReason,
    val displayName: String = "",
)

data class ExternalFileDedupeKey(
    val protocol: FileProtocol,
    val protocolId: String,
    val path: String,
    val size: Long,
    val updatedDate: Long,
)

data class ExternalFileResourceLease(
    val id: String,
    val rootPath: String? = null,
)

data class PreparedExternalFileBatch(
    val files: List<FileSimpleInfo> = emptyList(),
    val skipped: List<ExternalFileSkip> = emptyList(),
    val lease: ExternalFileResourceLease? = null,
    val representedItemCount: Int = files.size + skipped.size,
    val dedupeKeys: Set<ExternalFileDedupeKey> = files.map(::externalFileDedupeKey).toSet(),
)

data class ExternalFileImportTarget(
    val desk: DiskBase,
    val destination: FileSimpleInfo,
    val capturedPath: String,
)

data class ExternalFileImportTargetCapture(
    val target: ExternalFileImportTarget? = null,
    val failure: ExternalFileImportFailure? = null,
)

enum class ExternalFileImportFailure {
    PermissionDenied,
    TargetUnavailable,
    NoFiles,
    AllSkipped,
    ReadFailed,
}

internal class ExternalFileTargetUnavailableException : Exception()

data class ExternalFileImportResult(
    val taskKeys: List<Long> = emptyList(),
    val acceptedCount: Int = 0,
    val skipped: List<ExternalFileSkip> = emptyList(),
    val failure: ExternalFileImportFailure? = null,
) {
    val isSuccess: Boolean
        get() = failure == null && taskKeys.isNotEmpty()
}

fun externalFileDedupeKey(file: FileSimpleInfo): ExternalFileDedupeKey = ExternalFileDedupeKey(
    protocol = file.protocol,
    protocolId = file.protocolId,
    path = file.path,
    size = file.size,
    updatedDate = file.updatedDate,
)

fun buildPreparedExternalFileBatch(
    files: List<FileSimpleInfo>,
    skipped: List<ExternalFileSkip> = emptyList(),
    lease: ExternalFileResourceLease? = null,
    representedItemCount: Int = files.size + skipped.size,
): PreparedExternalFileBatch {
    val uniqueFiles = LinkedHashMap<ExternalFileDedupeKey, FileSimpleInfo>()
    val duplicateSkips = mutableListOf<ExternalFileSkip>()
    files.forEach { file ->
        val key = externalFileDedupeKey(file)
        if (uniqueFiles.containsKey(key)) {
            duplicateSkips += ExternalFileSkip(
                reason = ExternalFileSkipReason.Duplicate,
                displayName = file.name,
            )
        } else {
            uniqueFiles[key] = file
        }
    }
    return PreparedExternalFileBatch(
        files = uniqueFiles.values.toList(),
        skipped = skipped + duplicateSkips,
        lease = lease,
        representedItemCount = representedItemCount.coerceAtLeast(uniqueFiles.size + skipped.size + duplicateSkips.size),
        dedupeKeys = uniqueFiles.keys,
    )
}

fun newExternalFileLeaseId(): String = buildString {
    append(Clock.System.now().toEpochMilliseconds())
    append('-')
    append(Random.nextInt().toUInt().toString(16))
}

fun clipboardImageFileExtension(mimeType: String?): String? = when (
    mimeType?.substringBefore(';')?.trim()?.lowercase()
) {
    null, "", "image/png" -> "png"
    "image/jpeg", "image/jpg" -> "jpg"
    "image/gif" -> "gif"
    "image/webp" -> "webp"
    "image/bmp", "image/x-ms-bmp" -> "bmp"
    "image/tiff" -> "tiff"
    "image/heic", "image/heif" -> "heic"
    "image/avif" -> "avif"
    "image/svg+xml" -> "svg"
    else -> null
}

fun clipboardImageFileName(
    epochMillis: Long,
    itemIndex: Int = 0,
    mimeType: String? = "image/png",
): String? {
    val extension = clipboardImageFileExtension(mimeType) ?: return null
    val suffix = if (itemIndex > 0) "-${itemIndex + 1}" else ""
    return "clipboard-image-${epochMillis.coerceAtLeast(0L)}$suffix.$extension"
}

fun externalFileStagingRootPath(leaseId: String): String {
    val separator = PathUtils.getPathSeparator()
    val cacheRoot = PathUtils.getCachePath().trimEnd('/', '\\')
    return listOf(cacheRoot, "clipboard-file-import", leaseId)
        .filter { item -> item.isNotBlank() }
        .joinToString(separator)
}

internal fun cleanupExternalFileStagingRoot(rootPath: String) {
    val normalizedRoot = rootPath.trim()
    if (normalizedRoot.isEmpty()) return
    val stagingRoot = externalFileStagingRootPath("").trimEnd('/', '\\')
    if (
        stagingRoot.isEmpty() ||
        normalizedRoot == stagingRoot ||
        (!normalizedRoot.startsWith("$stagingRoot/") && !normalizedRoot.startsWith("$stagingRoot\\"))
    ) {
        return
    }
    runCatching {
        PathUtils.deleteDirectory(FileAccessPermission.Allowed, normalizedRoot)
    }
}

internal fun reconcileExternalFileStagingRoots(activeRoots: Set<String>) {
    val stagingRoot = externalFileStagingRootPath("").trimEnd('/', '\\')
    if (stagingRoot.isEmpty()) return
    val normalizedActiveRoots = activeRoots.map { item -> item.trimEnd('/', '\\') }.toSet()
    PathUtils.getFileAndFolder(FileAccessPermission.Allowed, stagingRoot)
        .getOrDefault(emptyList())
        .asSequence()
        .filter { item -> item.isDirectory }
        .filterNot { item -> item.path.trimEnd('/', '\\') in normalizedActiveRoots }
        .forEach { item -> cleanupExternalFileStagingRoot(item.path) }
}

internal fun ensureExternalFileStagingRoot(rootPath: String): Result<Boolean> =
    FileUtils.createFolder(FileAccessPermission.Allowed, rootPath)
