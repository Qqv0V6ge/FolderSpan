@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.folderspan.clipboard

import androidx.compose.runtime.Composable
import com.folderspan.data.file.FileProtocol
import com.folderspan.ui.state.file.ExternalFileResourceLease
import com.folderspan.ui.state.file.ExternalFileResourceLeaseRegistry
import com.folderspan.ui.state.file.ExternalFileSkip
import com.folderspan.ui.state.file.ExternalFileSkipReason
import com.folderspan.ui.state.file.PreparedExternalFileBatch
import com.folderspan.ui.state.file.buildPreparedExternalFileBatch
import com.folderspan.ui.state.file.externalFileStagingRootPath
import com.folderspan.ui.state.file.newExternalFileLeaseId
import com.folderspan.utils.FileAccessPermission
import com.folderspan.utils.FileUtils
import com.folderspan.open.releaseIosPreparedExternalSource
import kotlinx.cinterop.ObjCObjectVar
import kotlinx.cinterop.alloc
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.value
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import platform.Foundation.NSError
import platform.Foundation.NSFileManager
import platform.Foundation.NSURL
import platform.UIKit.UIPasteboard

actual suspend fun readClipboardFileBatch(): PreparedExternalFileBatch {
    val providerResponse = requestIosClipboardFileProviderResponse()
    val fallbackUrls = UIPasteboard.generalPasteboard.URLs.orEmpty()
        .mapNotNull { item -> item as? NSURL }
        .filter { url -> url.isFileURL() }
    return withContext(Dispatchers.Default) {
        prepareIosClipboardFileBatch(providerResponse, fallbackUrls)
    }
}

internal fun prepareIosClipboardFileBatch(
    providerResponse: IosClipboardFileProviderResponse?,
    fallbackUrls: List<NSURL>,
): PreparedExternalFileBatch {
    val urls = providerResponse?.urls
        .orEmpty()
        .ifEmpty { fallbackUrls }
        .distinctBy { url -> url.path }
    val ownsStagedUrls = providerResponse?.ownsStagedUrls == true && providerResponse.urls.isNotEmpty()
    val representedItemCount = providerResponse?.representedItemCount
        ?.takeIf { count -> count > 0 }
        ?: urls.size
    if (urls.isEmpty()) return PreparedExternalFileBatch()

    val leaseId = newExternalFileLeaseId()
    val rootPath = externalFileStagingRootPath(leaseId)
    val fileManager = NSFileManager.defaultManager
    val created = fileManager.createDirectoryAtPath(
        path = rootPath,
        withIntermediateDirectories = true,
        attributes = null,
        error = null,
    )
    if (!created && !fileManager.fileExistsAtPath(rootPath)) {
        if (ownsStagedUrls) {
            urls.forEach { url -> url.path?.let(::releaseIosPreparedExternalSource) }
        }
        return PreparedExternalFileBatch(
            skipped = urls.map { ExternalFileSkip(ExternalFileSkipReason.Unreadable) },
            representedItemCount = representedItemCount,
        )
    }

    val prepared = mutableListOf<com.folderspan.data.file.FileSimpleInfo>()
    val skipped = mutableListOf<ExternalFileSkip>()
    urls.forEach { sourceUrl ->
        val sourcePath = sourceUrl.path?.trim().orEmpty()
        val sourceName = sourceUrl.lastPathComponent
            ?.trim()
            ?.replace('/', '-')
            ?.takeIf { item -> item.isNotEmpty() }
            ?: "clipboard-file"
        val targetPath = reserveIosClipboardTargetPath(fileManager, rootPath, sourceName)
        val accessed = sourceUrl.startAccessingSecurityScopedResource()
        val copied = try {
            memScoped {
                val error = alloc<ObjCObjectVar<NSError?>>()
                error.value = null
                fileManager.copyItemAtPath(sourcePath, targetPath, error.ptr)
            }
        } finally {
            if (accessed) sourceUrl.stopAccessingSecurityScopedResource()
            if (ownsStagedUrls) releaseIosPreparedExternalSource(sourcePath)
        }
        val source = if (copied) {
            FileUtils.getFile(FileAccessPermission.Allowed, targetPath)
                .getOrNull()
                ?.withCopy(protocol = FileProtocol.Local, protocolId = "")
        } else {
            null
        }
        if (source == null) {
            skipped += ExternalFileSkip(
                reason = ExternalFileSkipReason.Unreadable,
                displayName = sourceName,
            )
        } else {
            prepared += source
        }
    }

    if (prepared.isEmpty()) {
        fileManager.removeItemAtPath(rootPath, error = null)
        return PreparedExternalFileBatch(
            skipped = skipped,
            representedItemCount = representedItemCount,
        )
    }
    val lease = ExternalFileResourceLease(leaseId, rootPath)
    ExternalFileResourceLeaseRegistry.register(lease)
    return buildPreparedExternalFileBatch(
        files = prepared,
        skipped = skipped,
        lease = lease,
        representedItemCount = representedItemCount,
    )
}

private fun reserveIosClipboardTargetPath(
    fileManager: NSFileManager,
    rootPath: String,
    sourceName: String,
): String {
    var candidate = "$rootPath/$sourceName"
    if (!fileManager.fileExistsAtPath(candidate)) return candidate
    val dotIndex = sourceName.lastIndexOf('.').takeIf { index -> index > 0 } ?: sourceName.length
    val base = sourceName.substring(0, dotIndex)
    val extension = sourceName.substring(dotIndex)
    var index = 2
    while (fileManager.fileExistsAtPath(candidate)) {
        candidate = "$rootPath/$base($index)$extension"
        index++
    }
    return candidate
}

@Composable
actual fun PlatformClipboardFilePasteEffect(
    enabled: Boolean,
    onPasteRequest: (ClipboardFileBatchReader) -> Unit,
    onTextPasteRequest: (ClipboardContent) -> Unit,
) = Unit
