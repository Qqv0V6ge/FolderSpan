package com.folderspan.pro.core.network.cache

import kotlinx.serialization.json.JsonElement

interface ApiResponseCacheStore {
    suspend fun read(key: ApiCacheKey, namespace: String = DEFAULT_PRO_API_CACHE_NAMESPACE): JsonElement?
    suspend fun write(
        key: ApiCacheKey,
        payload: JsonElement,
        policy: ApiCachePolicy,
    )

    suspend fun remove(key: ApiCacheKey, namespace: String = DEFAULT_PRO_API_CACHE_NAMESPACE)
}
