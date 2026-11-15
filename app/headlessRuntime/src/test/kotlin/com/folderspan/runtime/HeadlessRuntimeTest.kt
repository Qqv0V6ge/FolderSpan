package com.folderspan.runtime

import com.folderspan.data.file.FileSimpleInfo
import com.folderspan.service.http.server.linkshare.LinkShareHttpRequest
import com.folderspan.service.http.server.linkshare.LinkShareHttpResponseBody
import com.folderspan.service.webui.*
import com.folderspan.utils.SettingsUtils
import com.russhwolf.settings.MapSettings
import java.net.ServerSocket
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.time.Duration
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assume.assumeTrue
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class HeadlessRuntimeTest {
    @Test
    fun authenticationHostMetadataAndFilesWorkWithoutPresentationLibraries(): Unit = runBlocking {
        // Native preferences use the isolated user directory on Linux; other hosts may use the registry/CFPreferences.
        assumeTrue(System.getProperty("os.name").startsWith("Linux"))
        val settings = MapSettings()
        SettingsUtils.init(settings)
        val credentials = WebUiCredentialStore(settings)
        val secret = assertNotNull(credentials.ensureInitialized().oneTimeSecret)
        val handler = WebUiRequestHandler(
            credentials, WebUiSessionStore(), WebUiSecurityPolicy { setOf("127.0.0.1") },
            PlaceholderWebUiDocumentRoot(),
        )
        val service = WebUiHttpService(handler)
        val session = WebUiPlatformSession("headless-files-test")
        val directory = Files.createTempDirectory("headless-files-")
        val json = Json { encodeDefaults = true; ignoreUnknownKeys = true }
        try {
            val port = ServerSocket(0).use { it.localPort }
            assertTrue(service.start(port).running)
            val client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build()
            fun login(password: String): HttpResponse<String> = client.send(
                HttpRequest.newBuilder(URI("http://127.0.0.1:$port/api/web-ui/login"))
                    .timeout(Duration.ofSeconds(10))
                    .header("Origin", "http://127.0.0.1:$port")
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(json.encodeToString(
                        WebUiLoginRequest(password, WEB_UI_PROTOCOL_VERSION),
                    )))
                    .build(),
                HttpResponse.BodyHandlers.ofString(),
            )
            assertTrue(login("incorrect-secret").statusCode() in 400..499)
            val response = login(secret)
            assertEquals(200, response.statusCode())
            val loggedIn = json.decodeFromString<WebUiLoginResponse>(response.body())
            assertTrue(loggedIn.sessionToken.isNotEmpty())
            val host = assertNotNull(loggedIn.host)
            assertTrue(host.fingerprint.isNotEmpty())
            assertTrue(host.publicKey.isNotEmpty())

            val file = Files.writeString(directory.resolve("中文 file.txt"), "headless payload")
            val path = URLEncoder.encode(file.toString(), Charsets.UTF_8.name())
            val platform = JvmWebUiPlatform()
            val info = platform.handle(LinkShareHttpRequest.from(
                "GET", "$WEB_UI_PLATFORM_PATH/files/info?path=$path",
            ), session)
            assertEquals(200, info.statusCode)
            val metadata = json.decodeFromString<FileSimpleInfo>(
                (info.body as LinkShareHttpResponseBody.Bytes).bytes.decodeToString(),
            )
            assertEquals("中文 file.txt", metadata.name)
            assertEquals(16L, metadata.size)
            val deleted = platform.handle(LinkShareHttpRequest.from(
                "DELETE", "$WEB_UI_PLATFORM_PATH/files?path=$path",
            ), session)
            assertEquals(200, deleted.statusCode)
            assertFalse(Files.exists(file))
        } finally {
            session.close()
            service.stop()
            directory.toFile().deleteRecursively()
        }
    }
}
