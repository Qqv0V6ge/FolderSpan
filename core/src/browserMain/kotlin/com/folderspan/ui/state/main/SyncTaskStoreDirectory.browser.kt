package com.folderspan.ui.state.main

// Web 端继续使用现有的浏览器私有存储键。
internal actual fun resolveSyncTaskStoreDirectory(legacyDirectory: String): String = legacyDirectory
