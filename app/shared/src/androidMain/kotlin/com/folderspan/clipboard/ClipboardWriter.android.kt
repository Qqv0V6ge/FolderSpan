package com.folderspan.clipboard

import android.annotation.SuppressLint
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import com.folderspan.androidContext

@SuppressLint("NewApi")
actual suspend fun writeClipboardText(text: String): Boolean {
    val context = androidContext()
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
        ?: return false
    clipboard.setPrimaryClip(ClipData.newPlainText("text", text))
    return true
}
