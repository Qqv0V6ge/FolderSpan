package com.folderspan.clipboard

import platform.Foundation.NSURL
import platform.UIKit.UIPasteboard

actual suspend fun readClipboardContent(): ClipboardContent? {
    val pasteboard = UIPasteboard.generalPasteboard
    val texts = mutableListOf<String>()
    val filePaths = mutableListOf<String>()

    pasteboard.string?.let { value ->
        if (value.isNotBlank()) {
            texts.add(value)
        }
    }

    pasteboard.strings?.forEach { item ->
        val value = item as? String
        if (!value.isNullOrBlank()) {
            texts.add(value)
        }
    }

    val singleUrl = pasteboard.URL
    if (singleUrl != null) {
        val urlString = singleUrl.absoluteString
        if (singleUrl.isFileURL()) {
            val path = singleUrl.path
            if (!path.isNullOrBlank()) {
                filePaths.add(path)
            }
        } else if (!urlString.isNullOrBlank()) {
            texts.add(urlString)
        }
    }

    pasteboard.URLs?.forEach { item ->
        val url = item as? NSURL ?: return@forEach
        val urlString = url.absoluteString
        if (url.isFileURL()) {
            val path = url.path
            if (!path.isNullOrBlank()) {
                filePaths.add(path)
            }
        } else if (!urlString.isNullOrBlank()) {
            texts.add(urlString)
        }
    }

    if (texts.isEmpty() && filePaths.isEmpty()) return null
    return ClipboardContent(texts = texts, filePaths = filePaths)
}
