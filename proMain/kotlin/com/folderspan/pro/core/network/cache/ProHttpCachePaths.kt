package com.folderspan.pro.core.network.cache

internal expect fun resolveProHttpCacheRootDirectory(): String

internal expect fun restrictProHttpCachePermissions(directory: String, filePath: String)
