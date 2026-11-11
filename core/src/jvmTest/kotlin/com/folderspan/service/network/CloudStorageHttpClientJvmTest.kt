package com.folderspan.service.network

import com.sun.net.httpserver.HttpServer
import io.ktor.client.HttpClient
import io.ktor.http.HttpMethod
import io.ktor.http.Url
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Proxy
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class CloudStorageHttpClientJvmTest {
    @Test
    fun completePostsReuseConnectionAndCloseOwnedClient() = runBlocking {
        withServer { url, ports ->
            var creations = 0
            lateinit var owned: HttpClient
            val api = StorageHttpApi(token = { "test" }, clientFactory = { config ->
                creations++
                createCloudStorageHttpClient(config).also { owned = it }
            })
            try {
                assertEquals(Proxy.NO_PROXY, api.client.engine.config.proxy)
                repeat(2) {
                    val response = api.json(HttpMethod.Post, "$url/metadata", buildJsonObject { put("index", it) })
                    assertEquals(64 * 1024, (response.jsonObject.getValue("data") as JsonPrimitive).content.length)
                }
                assertEquals(1, creations)
                assertEquals(1, ports.size, "Fully consumed POST responses must reuse a connection")
            } finally {
                api.close()
            }
            withTimeout(5_000L) { owned.coroutineContext[Job]!!.join() }
            assertFalse(owned.coroutineContext[Job]!!.isActive)
        }
    }

    @Test
    fun overrideClientRemainsOpenAndSkipsFactory() = runBlocking {
        withServer { url, _ ->
            val client = createCloudStorageHttpClient { }
            try {
                val api = StorageHttpApi(token = { "test" }, overrideClient = client,
                    clientFactory = { error("An override must skip client creation") })
                api.close()
                assertTrue(client.coroutineContext[Job]!!.isActive)
                api.json(HttpMethod.Post, "$url/metadata", buildJsonObject { put("request", true) })
            } finally {
                client.close()
            }
        }
    }

    @Test
    fun readJsonRetriesTransientStatusButWriteJsonRunsOnce() = runBlocking {
        val executor = Executors.newCachedThreadPool()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.executor = executor
        val reads = AtomicInteger()
        val writes = AtomicInteger()
        server.createContext("/read") { exchange ->
            exchange.use {
                it.requestBody.readBytes()
                if (reads.incrementAndGet() == 1) {
                    it.sendResponseHeaders(503, -1)
                } else {
                    val body = "{\"ok\":true}".toByteArray()
                    it.sendResponseHeaders(200, body.size.toLong())
                    it.responseBody.write(body)
                }
            }
        }
        server.createContext("/write") { exchange ->
            exchange.use {
                it.requestBody.readBytes()
                writes.incrementAndGet()
                it.sendResponseHeaders(503, -1)
            }
        }
        server.start()
        val api = StorageHttpApi(token = { "test" }, clientFactory = ::createCloudStorageHttpClient)
        try {
            val value = api.readJson(HttpMethod.Post, "http://127.0.0.1:${server.address.port}/read",
                buildJsonObject { put("read", true) })
            assertEquals(true, value.jsonObject.getValue("ok").toString().toBoolean())
            assertEquals(2, reads.get(), "A transient read status should be retried once")
            assertFailsWith<StorageApiException> {
                api.json(HttpMethod.Post, "http://127.0.0.1:${server.address.port}/write",
                    buildJsonObject { put("write", true) })
            }
            assertEquals(1, writes.get(), "Write requests must not use the read retry policy")
        } finally {
            api.close()
            server.stop(0)
            executor.shutdownNow()
        }
    }

    @Test
    fun contentReadRetriesStatusBeforeBytesButNeverReplaysDeliveredBytes() = runBlocking {
        val executor = Executors.newCachedThreadPool()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.executor = executor
        val retryCalls = AtomicInteger()
        val partialCalls = AtomicInteger()
        server.createContext("/download") { exchange ->
            exchange.use {
                if (retryCalls.incrementAndGet() == 1) {
                    it.sendResponseHeaders(503, -1)
                } else {
                    val body = "abc".toByteArray()
                    it.sendResponseHeaders(200, body.size.toLong())
                    it.responseBody.write(body)
                }
            }
        }
        server.createContext("/partial") { exchange ->
            exchange.use {
                partialCalls.incrementAndGet()
                it.sendResponseHeaders(200, 6)
                it.responseBody.write("abc".toByteArray())
            }
        }
        server.start()
        val api = StorageHttpApi(token = { "test" }, clientFactory = ::createCloudStorageHttpClient)
        try {
            val first = mutableListOf<ByteArray>()
            api.retryStorageRead {
                api.client.readStorageFile(Url("http://127.0.0.1:${server.address.port}/download"), 3,
                    method = HttpMethod.Get) { bytes, _ -> first += bytes; Result.success(Unit) }.getOrThrow()
            }
            assertEquals(2, retryCalls.get(), "A 503 before any bytes should retry")
            assertEquals(listOf("abc"), first.map { it.decodeToString() })

            var delivered = false
            val partialResult = runCatching {
                api.retryStorageRead(retryIf = { !delivered }) {
                    api.client.readStorageFile(Url("http://127.0.0.1:${server.address.port}/partial"), 6,
                        method = HttpMethod.Get) { bytes, _ ->
                        delivered = delivered || bytes.isNotEmpty()
                        Result.success(Unit)
                    }.getOrThrow()
                }
            }
            assertTrue(partialResult.isFailure)
            assertTrue(delivered)
            assertEquals(1, partialCalls.get(), "A failed response after bytes must not replay the download")
        } finally {
            api.close()
            server.stop(0)
            executor.shutdownNow()
        }
    }

    @Test
    fun progressCancellationStopsStreamingUploadAndAllowsNextRequest() = runBlocking {
        withServer { url, _ ->
            val api = StorageHttpApi(token = { "test" }, clientFactory = ::createCloudStorageHttpClient)
            try {
                var progressed = false
                val chunks = AtomicInteger()
                val result = runCatching {
                    withTimeout(10_000L) {
                        api.upload(HttpMethod.Post, "$url/upload", 4L * 1024 * 1024, { sent, _ ->
                            if (sent > 0) {
                                progressed = true
                                throw CancellationException("test upload cancellation")
                            }
                        }, source = {
                            chunks.incrementAndGet()
                            ByteArray(64 * 1024)
                        })
                    }
                }
                assertTrue(progressed, "The streaming body must report progress before cancellation")
                val failure = result.exceptionOrNull()
                assertTrue(failure != null && generateSequence<Throwable>(failure) { it.cause }.any { it is CancellationException })
                assertEquals(1, chunks.get(), "Cancellation must stop reading the source")
                withTimeout(5_000L) {
                    api.json(HttpMethod.Post, "$url/metadata", buildJsonObject { put("afterCancellation", true) })
                }
            } finally {
                api.close()
            }
        }
    }

    private suspend fun withServer(block: suspend (String, Set<Int>) -> Unit) {
        val ports = ConcurrentHashMap.newKeySet<Int>()
        val executor = Executors.newCachedThreadPool()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.executor = executor
        server.createContext("/metadata") { exchange ->
            exchange.use {
                ports += exchange.remoteAddress.port
                exchange.requestBody.readBytes()
                val body = "{\"data\":\"${"x".repeat(64 * 1024)}\"}".toByteArray()
                exchange.responseHeaders.add("Content-Type", "application/json")
                exchange.sendResponseHeaders(200, body.size.toLong())
                exchange.responseBody.write(body)
            }
        }
        server.createContext("/upload") { exchange ->
            exchange.use {
                try {
                    exchange.requestBody.readBytes()
                    exchange.sendResponseHeaders(200, 2)
                    exchange.responseBody.write("{}".toByteArray())
                } catch (_: IOException) {
                    // A cancelled streaming request may close before its declared length.
                }
            }
        }
        server.start()
        try {
            block("http://127.0.0.1:${server.address.port}", ports)
        } finally {
            server.stop(0)
            executor.shutdownNow()
        }
    }
}
