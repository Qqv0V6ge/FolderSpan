package com.folderspan

import platform.Foundation.NSURL
import platform.UIKit.UIApplication

actual fun openUrl(url: String?) {
    val target = url?.trim().orEmpty()
    if (target.isEmpty()) return
    val nsUrl = NSURL.URLWithString(target) ?: return
    UIApplication.sharedApplication.openURL(nsUrl)
}
