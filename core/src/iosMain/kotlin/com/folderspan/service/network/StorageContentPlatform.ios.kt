package com.folderspan.service.network

actual object StorageContentPlatform : StorageContentOperations by StorageContentHostContext()
