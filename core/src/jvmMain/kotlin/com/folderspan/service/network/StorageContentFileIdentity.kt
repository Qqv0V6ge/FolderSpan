package com.folderspan.service.network

import com.folderspan.utils.WindowsFileIdentity
import com.sun.jna.Platform
import java.nio.file.Path

internal actual fun platformStorageContentFileIdentity(path: Path): String? =
    if (Platform.isWindows()) WindowsFileIdentity.read(path) else null
