package com.folderspan.service.http.clipboard

actual fun createPlatformClipboardDownloadStagingFactory(): ClipboardDownloadStagingFactory =
    WebClipboardDownloadStagingFactory()
