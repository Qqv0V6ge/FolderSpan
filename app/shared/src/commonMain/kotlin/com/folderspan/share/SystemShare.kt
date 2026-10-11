package com.folderspan.share

data class SystemShareItem(
    val path: String,
    val displayName: String,
    val mimeType: String,
    val isDirectory: Boolean,
)

expect fun shareSystemItems(items: List<SystemShareItem>): Boolean
