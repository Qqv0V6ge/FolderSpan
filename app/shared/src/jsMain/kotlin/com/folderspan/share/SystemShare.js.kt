package com.folderspan.share

import strings.AppStrings
import kotlinx.browser.window

actual fun shareSystemItems(items: List<SystemShareItem>): Boolean {
    val payload = items.mapNotNull { item ->
        val value = item.path.takeIf { item ->  item.isNotBlank() } ?: item.displayName.takeIf { item ->  item.isNotBlank() }
        value
    }
    if (payload.isEmpty()) return false

    val isSupported = (js("typeof navigator.share === 'function'") as Boolean)
    if (!isSupported) return false

    val data = js("{}")
    data.title = AppStrings.app_name
    data.text = payload.joinToString("\n")

    return try {
        window.navigator.asDynamic().share(data)
        true
    } catch (e: dynamic) {
        false
    }
}
