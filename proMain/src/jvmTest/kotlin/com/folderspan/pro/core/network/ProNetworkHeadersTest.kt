package com.folderspan.pro.core.network

import strings.AppStrings

import com.folderspan.pro.test.ChineseLocalizationTest
import com.folderspan.AppBuildConfig
import com.folderspan.pro.data.remote.api.SettingApiService
import com.folderspan.pro.data.remote.api.UserApiService
import com.folderspan.pro.data.remote.dto.SettingCloneTargetRequest
import io.ktor.client.*
import io.ktor.client.engine.mock.*
import io.ktor.client.request.*
import io.ktor.http.*
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import kotlin.test.*

class ProNetworkHeadersTest : ChineseLocalizationTest() {
    @Test
    fun commonConfigUsesCentralHeaderValuesAndInjectedDeviceIdentity() = runTest {
        var headers = Headers.Empty
        val engine = MockEngine { request ->
            headers = request.headers
            respondOk()
        }
        val client = HttpClient(engine) {
            installCommonConfig(
                requestSigningConfig = RequestSigningConfig(
                    timestampProvider = { "1000" },
                    nonceProvider = { "nonce" },
                ),
                deviceIdentity = DeviceIdentity(
                    type = "JVM",
                    key = "device-id",
                    name = "Workstation",
                ),
                deviceLanguageTagsProvider = { listOf("zh_Hans_CN", "en-US") },
            )
        }

        client.get("https://example.test/api/v1/user/ping")

        assertEquals("application/json", headers[HttpHeaders.ContentType])
        assertEquals("application/json", headers[HttpHeaders.Accept])
        assertEquals("zh-Hans-CN,en-US", headers[HttpHeaders.AcceptLanguage])
        assertEquals(PRO_API_HOST_HEADER_VALUE, headers[HttpHeaders.Host])
        assertEquals("desktop-app", headers["X-App-Key"])
        assertNull(headers["X-Timestamp"])
        assertNull(headers["X-Nonce"])
        assertNull(headers["X-Signature"])
        assertEquals("JVM", headers["X-Device-Type"])
        assertEquals("device-id", headers["X-Device-Key"])
        assertEquals("Workstation", headers["X-Device-Name"])
    }

    @Test
    fun requestSignerMatchesUserApiHmacAndGoQueryEscapeRules() {
        val signed = RequestSigner.sign(
            method = "post",
            path = "/api/v1/test",
            query = listOf(
                "z" to "hello world",
                "a" to AppStrings.ui_test_editor_text_codec_chinese_sample,
                "empty" to "",
                "a" to "*!()'",
                "a" to "a+b",
            ),
            body = AppStrings.ui_test_pro_network_headers_chinese_message_json.encodeToByteArray(),
            timestamp = "1700000000",
            nonce = "0123456789abcdef0123456789abcdef",
            secret = "access-token",
        )

        assertEquals(
            "a=%2A%21%28%29%27&a=a%2Bb&a=%E4%B8%AD%E6%96%87&empty=&z=hello+world",
            signed.canonicalQuery,
        )
        assertEquals(
            "c9e2af2cd43caf5443003f9a156dfef55714f86fe7720635e2fdb6879aecb3ec",
            signed.bodySha256Hex,
        )
        assertEquals(
            listOf(
                "POST",
                "/api/v1/test",
                "a=%2A%21%28%29%27&a=a%2Bb&a=%E4%B8%AD%E6%96%87&empty=&z=hello+world",
                "c9e2af2cd43caf5443003f9a156dfef55714f86fe7720635e2fdb6879aecb3ec",
                "1700000000",
                "0123456789abcdef0123456789abcdef",
            ).joinToString("\n"),
            signed.signingText,
        )
        assertEquals(
            "3af9213dd8d7a6a6d086e323200b5e1e41509e7b291a63c4f1381ddaab89de0e",
            signed.signature,
        )
    }

