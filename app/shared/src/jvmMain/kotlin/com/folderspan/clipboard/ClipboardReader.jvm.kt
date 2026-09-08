package com.folderspan.clipboard

import java.awt.Toolkit
import java.awt.datatransfer.DataFlavor
import java.io.File

actual suspend fun readClipboardContent(): ClipboardContent? {
    val clipboard = runCatching { Toolkit.getDefaultToolkit().systemClipboard }.getOrNull()
        ?: return null
    val transferable = runCatching { clipboard.getContents(null) }.getOrNull() ?: return null

    val texts = mutableListOf<String>()
    val filePaths = mutableListOf<String>()

    if (transferable.isDataFlavorSupported(DataFlavor.javaFileListFlavor)) {
        runCatching {
            val data = transferable.getTransferData(DataFlavor.javaFileListFlavor) as? List<*>
            data?.forEach { item ->
                val path = (item as? File)?.path
                if (!path.isNullOrBlank()) {
                    filePaths.add(path)
                }
            }
        }
    }

    if (transferable.isDataFlavorSupported(DataFlavor.stringFlavor)) {
        runCatching {
            val text = transferable.getTransferData(DataFlavor.stringFlavor) as? String
            if (!text.isNullOrBlank()) {
                texts.add(text)
            }
        }
    }

    if (texts.isEmpty() && filePaths.isEmpty()) return null
    return ClipboardContent(texts = texts, filePaths = filePaths)
}
