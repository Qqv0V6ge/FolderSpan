package com.folderspan.service.webui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.folderspan.data.file.PathInfo

object WebUiAttachment {
    var mode by mutableStateOf(WebUiMode.Native)
    var showConnectionDialog by mutableStateOf(false)
    var target: WebUiRuntimeAddress? = null
    var connected by mutableStateOf(false)
    var hasSession by mutableStateOf(false)
    var stale by mutableStateOf(false)
    var plaintextHttp = false
    var pathSeparator = "/"
    var homePath = "/"
    var cachePath = "/tmp"
    var rootPaths: List<PathInfo> = emptyList()
    var hostInfo: WebUiHostInfo? = null
    var sessionToken = ""
    val isAttached get() = mode == WebUiMode.Attached

    fun applyHostInfo(host: WebUiHostInfo) {
        hostInfo = host
        pathSeparator = host.separator
        homePath = host.home
        cachePath = host.cache
        rootPaths = host.roots
    }

    fun reset() {
        mode = WebUiMode.Native
        showConnectionDialog = false
        target = null
        connected = false
        hasSession = false
        stale = false
        plaintextHttp = false
        pathSeparator = "/"
        homePath = "/"
        cachePath = "/tmp"
        rootPaths = emptyList()
        sessionToken = ""
        hostInfo = null
    }
}

fun joinWebUiPath(parent: String, child: String, separator: String = WebUiAttachment.pathSeparator): String {
    val sep = separator.ifBlank { "/" }
    val trimmedChild = child.trim().trimStart('/', '\\')
    if (child.startsWith("/") || (sep == "\\" && child.length >= 2 && child[1] == ':')) {
        return child
    }
    if (parent.isBlank() || parent == sep) {
        return if (sep == "\\") child else "$sep$trimmedChild".replace("//", "/")
    }
    return if (parent.endsWith('/') || parent.endsWith('\\') || parent.endsWith(sep)) {
        parent + trimmedChild
    } else {
        parent + sep + trimmedChild
    }
}

fun joinWebUiRelative(directory: String, relativePath: String, separator: String = WebUiAttachment.pathSeparator): String {
    val sep = separator.ifBlank { "/" }
    val normalized = relativePath.replace('/', sep.first()).replace('\\', sep.first())
    return joinWebUiPath(directory, normalized, sep)
}