    @Test
    fun requestSigningBearerTokenAcceptsOnlyBearerAuthorization() {
        assertEquals(
            "access-token",
            requestSigningBearerToken("bEaReR \t access-token "),
        )
        assertNull(requestSigningBearerToken("Basic credentials"))
        assertNull(requestSigningBearerToken("Bearer"))
        assertNull(requestSigningBearerToken(null))
    }

    @Test
    fun requestSignatureUsesBearerTokenAsHmacSecret() = runTest {
        var headers = Headers.Empty
        val engine = MockEngine { request ->
            headers = request.headers
            respondOk()
        }
        val client = HttpClient(engine) {
            installCommonConfig(
                requestSigningConfig = RequestSigningConfig(
                    timestampProvider = { "1000" },
                    nonceProvider = { "nonce" },
                ),
                deviceIdentity = DeviceIdentity(
                    type = "JVM",
                    key = "device-id",
                    name = "Workstation",
                ),
            )
        }

        client.get("https://example.test/api/v1/user/ping") {
            header(HttpHeaders.Authorization, "Bearer access-token")
        }

        assertEquals("1000", headers["X-Timestamp"])
        assertEquals("nonce", headers["X-Nonce"])
        assertEquals(
            RequestSigner.sign(
                method = "GET",
                path = "/api/v1/user/ping",
                query = emptyList(),
                body = ByteArray(0),
                timestamp = "1000",
                nonce = "nonce",
                secret = "access-token",
            ).signature,
            headers["X-Signature"],
        )
    }

    @Test
    fun commonConfigDoesNotUseOldHardcodedDeviceHeaderValues() = runTest {
        var headers = Headers.Empty
        val engine = MockEngine { request ->
            headers = request.headers
            respondOk()
        }
        val client = HttpClient(engine) {
            installCommonConfig(
                requestSigningConfig = RequestSigningConfig(
                    timestampProvider = { "1000" },
                    nonceProvider = { "nonce" },
                ),
            )
        }

        client.get("https://example.test/api/v1/user/ping")

        assertNotEquals("desktop", headers["X-Device-Type"])
        assertNotEquals("dev-machine-001", headers["X-Device-Key"])
        assertNotEquals("device_local", headers["X-Device-Name"])
    }

    @Test
    fun headerOverrideIsOnlyAppliedBySettingApiService() = runTest {
        val capturedRequests = mutableListOf<Pair<String, Headers>>()
        val engine = MockEngine { request ->
            capturedRequests += request.url.encodedPath to request.headers
            respond(
                content = """{"code":0}""",
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
            )
        }
        val client = HttpClient(engine) {
            installCommonConfig(
                requestSigningConfig = RequestSigningConfig(
                    timestampProvider = { "1000" },
                    nonceProvider = { "nonce" },
                ),
                deviceIdentity = DeviceIdentity(
                    type = "JVM",
                    key = "device-id",
                    name = "Workstation",
                ),
            )
        }
        val config = GatewayConfig(baseUrl = "https://example.test")
        val userApiService = UserApiService(client, config)
        val settingApiService = SettingApiService(
            client = client,
            config = config,
            headerOverrideProvider = {
                ProApiHeaderOverride(
                    host = "override-host:8888",
                    deviceType = "Android",
                    deviceKey = "other-device-key",
                    deviceName = "Other Phone",
                    appKey = "settings-app",
                )
            },
        )

        userApiService.ping()
        settingApiService.ping(token = "access-token")

        val userHeaders = capturedRequests.single { it.first == "/api/v1/user/ping" }.second
        val settingHeaders = capturedRequests.single { it.first == "/api/v1/settings/ping" }.second
        assertEquals(PRO_API_HOST_HEADER_VALUE, userHeaders[HttpHeaders.Host])
        assertEquals("JVM", userHeaders["X-Device-Type"])
        assertEquals("device-id", userHeaders["X-Device-Key"])
        assertEquals("Workstation", userHeaders["X-Device-Name"])
        assertEquals("desktop-app", userHeaders["X-App-Key"])
        assertNull(userHeaders["X-Timestamp"])
        assertNull(userHeaders["X-Nonce"])
        assertNull(userHeaders["X-Signature"])
        assertEquals("override-host:8888", settingHeaders[HttpHeaders.Host])
        assertEquals("Android", settingHeaders["X-Device-Type"])
        assertEquals("other-device-key", settingHeaders["X-Device-Key"])
        assertEquals("Other Phone", settingHeaders["X-Device-Name"])
        assertEquals("settings-app", settingHeaders["X-App-Key"])
        assertEquals(
            RequestSigner.sign(
                method = "GET",
                path = "/api/v1/settings/ping",
                query = emptyList(),
                body = ByteArray(0),
                timestamp = "1000",
                nonce = "nonce",
                secret = "access-token",
            ).signature,
            settingHeaders["X-Signature"],
        )
    }

