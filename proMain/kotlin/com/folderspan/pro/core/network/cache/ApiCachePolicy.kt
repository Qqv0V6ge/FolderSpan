package com.folderspan.pro.core.network.cache

import kotlin.time.Duration

data class ApiCachePolicy(
    val ttl: Duration,
    val namespace: String = DEFAULT_PRO_API_CACHE_NAMESPACE,
    val allowFallback: Boolean = true,
) {
    init {
        require(ttl.isPositive()) { "Cache ttl must be positive." }
        require(namespace.isNotBlank()) { "Cache namespace must not be blank." }
    }
}

const val DEFAULT_PRO_API_CACHE_NAMESPACE = "pro-api"
