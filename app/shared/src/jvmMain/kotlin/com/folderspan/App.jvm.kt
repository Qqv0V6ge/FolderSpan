package com.folderspan

import java.awt.Desktop
import java.net.URI

actual fun openUrl(url: String?) {
    val uri = url?.let { item ->  URI.create(item) } ?: return
    Desktop.getDesktop().browse(uri)
}
