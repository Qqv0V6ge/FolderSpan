package com.folderspan.service.http.client

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respondOk
import io.ktor.client.request.get
import io.ktor.http.Url
import kotlinx.coroutines.runBlocking
import java.net.InetSocketAddress
import java.net.Proxy
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class HttpClientProxyTest {
    @Test
    fun configuredProxyUsesHttpHandshakeUrlsAndBlankConfigurationKeepsDirectUrls() = runBlocking {
        for (proxyUrl in listOf(" http://127.0.0.1:3128 ", " ")) {
            val client = HttpClient(MockEngine) {
                applyConfiguredHttpProxy(proxyUrl)
                engine { addHandler { respondOk() } }
            }
            try {
                val proxy = client.engine.config.proxy
                if (proxyUrl.isBlank()) {
                    assertNull(proxy)
                } else {
                    assertEquals(Proxy.Type.HTTP, proxy?.type())
                    val address = proxy?.address() as InetSocketAddress
                    assertEquals("127.0.0.1", address.hostString)
                    assertEquals(3128, address.port)
                }
                for ((scheme, proxyScheme) in listOf("ws" to "http", "wss" to "https", "https" to "https")) {
                    val response = client.get("$scheme://example.test:8443/socket?room=a")
                    val expectedScheme = if (proxyUrl.isBlank()) scheme else proxyScheme
                    assertEquals(Url("$expectedScheme://example.test:8443/socket?room=a"), response.call.request.url)
                }
            } finally {
                client.close()
            }
        }
    }
}
