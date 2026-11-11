package com.folderspan.service

internal fun withBrowserTimeZoneForTest(id: String, block: () -> Unit) {
    val intl = js("Intl")
    val original = intl.DateTimeFormat
    val factory = js("(function (zone) { return function () { return { resolvedOptions: function () { return { timeZone: zone }; } }; }; })")
    intl.DateTimeFormat = factory(id)
    try { block() } finally { intl.DateTimeFormat = original }
}
