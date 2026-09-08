package com.folderspan.share

import android.annotation.SuppressLint
import android.content.Intent
import android.net.Uri
import android.webkit.MimeTypeMap
import androidx.core.content.FileProvider
import androidx.core.net.toUri
import com.folderspan.androidContext
import com.folderspan.utils.FileAccessPermission
import com.folderspan.utils.PathUtils
import com.folderspan.utils.SensitiveFileAccessPolicy
import java.io.File
import java.util.Locale

@SuppressLint("NewApi")
actual fun shareSystemItems(items: List<SystemShareItem>): Boolean {
    if (items.isEmpty()) return false

    val context = androidContext()
    val uris = ArrayList<Uri>(items.size)
    val mimeTypes = LinkedHashSet<String>()

    for ((path1, _, mimeType) in items) {
        val path = path1.trim()
        if (path.isBlank()) continue
        val uri = if (path.startsWith("content://")) {
            path.toUri()
        } else {
            if (PathUtils.isSymbolicLink(FileAccessPermission.Allowed, path)) continue
            if (SensitiveFileAccessPolicy.deniedExceptionForFileProvider(path) != null) continue
            val file = File(path)
            if (!file.exists()) continue
            FileProvider.getUriForFile(context, "com.folderspan.provider", file)
        }
        uris.add(uri)

        val resolvedMime = run {
            val rawMime = mimeType.trim()
            val file = File(path)
            when {
                rawMime.contains("/") -> rawMime
                rawMime.startsWith(".") -> MimeTypeMap.getSingleton()
                    .getMimeTypeFromExtension(rawMime.drop(1).lowercase(Locale.ROOT))
                rawMime.isNotBlank() -> MimeTypeMap.getSingleton()
                    .getMimeTypeFromExtension(rawMime.lowercase(Locale.ROOT))
                file.isFile -> MimeTypeMap.getSingleton()
                    .getMimeTypeFromExtension(file.extension.lowercase(Locale.ROOT))
                else -> null
            }
        }
        if (!resolvedMime.isNullOrBlank()) {
            mimeTypes.add(resolvedMime)
        }
    }

    if (uris.isEmpty()) return false

    val intent = Intent(
        if (uris.size == 1) Intent.ACTION_SEND else Intent.ACTION_SEND_MULTIPLE
    ).apply {
        type = if (mimeTypes.size == 1) mimeTypes.first() else "*/*"
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        if (uris.size == 1) {
            putExtra(Intent.EXTRA_STREAM, uris.first())
        } else {
            putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(uris))
        }
    }

    val chooser = Intent.createChooser(intent, null).apply {
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }

    return runCatching { context.startActivity(chooser) }.isSuccess
}
