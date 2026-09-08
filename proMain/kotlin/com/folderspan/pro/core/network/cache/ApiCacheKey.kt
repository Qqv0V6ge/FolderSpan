package com.folderspan.pro.core.network.cache

import com.folderspan.pro.core.network.RequestSigner

class ApiCacheKey private constructor(
    val method: String,
    val url: String,
    val storageKey: String,
) {
    companion object {
        fun from(
            method: String,
            url: String,
            queryParameters: List<Pair<String, String>> = emptyList(),
            body: ByteArray? = null,
            userScope: String? = null,
        ): ApiCacheKey {
            val normalizedMethod = method.trim().uppercase()
            val normalizedUrl = normalizeUrl(url)
            val canonicalQuery = RequestSigner.canonicalQuery(queryParameters)
            val bodyHash = body?.let(RequestSigner::sha256Hex)
            val userScopeHash = userScope
                ?.trim()
                ?.takeIf { it.isNotEmpty() }
                ?.encodeToByteArray()
                ?.let(RequestSigner::sha256Hex)
            val source = listOf(
                normalizedMethod,
                normalizedUrl,
                canonicalQuery,
                bodyHash.orEmpty(),
                userScopeHash.orEmpty(),
            ).joinToString("\n")

            return ApiCacheKey(
                method = normalizedMethod,
                url = normalizedUrl,
                storageKey = RequestSigner.sha256Hex(source.encodeToByteArray()),
            )
        }

        private fun normalizeUrl(value: String): String {
            val trimmed = value.trim()
            val minimumLength = trimmed.indexOf("://")
                .takeIf { it >= 0 }
                ?.let { it + 3 }
                ?: 0
            var end = trimmed.length
            while (end > minimumLength && trimmed[end - 1] == '/') {
                end--
            }
            return trimmed.substring(0, end)
        }
    }
}
