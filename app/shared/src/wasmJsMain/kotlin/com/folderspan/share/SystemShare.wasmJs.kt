@file:OptIn(ExperimentalWasmJsInterop::class)

package com.folderspan.share

actual fun shareSystemItems(items: List<SystemShareItem>): Boolean {
    val payload = items.mapNotNull { item ->
        item.path.takeIf { item ->  item.isNotBlank() } ?: item.displayName.takeIf { item ->  item.isNotBlank() }
    }
    if (payload.isEmpty()) return false
    if (!isWebShareSupported()) return false

    val data = createShareData("FolderSpan", payload.joinToString("\n"))
    return try {
        shareWithNavigator(data)
        true
    } catch (_: Throwable) {
        false
    }
}

@JsFun("() => typeof navigator.share === 'function'")
private external fun isWebShareSupported(): Boolean

@JsFun("(title, text) => ({ title: title, text: text })")
private external fun createShareData(title: String, text: String): JsAny

@JsFun("(data) => navigator.share(data)")
private external fun shareWithNavigator(data: JsAny)
