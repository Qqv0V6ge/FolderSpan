@file:OptIn(ExperimentalWasmJsInterop::class)

package com.folderspan.service

import kotlin.js.ExperimentalWasmJsInterop
import kotlin.js.JsAny

@JsFun("(id) => { const original = Intl.DateTimeFormat; Intl.DateTimeFormat = () => ({ resolvedOptions: () => ({ timeZone: id }) }); return original; }")
private external fun replaceBrowserTimeZone(id: String): JsAny

@JsFun("(original) => { Intl.DateTimeFormat = original; }")
private external fun restoreBrowserTimeZone(original: JsAny)

internal fun withBrowserTimeZoneForTest(id: String, block: () -> Unit) {
    val original = replaceBrowserTimeZone(id)
    try { block() } finally { restoreBrowserTimeZone(original) }
}
