package com.folderspan.ui.state.main

// Android 和桌面端的现有应用数据目录可直接写入。
internal actual fun resolveSyncTaskStoreDirectory(legacyDirectory: String): String = legacyDirectory
