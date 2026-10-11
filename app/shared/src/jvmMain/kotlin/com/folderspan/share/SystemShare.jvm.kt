package com.folderspan.share

import java.awt.Desktop
import java.io.File

actual fun shareSystemItems(items: List<SystemShareItem>): Boolean {
    if (items.isEmpty()) return false
    if (!Desktop.isDesktopSupported()) return false
    val desktop = Desktop.getDesktop()
    if (!desktop.isSupported(Desktop.Action.OPEN)) return false

    var opened = false
    for ((path1) in items) {
        val path = path1.trim()
        if (path.isBlank()) continue
        val file = File(path)
        if (!file.exists()) continue
        if (runCatching { desktop.open(file) }.isSuccess) {
            opened = true
        }
    }
    return opened
}
