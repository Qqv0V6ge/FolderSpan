package com.folderspan.utils

import com.folderspan.service.webui.WebUiPlatformClient
import com.folderspan.service.webui.encodeWebUiQuery

internal actual suspend fun isDropDirectoryWritable(path: String): Boolean =
    WebUiPlatformClient.get("/files/directory-writable?path=${encodeWebUiQuery(path)}")
