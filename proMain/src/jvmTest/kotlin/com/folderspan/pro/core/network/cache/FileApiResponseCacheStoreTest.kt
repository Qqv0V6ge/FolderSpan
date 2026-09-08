package com.folderspan.pro.core.network.cache

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

class FileApiResponseCacheStoreTest {
    @Test
    fun readReturnsCachedPayloadBeforeExpiration() = runTest {
        var now = 1_000L
        val store = FileApiResponseCacheStore(
            rootDirectory = Files.createTempDirectory("pro-http-cache-test").toString(),
            nowMillis = { now },
        )
        val key = ApiCacheKey.from(method = "GET", url = "https://example.test/api/plugins")
        val payload = buildJsonObject {
            put("code", 0)
            put("message", "ok")
        }

        store.write(key, payload, ApiCachePolicy(ttl = 5.seconds))
        now = 4_000L

        assertEquals(payload, store.read(key))
    }

    @Test
    fun readReturnsNullAfterExpiration() = runTest {
        var now = 1_000L
        val store = FileApiResponseCacheStore(
            rootDirectory = Files.createTempDirectory("pro-http-cache-test").toString(),
            nowMillis = { now },
        )
        val key = ApiCacheKey.from(method = "GET", url = "https://example.test/api/plugins")
        val payload = buildJsonObject {
            put("code", 0)
            put("message", "ok")
        }

        store.write(key, payload, ApiCachePolicy(ttl = 1.seconds))
        now = 3_000L

        assertNull(store.read(key))
    }

    @Test
    fun readReturnsNullForCorruptCacheFile() = runTest {
        val root = Files.createTempDirectory("pro-http-cache-test")
        val store = FileApiResponseCacheStore(
            rootDirectory = root.toString(),
            nowMillis = { 1_000L },
        )
        val key = ApiCacheKey.from(method = "GET", url = "https://example.test/api/plugins")
        val cacheFile = root.resolve("pro-api").resolve("${key.storageKey}.json")
        Files.createDirectories(cacheFile.parent)
        Files.write(cacheFile, "not-json".encodeToByteArray())

        assertNull(store.read(key))
    }

    @Test
    fun cacheFileDoesNotStorePayloadAsPlainText() = runTest {
        val root = Files.createTempDirectory("pro-http-cache-test")
        val store = FileApiResponseCacheStore(
            rootDirectory = root.toString(),
            nowMillis = { 1_000L },
        )
        val key = ApiCacheKey.from(method = "GET", url = "https://example.test/api/plugins")
        val payload = buildJsonObject {
            put("code", 0)
            put("message", "vip-enabled")
            put("isVip", true)
        }

        store.write(key, payload, ApiCachePolicy(ttl = 5.seconds))

        val raw = Files.readString(root.resolve("pro-api").resolve("${key.storageKey}.json"))
        assertTrue(raw.contains("\"algorithm\":\"AES-GCM-256\""))
        assertTrue(raw.contains("\"tag\""))
        assertFalse(raw.contains("\"mac\""))
        assertFalse(raw.contains("payloadJson"))
        assertFalse(raw.contains("expiresAtMillis"))
        assertFalse(raw.contains("vip-enabled"))
        assertFalse(raw.contains("isVip"))
        assertEquals(payload, store.read(key))
    }

    @Test
    fun readReturnsNullAndDeletesCacheWhenEncryptedFileIsTampered() = runTest {
        val root = Files.createTempDirectory("pro-http-cache-test")
        val store = FileApiResponseCacheStore(
            rootDirectory = root.toString(),
            nowMillis = { 1_000L },
        )
        val key = ApiCacheKey.from(method = "GET", url = "https://example.test/api/plugins")
        val payload = buildJsonObject {
            put("code", 0)
            put("message", "ok")
        }
        val cacheFile = root.resolve("pro-api").resolve("${key.storageKey}.json")

        store.write(key, payload, ApiCachePolicy(ttl = 5.seconds))
        val raw = Files.readString(cacheFile)
        Files.writeString(cacheFile, raw.replaceFirst("A", "B"))

        assertNull(store.read(key))
        assertFalse(Files.exists(cacheFile))
    }

    @Test
    fun readReturnsNullAndDeletesLegacyPlainTextCacheFile() = runTest {
        val root = Files.createTempDirectory("pro-http-cache-test")
        val store = FileApiResponseCacheStore(
            rootDirectory = root.toString(),
            nowMillis = { 1_000L },
        )
        val key = ApiCacheKey.from(method = "GET", url = "https://example.test/api/plugins")
        val cacheFile = root.resolve("pro-api").resolve("${key.storageKey}.json")
        Files.createDirectories(cacheFile.parent)
        Files.writeString(
            cacheFile,
            """
            {"createdAtMillis":1000,"expiresAtMillis":6000,"payloadJson":{"code":0,"isVip":true}}
            """.trimIndent(),
        )

        assertNull(store.read(key))
        assertFalse(Files.exists(cacheFile))
    }

    @Test
    fun encryptedCacheCannotBeMovedToDifferentRequestKey() = runTest {
        val root = Files.createTempDirectory("pro-http-cache-test")
        val store = FileApiResponseCacheStore(
            rootDirectory = root.toString(),
            nowMillis = { 1_000L },
        )
        val firstKey = ApiCacheKey.from(method = "GET", url = "https://example.test/api/plugins")
        val secondKey = ApiCacheKey.from(method = "GET", url = "https://example.test/api/orders")
        val payload = buildJsonObject {
            put("code", 0)
            put("message", "ok")
        }
        val firstFile = root.resolve("pro-api").resolve("${firstKey.storageKey}.json")
        val secondFile = root.resolve("pro-api").resolve("${secondKey.storageKey}.json")

        store.write(firstKey, payload, ApiCachePolicy(ttl = 5.seconds))
        Files.copy(firstFile, secondFile)

        assertNull(store.read(secondKey))
        assertFalse(Files.exists(secondFile))
        assertTrue(Files.exists(firstFile))
    }
}
