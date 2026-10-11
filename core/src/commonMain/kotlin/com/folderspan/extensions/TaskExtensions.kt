package com.folderspan.extensions

import com.folderspan.ui.state.main.Task

fun Task.fileOperationKey(): String? {
    val path = values["path"]?.takeIf { item -> item.isNotBlank() } ?: return null
    return "${protocol.name}:$protocolId:$path"
}
