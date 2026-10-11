@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class, kotlinx.cinterop.BetaInteropApi::class)

package com.folderspan.utils

import kotlinx.cinterop.ObjCObjectVar
import kotlinx.cinterop.alloc
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import platform.Foundation.NSFileManager
import platform.Foundation.NSFileProtectionCompleteUntilFirstUserAuthentication
import platform.Foundation.NSFileProtectionKey
import platform.Foundation.NSNumber
import platform.Foundation.NSURL
import platform.Foundation.NSURLIsExcludedFromBackupKey
import platform.Foundation.NSError

fun protectIosPrivatePath(path: String) {
    if (path.isBlank()) return
    excludePathFromBackup(path)
    applyCompleteUntilFirstAuthProtection(path)
}

private fun excludePathFromBackup(path: String) {
    val url = NSURL.fileURLWithPath(path)
    memScoped {
        val error = alloc<ObjCObjectVar<NSError?>>()
        url.setResourceValue(
            NSNumber(bool = true),
            forKey = NSURLIsExcludedFromBackupKey,
            error = error.ptr,
        )
    }
}

private fun applyCompleteUntilFirstAuthProtection(path: String) {
    memScoped {
        val error = alloc<ObjCObjectVar<NSError?>>()
        NSFileManager.defaultManager.setAttributes(
            mapOf(NSFileProtectionKey to NSFileProtectionCompleteUntilFirstUserAuthentication),
            ofItemAtPath = path,
            error = error.ptr,
        )
    }
}
