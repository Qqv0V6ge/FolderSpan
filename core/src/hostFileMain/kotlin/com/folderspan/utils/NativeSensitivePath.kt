package com.folderspan.utils

import com.folderspan.exception.AuthorityException

internal fun nativeSensitivePathDeniedException(path: String): AuthorityException? =
    SensitiveFileAccessPolicy.lexicalDeniedException(path)
        ?: NativePathUtils.resolveCanonicalPath(FileAccessPermission.Allowed, path, true)
            ?.let(SensitiveFileAccessPolicy::lexicalDeniedException)
