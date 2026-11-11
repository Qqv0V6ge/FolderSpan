@file:OptIn(ExperimentalWasmJsInterop::class)

package com.folderspan.utils

import kotlin.js.ExperimentalWasmJsInterop
import kotlin.js.JsModule
import kotlinx.datetime.TimeZone

@JsModule("@js-joda/timezone")
external object JsJodaTimeZoneModule

private val jsJodaTz = JsJodaTimeZoneModule

@JsFun("() => Intl.DateTimeFormat().resolvedOptions().timeZone")
private external fun currentBrowserTimeZoneId(): String

actual fun currentSyncTimeZone(): TimeZone = TimeZone.of(currentBrowserTimeZoneId())