    @Test
    fun cloneTargetSettingsDoesNotApplyHeaderOverride() = runTest {
        var cloneHeaders = Headers.Empty
        val engine = MockEngine { request ->
            if (request.url.encodedPath == "/api/v1/settings/targets/clone") {
                cloneHeaders = request.headers
            }
            respond(
                content = """{"code":0}""",
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
            )
        }
        val client = HttpClient(engine) {
            installCommonConfig(
                requestSigningConfig = RequestSigningConfig(
                    timestampProvider = { "1000" },
                    nonceProvider = { "nonce" },
                ),
                deviceIdentity = DeviceIdentity(
                    type = "JVM",
                    key = "device-id",
                    name = "Workstation",
                ),
            )
        }
        val settingApiService = SettingApiService(
            client = client,
            config = GatewayConfig(baseUrl = "https://example.test"),
            headerOverrideProvider = {
                ProApiHeaderOverride(
                    host = "override-host:8888",
                    deviceType = "Android",
                    deviceKey = "other-device-key",
                    deviceName = "Other Phone",
                    appKey = "settings-app",
                )
            },
        )

        settingApiService.cloneTargetSettings(
            request = SettingCloneTargetRequest(
                type = "personal",
                sourceTargetId = "source-device",
                targetId = "target-device",
                name = "Target Device",
            ),
            token = "access-token",
        )

        assertEquals(PRO_API_HOST_HEADER_VALUE, cloneHeaders[HttpHeaders.Host])
        assertEquals("JVM", cloneHeaders["X-Device-Type"])
        assertEquals("device-id", cloneHeaders["X-Device-Key"])
        assertEquals("Workstation", cloneHeaders["X-Device-Name"])
        assertEquals("desktop-app", cloneHeaders["X-App-Key"])
    }

    @Test
    fun commonConfigDoesNotPrintHttpRequestDump() = runTest {
        val engine = MockEngine { respondOk() }
        val client = HttpClient(engine) {
            installCommonConfig(
                requestSigningConfig = RequestSigningConfig(
                    timestampProvider = { "1000" },
                    nonceProvider = { "nonce" },
                ),
                deviceIdentity = DeviceIdentity(
                    type = "JVM",
                    key = "device-id",
                    name = "Workstation",
                ),
            )
        }
        val originalOut = System.out
        val buffer = ByteArrayOutputStream()
        System.setOut(PrintStream(buffer))
        try {
            client.get("https://example.test/api/v1/user/ping")
        } finally {
            System.setOut(originalOut)
            client.close()
        }

        val output = buffer.toString()
        assertFalse(output.contains("REQUEST:"))
        assertFalse(output.contains("COMMON HEADERS"))
        assertFalse(output.contains("BODY START"))
    }

    @Test
    fun headerOverrideCodecFiltersDynamicAndUnsafeHeaders() {
        val override = proApiHeaderOverrideFromHeaders(
            mapOf(
                "Host" to "override-host:8888",
                "X-Device-Type" to "Android",
                "X-Device-Key" to "other-device-key",
                "X-Device-Name" to "Other Phone",
                "Authorization" to "Bearer leaked",
                "X-Timestamp" to "old",
                "X-Nonce" to "old-nonce",
                "X-Signature" to "old-signature",
                "Content-Type" to "text/plain",
            ),
        )
        val encoded = encodeProApiHeaderOverride(override)

        assertEquals("override-host:8888", override.host)
        assertEquals("Android", override.deviceType)
        assertEquals("other-device-key", override.deviceKey)
        assertEquals("Other Phone", override.deviceName)
        assertFalse(encoded.contains("Authorization"))
        assertFalse(encoded.contains("X-Timestamp"))
        assertFalse(encoded.contains("X-Nonce"))
        assertFalse(encoded.contains("X-Signature"))
        assertFalse(encoded.contains("Content-Type"))
    }

