package com.folderspan.utils

import okio.Path

// 与既有数据库和设备身份目录共用同一个应用支持路径。
internal fun iosApplicationSupportRoot(home: Path): Path = home / "Library" / "Application Support" / "FolderSpan"
