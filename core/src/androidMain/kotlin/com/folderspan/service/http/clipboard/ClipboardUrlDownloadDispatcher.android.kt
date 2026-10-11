package com.folderspan.service.http.clipboard

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers

actual val clipboardUrlDownloadWorkerDispatcher: CoroutineDispatcher = Dispatchers.IO
