package com.folderspan.service.webui

import com.folderspan.data.file.FileSimpleInfo
import com.folderspan.service.http.server.linkshare.LinkShareHttpRequest
import com.folderspan.service.http.server.linkshare.LinkShareHttpRequestBody
import com.folderspan.service.http.server.linkshare.LinkShareHttpResponse
import com.folderspan.service.http.server.linkshare.LinkShareHttpResponseBody
import com.folderspan.utils.PathUtils
import com.folderspan.utils.SettingsUtils
import com.russhwolf.settings.MapSettings
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import org.junit.Assume.assumeTrue
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import strings.AppStrings

private fun LinkShareHttpResponse.bytes() = (body as LinkShareHttpResponseBody.Bytes).bytes
private inline fun <reified T> LinkShareHttpResponse.value(): T = WebUiPlatformClient.json.decodeFromString(bytes().decodeToString())

class WebUiFileParityTest {
    private class Fixture(val root: Path) {
        val json = WebUiPlatformClient.json
        private val settings = MapSettings().also(SettingsUtils::init)
        private val credentials = WebUiCredentialStore(settings)
        private val secret = checkNotNull(credentials.ensureInitialized().oneTimeSecret)
        private var token = ""
        val handler = WebUiRequestHandler(credentials, WebUiSessionStore(), WebUiSecurityPolicy { setOf("127.0.0.1") },
            PlaceholderWebUiDocumentRoot(), WebUiFileTestPlatform())

        suspend fun login() {
            handler.start()
            val response = request("POST", WEB_UI_LOGIN_PATH,
                json.encodeToString(WebUiLoginRequest(secret, WEB_UI_PROTOCOL_VERSION)).encodeToByteArray())
            assertEquals(200, response.statusCode)
            token = response.value<WebUiLoginResponse>().sessionToken
        }

        suspend fun request(method: String, path: String, bytes: ByteArray = byteArrayOf()) = handler.handleCommand(
            LinkShareHttpRequest.from(method, path, headers = buildMap {
                put("Host", listOf("127.0.0.1:12043"))
                put("Origin", listOf("http://127.0.0.1:12043"))
                put(WEB_UI_PROTOCOL_HEADER, listOf(WEB_UI_PROTOCOL_VERSION.toString()))
                if (token.isNotBlank()) put(WEB_UI_SESSION_HEADER, listOf(token))
            }, body = LinkShareHttpRequestBody.Bytes(bytes)),
        )

        fun pathBody(path: Path) = json.encodeToString(WebUiPathRequest(path.toString())).encodeToByteArray()

        suspend fun openDirectory(path: Path, includeCount: Boolean? = null): String {
            val query = includeCount?.let { "?includeDirectoryEntryCount=$it" }.orEmpty()
            val response = request("POST", "$WEB_UI_PLATFORM_PATH/files/directories$query", pathBody(path))
            assertEquals(200, response.statusCode)
            return response.value<WebUiHandle>().id
        }

        suspend fun batch(handle: String) = request("GET", "$WEB_UI_PLATFORM_PATH/files/directories?handle=$handle")
            .value<WebUiDirectoryBatch>()
    }

