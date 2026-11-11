package com.folderspan.service.network

import kotlin.js.ExperimentalWasmJsInterop

internal fun storageContentFixtureUrl(runtime: Boolean): String? = fixtureUrl(runtime)

@OptIn(ExperimentalWasmJsInterop::class)
@JsFun("(runtime) => globalThis.__karma__?.config?.[runtime ? 'storageContentRuntimeFixtureUrl' : 'storageContentTestUrl'] ?? null")
private external fun fixtureUrl(runtime: Boolean): String?
