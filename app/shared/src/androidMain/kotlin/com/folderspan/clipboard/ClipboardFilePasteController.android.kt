package com.folderspan.clipboard

import android.content.ClipboardManager
import android.content.Context
import androidx.compose.runtime.Composable
import com.folderspan.androidContext
import com.folderspan.ui.state.file.PreparedExternalFileBatch
import com.folderspan.utils.ShareHandler
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

actual suspend fun readClipboardFileBatch(): PreparedExternalFileBatch {
    val context = androidContext()
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
        ?: return PreparedExternalFileBatch()
    val clipData = clipboard.primaryClip ?: return PreparedExternalFileBatch()
    val uris = buildList {
        repeat(clipData.itemCount) { index ->
            clipData.getItemAt(index).uri
                ?.takeUnless { it.scheme.equals("http", true) || it.scheme.equals("https", true) }
                ?.let(::add)
        }
    }
    if (uris.isEmpty()) return PreparedExternalFileBatch()
    return withContext(Dispatchers.IO) {
        ShareHandler.prepareClipboardFiles(context, uris)
    }
}

@Composable
actual fun PlatformClipboardFilePasteEffect(
    enabled: Boolean,
    onPasteRequest: (ClipboardFileBatchReader) -> Unit,
    onTextPasteRequest: (ClipboardContent) -> Unit,
) = Unit
