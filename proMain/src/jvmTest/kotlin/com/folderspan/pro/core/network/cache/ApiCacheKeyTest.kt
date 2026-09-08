package com.folderspan.pro.core.network.cache

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

class ApiCacheKeyTest {
    @Test
    fun sameQueryParametersInDifferentOrderProduceSameStorageKey() {
        val first = ApiCacheKey.from(
            method = "GET",
            url = "https://example.test/api/plugins",
            queryParameters = listOf("keyword" to "zip", "page" to "1"),
            userScope = "user-a",
        )
        val second = ApiCacheKey.from(
            method = "get",
            url = "https://example.test/api/plugins",
            queryParameters = listOf("page" to "1", "keyword" to "zip"),
            userScope = "user-a",
        )

        assertEquals(first.storageKey, second.storageKey)
    }

    @Test
    fun differentQueryParameterValuesProduceDifferentStorageKeys() {
        val first = ApiCacheKey.from(
            method = "GET",
            url = "https://example.test/api/plugins",
            queryParameters = listOf("page" to "1"),
        )
        val second = ApiCacheKey.from(
            method = "GET",
            url = "https://example.test/api/plugins",
            queryParameters = listOf("page" to "2"),
        )

        assertNotEquals(first.storageKey, second.storageKey)
    }

    @Test
    fun differentRequestBodiesProduceDifferentStorageKeys() {
        val first = ApiCacheKey.from(
            method = "POST",
            url = "https://example.test/api/orders",
            body = """{"pluginId":"a"}""".encodeToByteArray(),
        )
        val second = ApiCacheKey.from(
            method = "POST",
            url = "https://example.test/api/orders",
            body = """{"pluginId":"b"}""".encodeToByteArray(),
        )

        assertNotEquals(first.storageKey, second.storageKey)
    }

    @Test
    fun differentUserScopesProduceDifferentStorageKeys() {
        val first = ApiCacheKey.from(
            method = "GET",
            url = "https://example.test/api/user/profile",
            userScope = "token-a",
        )
        val second = ApiCacheKey.from(
            method = "GET",
            url = "https://example.test/api/user/profile",
            userScope = "token-b",
        )

        assertNotEquals(first.storageKey, second.storageKey)
    }
}
