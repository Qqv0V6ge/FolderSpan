package com.folderspan.service.network

internal fun storageContentFixtureUrl(runtime: Boolean): String? {
    val config = js("globalThis.__karma__ && globalThis.__karma__.config")
    return (if (runtime) config?.storageContentRuntimeFixtureUrl else config?.storageContentTestUrl) as? String
}
