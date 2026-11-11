package com.folderspan.utils

import kotlinx.datetime.TimeZone

@JsModule("@js-joda/timezone")
@JsNonModule
external object JsJodaTimeZoneModule

// Export keeps the timezone provider's registration in the linked browser module.
@OptIn(ExperimentalJsExport::class)
@JsExport
val jsJodaTz = JsJodaTimeZoneModule

actual fun currentSyncTimeZone(): TimeZone =
    TimeZone.of(js("Intl.DateTimeFormat().resolvedOptions().timeZone") as String)
