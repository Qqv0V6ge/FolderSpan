package com.folderspan.utils

data class PlatformDirectoryItem(
    val title: String,
    val description: String,
    val directory: String
)

expect fun getPlatformDirectories(): List<PlatformDirectoryItem>

