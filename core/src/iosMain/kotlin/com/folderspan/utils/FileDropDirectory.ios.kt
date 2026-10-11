package com.folderspan.utils

import platform.Foundation.NSFileManager

internal actual suspend fun isDropDirectoryWritable(path: String): Boolean = NSFileManager.defaultManager.isWritableFileAtPath(path)
