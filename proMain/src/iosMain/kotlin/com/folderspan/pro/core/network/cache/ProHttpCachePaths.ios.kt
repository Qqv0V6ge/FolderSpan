package com.folderspan.pro.core.network.cache

import com.folderspan.utils.PathUtils

internal actual fun resolveProHttpCacheRootDirectory(): String {
    val separator = PathUtils.getPathSeparator().ifBlank { "/" }
    return PathUtils.getCachePath().trimEnd('/', '\\') + separator + "pro_http_cache"
}

internal actual fun restrictProHttpCachePermissions(directory: String, filePath: String) = Unit
