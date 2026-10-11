package com.folderspan.utils

import java.io.File

internal actual suspend fun isDropDirectoryWritable(path: String): Boolean = File(path).let { it.isDirectory && it.canWrite() }