    @Test
    fun gatewayConfigUsesCentralApiRouteDefaults() {
        val config = GatewayConfig(baseUrl = "https://example.test")
        val routes = RouteBuilder(config)

        assertEquals("https://example.test/api/v1/user/ping", routes.user("/ping"))
        assertEquals("https://example.test/api/v1/plugins/abc", routes.plugin("/abc"))
        assertEquals("https://example.test/api/v1/plugins/simple/", routes.pluginSimple())
        assertEquals("https://example.test/api/v1/orders/ping", routes.order("/ping"))
        assertEquals("https://example.test/api/v1/settings/entries", routes.settings("/entries"))
    }

    @Test
    fun gatewayBaseUrlComesFromBuildConfigAndUsesHttps() {
        val url = Url(AppBuildConfig.GATEWAY_BASE_URL)
        val host = url.host

        assertEquals(URLProtocol.HTTPS, url.protocol)
        assertNotNull(host)
        if (host.firstOrNull()?.isDigit() == true) {
            assertTrue(host.matches(Regex("""\d{1,3}(\.\d{1,3}){3}""")))
        }
    }

    @Test
    fun officialWebRtcWebSocketUrlConvertsGatewayHttpSchemeToWebSocketScheme() {
        assertEquals(
            "ws://gateway.example/api/v1/webrtc/ws",
            officialWebRtcWebSocketUrl("http://gateway.example/"),
        )
        assertEquals(
            "wss://gateway.example/api/v1/webrtc/ws",
            officialWebRtcWebSocketUrl("https://gateway.example"),
        )
    }

    @Test
    fun officialWebRtcWebSocketHeadersUseBearerDeviceIdAndRequestSignature() {
        val headers = officialWebRtcWebSocketHeaders(
            token = "access-token",
            deviceId = "device-a",
            requestSigningConfig = RequestSigningConfig(
                timestampProvider = { "1764144000" },
                nonceProvider = { "nonce-001" },
            ),
        ).getOrThrow()
        val expectedSignature = RequestSigner.sign(
            method = "GET",
            path = "/api/v1/webrtc/ws",
            query = emptyList(),
            body = ByteArray(0),
            timestamp = "1764144000",
            nonce = "nonce-001",
            secret = "access-token",
        ).signature

        assertEquals("Bearer access-token", headers[HttpHeaders.Authorization])
        assertEquals("device-a", headers["X-Device-ID"])
        assertEquals("desktop-app", headers["X-App-Key"])
        assertEquals("1764144000", headers["X-Timestamp"])
        assertEquals("nonce-001", headers["X-Nonce"])
        assertEquals(expectedSignature, headers["X-Signature"])
    }

    @Test
    fun officialWebRtcWebSocketHeadersRequireLoginTokenAndDeviceId() {
        assertTrue(
            officialWebRtcWebSocketHeaders(
                token = "",
                deviceId = "device-a",
            ).isFailure,
        )
        assertTrue(
            officialWebRtcWebSocketHeaders(
                token = "access-token",
                deviceId = "",
            ).isFailure,
        )
    }

    @Test
    fun defaultHttpProxyUsesRequestedHostAndPort() {
        val proxy = runtimeHttpProxyConfig()

        if (AppBuildConfig.HTTP_PROXY_URL.isBlank()) {
            assertNull(proxy)
        } else {
            assertNotNull(proxy)
            assertEquals("10.0.0.122", proxy.host)
            assertEquals(8080, proxy.port)
            assertEquals(AppBuildConfig.HTTP_PROXY_URL, proxy.url)
        }
    }
}
