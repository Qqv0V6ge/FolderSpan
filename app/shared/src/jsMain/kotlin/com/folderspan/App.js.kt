package com.folderspan

import kotlinx.browser.window

actual fun openUrl(url: String?) {
    val target = url ?: return
    window.open(target, "_blank")
}
