package com.folderspan.utils

data class PlatformDirectoryItem(
    val title: String,
    val description: String,
    val directory: String,
    val requestAccess: (suspend () -> Result<String?>)? = null,
)

expect fun getPlatformDirectories(): List<PlatformDirectoryItem>

