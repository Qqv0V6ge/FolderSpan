package com.folderspan.extensions

import com.eygraber.uri.Uri

actual fun String.parsePath(): List<String>  = Uri.parse(this).pathSegments