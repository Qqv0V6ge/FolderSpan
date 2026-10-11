package com.folderspan.service.http.client

import com.folderspan.service.http.FILE_SHARE_ACCESS_KEY_HEADER
import com.folderspan.service.http.FileShareAccessKeyConfig
import com.folderspan.service.http.writeFileShareAccessKeyConfig
import com.russhwolf.settings.MapSettings
import io.ktor.client.*
import io.ktor.client.engine.mock.*
import io.ktor.client.plugins.*
import io.ktor.client.request.*
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class FileShareAccessKeyRequestTest {
    @Test
    fun disabledConfigDoesNotAddHeader() {
        val builder = HttpRequestBuilder()

        builder.applyFileShareAccessKey(MapSettings())

        assertNull(builder.headers[FILE_SHARE_ACCESS_KEY_HEADER])
    }

    @Test
    fun enabledConfigAddsLatestValueAndReplacesExistingHeader() {
        val settings = MapSettings().apply {
            writeFileShareAccessKeyConfig(
                FileShareAccessKeyConfig(enabled = true, value = "shared-key")
            )
        }
        val builder = HttpRequestBuilder().apply {
            headers.append(FILE_SHARE_ACCESS_KEY_HEADER, "old-key")
        }

        builder.applyFileShareAccessKey(settings)

        assertEquals("shared-key", builder.headers[FILE_SHARE_ACCESS_KEY_HEADER])
    }

    @Test
    fun enabledConfigWithInvalidValueDoesNotAddHeader() {
        val settings = MapSettings().apply {
            writeFileShareAccessKeyConfig(
                FileShareAccessKeyConfig(enabled = true, value = "line\nbreak")
            )
        }
        val builder = HttpRequestBuilder()

        builder.applyFileShareAccessKey(settings)

        assertNull(builder.headers[FILE_SHARE_ACCESS_KEY_HEADER])
    }

    @Test
    fun defaultRequestReadsLatestConfigForEveryRequest() = runTest {
        val settings = MapSettings().apply {
            writeFileShareAccessKeyConfig(
                FileShareAccessKeyConfig(enabled = true, value = "first-key")
            )
        }
        val capturedValues = mutableListOf<String>()
        val client = HttpClient(
            MockEngine { request ->
                capturedValues += request.headers[FILE_SHARE_ACCESS_KEY_HEADER].orEmpty()
                respondOk()
            }
        ) {
            defaultRequest {
                headers.applyFileShareAccessKey(settings)
            }
        }

        try {
            client.get("http://localhost/first")
            settings.writeFileShareAccessKeyConfig(
                FileShareAccessKeyConfig(enabled = true, value = "second-key")
            )
            client.get("http://localhost/second")
        } finally {
            client.close()
        }

        assertEquals(listOf("first-key", "second-key"), capturedValues)
    }
}
