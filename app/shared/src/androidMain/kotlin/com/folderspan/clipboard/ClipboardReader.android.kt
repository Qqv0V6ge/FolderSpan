@file:Suppress("DEPRECATION")

package com.folderspan.clipboard

import android.annotation.SuppressLint
import android.content.ClipboardManager
import android.content.Context
import com.folderspan.androidContext

@SuppressLint("NewApi")
actual suspend fun readClipboardContent(): ClipboardContent? {
    val context = androidContext()
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
        ?: return null
    val clipData = clipboard.primaryClip ?: return null
    if (clipData.itemCount <= 0) return null

    val texts = mutableListOf<String>()
    val filePaths = mutableListOf<String>()

    for (index in 0 until clipData.itemCount) {
        val item = clipData.getItemAt(index)
        val uri = item.uri
        if (uri != null) {
            if (uri.scheme.equals("file", ignoreCase = true)) {
                val path = uri.path
                if (!path.isNullOrBlank()) {
                    filePaths.add(path)
                } else {
                    texts.add(uri.toString())
                }
            } else {
                texts.add(uri.toString())
            }
        }

        val text = item.text?.toString()
        if (!text.isNullOrBlank()) {
            texts.add(text)
        }
    }

    if (texts.isEmpty() && filePaths.isEmpty()) return null
    return ClipboardContent(texts = texts, filePaths = filePaths)
}
