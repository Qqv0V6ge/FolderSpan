package com.folderspan.pro.core.network.cache

import com.folderspan.pro.core.network.defaultJson
import com.folderspan.utils.FileUtils
import com.folderspan.utils.PathUtils
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlin.time.Clock

class FileApiResponseCacheStore(
    private val rootDirectory: String = defaultRootDirectory(),
    private val nowMillis: () -> Long = { Clock.System.now().toEpochMilliseconds() },
    private val crypto: ProApiCacheCrypto = ProApiCacheCrypto(),
) : ApiResponseCacheStore {
    override suspend fun read(key: ApiCacheKey, namespace: String): JsonElement? {
        val path = cacheFilePath(namespace, key)
        val bytes = FileUtils.readFile(com.folderspan.utils.FileAccessPermission.Allowed, path).getOrNull() ?: return null
        val encryptedPayload = runCatching {
            defaultJson.decodeFromString(EncryptedCacheFile.serializer(), bytes.decodeToString())
        }.getOrElse {
            remove(key, namespace)
            return null
        }
        if (encryptedPayload.version != ProApiCacheCrypto.CACHE_FORMAT_VERSION) {
            remove(key, namespace)
            return null
        }
        val decrypted = crypto.decrypt(
            payload = EncryptedApiCachePayload(
                algorithm = encryptedPayload.algorithm,
                nonce = encryptedPayload.nonce,
                cipherText = encryptedPayload.cipherText,
                tag = encryptedPayload.tag,
            ),
            namespace = namespace,
            key = key,
        ) ?: run {
            remove(key, namespace)
            return null
        }
        val entry = runCatching {
            defaultJson.decodeFromString(CacheEntry.serializer(), decrypted.decodeToString())
        }.getOrElse {
            remove(key, namespace)
            return null
        }
        if (entry.expiresAtMillis <= nowMillis()) {
            remove(key, namespace)
            return null
        }
        return entry.payloadJson
    }

    override suspend fun write(
        key: ApiCacheKey,
        payload: JsonElement,
        policy: ApiCachePolicy,
    ) {
        val now = nowMillis()
        val entry = CacheEntry(
            createdAtMillis = now,
            expiresAtMillis = now + policy.ttl.inWholeMilliseconds,
            payloadJson = payload,
        )
        val directory = namespaceDirectory(policy.namespace)
        PathUtils.createDirectoryIfNotExists(com.folderspan.utils.FileAccessPermission.Allowed, directory)
        val path = cacheFilePath(policy.namespace, key)
        restrictProHttpCachePermissions(directory, path)
        val plainText = defaultJson.encodeToString(CacheEntry.serializer(), entry).encodeToByteArray()
        val encrypted = crypto.encrypt(
            plainText = plainText,
            namespace = policy.namespace,
            key = key,
        )
        val cacheFile = EncryptedCacheFile(
            version = ProApiCacheCrypto.CACHE_FORMAT_VERSION,
            algorithm = encrypted.algorithm,
            nonce = encrypted.nonce,
            cipherText = encrypted.cipherText,
            tag = encrypted.tag,
        )
        val bytes = defaultJson.encodeToString(EncryptedCacheFile.serializer(), cacheFile).encodeToByteArray()
        FileUtils.writeBytes(com.folderspan.utils.FileAccessPermission.Allowed, path, bytes.size.toLong(), bytes, 0)
        restrictProHttpCachePermissions(directory, path)
    }

    override suspend fun remove(key: ApiCacheKey, namespace: String) {
        val path = cacheFilePath(namespace, key)
        if (PathUtils.exists(com.folderspan.utils.FileAccessPermission.Allowed, path)) {
            FileUtils.deleteFile(com.folderspan.utils.FileAccessPermission.Allowed, path)
        }
    }

    private fun cacheFilePath(namespace: String, key: ApiCacheKey): String =
        joinPath(namespaceDirectory(namespace), "${key.storageKey}.json")

    private fun namespaceDirectory(namespace: String): String =
        joinPath(rootDirectory, namespace.safePathSegment())

    private fun joinPath(parent: String, child: String): String {
        val separator = PathUtils.getPathSeparator().ifBlank { "/" }
        return parent.trimEnd('/', '\\') + separator + child.trimStart('/', '\\')
    }

    private fun String.safePathSegment(): String =
        trim()
            .map { char ->
                if (char.isLetterOrDigit() || char == '-' || char == '_') char else '_'
            }
            .joinToString("")
            .ifBlank { DEFAULT_PRO_API_CACHE_NAMESPACE }

    @Serializable
    private data class CacheEntry(
        val createdAtMillis: Long,
        val expiresAtMillis: Long,
        val payloadJson: JsonElement,
    )

    @Serializable
    private data class EncryptedCacheFile(
        val version: Int,
        val algorithm: String,
        val nonce: String,
        val cipherText: String,
        val tag: String,
    )

    companion object {
        fun defaultRootDirectory(): String = resolveProHttpCacheRootDirectory()
    }
}
