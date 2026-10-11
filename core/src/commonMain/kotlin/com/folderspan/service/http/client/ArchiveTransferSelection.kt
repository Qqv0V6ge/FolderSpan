package com.folderspan.service.http.client

internal fun shouldUseShareArchiveTransfer(encryptedHttpTransport: Boolean): Boolean =
    !encryptedHttpTransport
