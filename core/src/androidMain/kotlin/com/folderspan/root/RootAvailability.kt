package com.folderspan.root

import java.io.File
import java.util.concurrent.TimeUnit

object RootAvailability {
    private val commonSuPaths = listOf(
        "/system/bin/su",
        "/system/xbin/su",
        "/sbin/su",
        "/su/bin/su",
        "/vendor/bin/su"
    )

    fun hasSuBinary(
        pathExists: (String) -> Boolean = { path -> File(path).exists() },
        commandLookup: () -> String? = ::lookupSuOnPath
    ): Boolean {
        return commonSuPaths.any(pathExists) || commandLookup().orEmpty().trim().isNotBlank()
    }

    private fun lookupSuOnPath(): String? {
        return runCatching {
            val process = Runtime.getRuntime().exec(arrayOf("sh", "-c", "command -v su || which su"))
            if (!process.waitFor(500, TimeUnit.MILLISECONDS)) {
                process.destroy()
                return null
            }
            process.inputStream.bufferedReader().use { reader -> reader.readText() }
        }.getOrNull()
    }
}
