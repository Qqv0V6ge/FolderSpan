package com.folderspan.utils

import com.folderspan.data.file.FileProtocol
import com.folderspan.ui.state.file.ExternalFileSkip
import com.folderspan.ui.state.file.ExternalFileSkipReason
import com.folderspan.ui.state.file.PreparedExternalFileBatch
import com.folderspan.ui.state.file.buildPreparedExternalFileBatch
import java.io.File

fun prepareDesktopExternalFiles(files: List<File>): PreparedExternalFileBatch {
    val prepared = mutableListOf<com.folderspan.data.file.FileSimpleInfo>()
    val skipped = mutableListOf<ExternalFileSkip>()
    files.forEach { file ->
        val absolutePath = file.absolutePath
        if (
            SensitiveFileAccessPolicy.deniedException(absolutePath) != null ||
            PathUtils.isSymbolicLink(FileAccessPermission.Allowed, absolutePath)
        ) {
            skipped += ExternalFileSkip(
                reason = ExternalFileSkipReason.Unsupported,
                displayName = file.name,
            )
            return@forEach
        }
        val source = FileUtils.getFile(FileAccessPermission.Allowed, absolutePath)
            .getOrNull()
            ?.withCopy(protocol = FileProtocol.Local, protocolId = "")
        if (source == null) {
            skipped += ExternalFileSkip(
                reason = ExternalFileSkipReason.Unreadable,
                displayName = file.name,
            )
        } else {
            prepared += source
        }
    }
    return buildPreparedExternalFileBatch(
        files = prepared,
        skipped = skipped,
        representedItemCount = files.size,
    )
}
