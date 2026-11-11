package com.folderspan.service.webui

import com.folderspan.service.http.server.linkshare.LinkShareHttpRequest
import com.folderspan.service.http.server.linkshare.LinkShareHttpResponseBody
import java.net.URLEncoder
import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class WebUiDiskSpacePrivacyTest {
    @Test
    fun appleHostDoesNotDiscloseDiskSpaceThroughWebManagement() = runBlocking {
        val directory = Files.createTempDirectory("webui-disk-privacy")
        val session = WebUiPlatformSession("disk-privacy")
        try {
            val platform = JvmWebUiPlatform()
            val host = platform.hostInfo(session)
            val response = platform.handle(LinkShareHttpRequest.from("GET",
                WEB_UI_PLATFORM_PATH + "/files/space?path=" + URLEncoder.encode(directory.toString(), "UTF-8")), session)
            val bytes = (response.body as LinkShareHttpResponseBody.Bytes).bytes
            val space = WebUiPlatformClient.json.decodeFromString<List<Long>>(bytes.decodeToString())
            if (System.getProperty("os.name").startsWith("Mac", ignoreCase = true)) {
                assertTrue(host.roots.all { it.totalSpace == 0L && it.freeSpace == 0L })
                // FileUtils uses negative values for unknown capacity, so local backup
                // checks in the browser do not mistake hidden space for a full disk.
                assertEquals(listOf(-1L, -1L), space)
            } else {
                assertTrue(space[0] > 0L && space[1] >= 0L)
            }
        } finally {
            session.close()
            Files.deleteIfExists(directory)
        }
    }
}
