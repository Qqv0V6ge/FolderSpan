package com.folderspan.service.http.clipboard

import okio.FileSystem

actual fun createPlatformClipboardDownloadStagingFactory(): ClipboardDownloadStagingFactory =
    OkioClipboardDownloadStagingFactory(FileSystem.SYSTEM)
