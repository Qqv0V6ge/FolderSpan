package com.folderspan.service.network

import com.russhwolf.settings.Settings
import okio.Path.Companion.toPath
import java.nio.file.Path

/** Keep JVM/Android callers using their existing platform Path without changing persisted paths. */
internal fun StorageContentHostContext(
    keys: StorageContentKeyStore = StorageContentKeyStore(),
    settings: Settings = createStorageContentSettings(),
    cacheRoot: Path,
    providerFactory: (NetworkConnectionConfig) -> StorageProvider = NetworkClientFactory::create,
): StorageContentHostContext = StorageContentHostContext(keys, settings, cacheRoot.toString().toPath(), providerFactory)