    private suspend fun withFixture(block: suspend Fixture.() -> Unit) {
        val root = Files.createTempDirectory("webui-file-parity-").toRealPath()
        val fixture = Fixture(root)
        try {
            fixture.login()
            fixture.block()
        } finally {
            fixture.handler.stop()
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun trailingWhitespaceNeverRetargetsFileOperations(): Unit = runBlocking {
        assumeTrue(PathUtils.getPathSeparator() == "/")
        withFixture {
            for ((index, suffix) in listOf(" ", "  ", "\t", "\n").withIndex()) {
                val plain = Files.writeString(root.resolve("file-$index"), "A")
                val selected = Files.writeString(root.resolve("file-$index$suffix"), "B")
                val query = encodeWebUiQuery(selected.toString())
                assertEquals(selected.toString(), request("GET", "$WEB_UI_PLATFORM_PATH/files/info?path=$query")
                    .value<FileSimpleInfo>().path)
                assertEquals("B", request("GET", "$WEB_UI_PLATFORM_PATH/files/content?path=$query&offset=0&length=1").bytes().decodeToString())
                assertEquals(200, request("PUT", "$WEB_UI_PLATFORM_PATH/files/content?path=$query&size=1&offset=0", "C".encodeToByteArray()).statusCode)
                assertEquals("C", Files.readString(selected))
                val renamed = root.resolve("renamed-$index$suffix")
                val renamedPlain = Files.writeString(root.resolve("renamed-$index"), "D")
                assertEquals(200, request("POST", "$WEB_UI_PLATFORM_PATH/files/rename",
                    json.encodeToString(WebUiRenameRequest(root.toString(), selected.fileName.toString(), renamed.fileName.toString())).encodeToByteArray()).statusCode)
                assertFalse(Files.exists(selected))
                assertEquals("C", Files.readString(renamed))
                assertEquals(200, request("DELETE", "$WEB_UI_PLATFORM_PATH/files?path=${encodeWebUiQuery(renamed.toString())}").statusCode)
                assertFalse(Files.exists(renamed))
                assertEquals("D", Files.readString(renamedPlain))
                assertEquals("A", Files.readString(plain))
                val created = root.resolve("created-$index$suffix")
                val createdPlain = Files.writeString(root.resolve("created-$index"), "E")
                assertEquals(200, request("POST", "$WEB_UI_PLATFORM_PATH/files/create", pathBody(created)).statusCode)
                assertTrue(Files.exists(created))
                assertEquals(0L, Files.size(created))
                assertEquals("E", Files.readString(createdPlain))
            }
        }
    }

    @Test
    fun trailingWhitespaceDirectoryNamesRemainDistinct(): Unit = runBlocking {
        assumeTrue(PathUtils.getPathSeparator() == "/")
        withFixture {
            val plain = Files.createDirectory(root.resolve("folder"))
            Files.writeString(plain.resolve("plain.txt"), "plain")
            val selected = root.resolve("folder ")
            assertEquals(200, request("POST", "$WEB_UI_PLATFORM_PATH/files/mkdir", pathBody(selected)).statusCode)
            Files.writeString(selected.resolve("selected.txt"), "selected")
            val entries = batch(openDirectory(selected)).entries
            assertEquals(listOf("selected.txt"), entries.map { it.name })
            assertTrue(Files.exists(plain.resolve("plain.txt")))
        }
    }

    @Test
    fun renameConflictPreservesReasonAndBothFiles(): Unit = runBlocking {
        withFixture {
            val source = Files.writeString(root.resolve("source.txt"), "source")
            val destination = Files.writeString(root.resolve("destination.txt"), "destination")
            val response = request("POST", "$WEB_UI_PLATFORM_PATH/files/rename",
                json.encodeToString(WebUiRenameRequest(root.toString(), "source.txt", "destination.txt")).encodeToByteArray())
            assertEquals(409, response.statusCode)
            val failure = response.value<WebUiJsonError>()
            assertEquals("conflict", failure.code)
            assertEquals(AppStrings.ui_the_file_name_is_already_present_and_cannot_be_renamed, failure.error)
            assertEquals("source", Files.readString(source))
            assertEquals("destination", Files.readString(destination))
            assertEquals(200, request("POST", "$WEB_UI_PLATFORM_PATH/files/rename",
                json.encodeToString(WebUiRenameRequest(root.toString(), "source.txt", "renamed.txt")).encodeToByteArray()).statusCode)
        }
    }

    @Test
    fun recursiveListingOmitsDisplayCountsWithoutChangingOrdinaryListing(): Unit = runBlocking {
        withFixture {
            repeat(130) { index ->
                val folder = Files.createDirectory(root.resolve("folder-$index"))
                Files.writeString(folder.resolve("child.txt"), "child")
            }
            val lightHandle = openDirectory(root, includeCount = false)
            val normalHandle = openDirectory(root)
            val lightFirst = batch(lightHandle)
            val normalFirst = batch(normalHandle)
            val lightLast = batch(lightHandle)
            val normalLast = batch(normalHandle)
            assertEquals(128, lightFirst.entries.size)
            assertFalse(lightFirst.finished)
            assertEquals(2, lightLast.entries.size)
            assertTrue(lightLast.finished)
            val light = (lightFirst.entries + lightLast.entries).associateBy { it.name }
            val normal = (normalFirst.entries + normalLast.entries).associateBy { it.name }
            assertEquals(normal.keys, light.keys)
            assertTrue(light.values.all { it.isDirectory && it.size == -1L })
            assertTrue(normal.values.all { it.isDirectory && it.size == 1L })
            val children = batch(openDirectory(root.resolve("folder-0"), includeCount = false)).entries
            assertEquals(5L, children.single().size)
            assertEquals("child.txt", children.single().name)
            assertEquals(400, request("POST", "$WEB_UI_PLATFORM_PATH/files/directories?includeDirectoryEntryCount=invalid", pathBody(root)).statusCode)
        }
    }
}
