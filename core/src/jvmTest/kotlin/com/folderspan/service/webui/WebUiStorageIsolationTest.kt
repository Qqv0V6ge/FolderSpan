package com.folderspan.service.webui

import com.folderspan.service.http.server.linkshare.LinkShareHttpRequest
import com.folderspan.service.http.server.linkshare.LinkShareHttpRequestBody
import com.folderspan.service.network.StorageAuthorizationHandle
import com.folderspan.service.network.StorageAuthorizationPoll
import com.folderspan.test.runSuspendTest
import kotlinx.serialization.encodeToString
import kotlin.test.*

class WebUiStorageIsolationTest {
    private fun request(operation: String, handle: String) = LinkShareHttpRequest.from("POST", WEB_UI_PLATFORM_PATH + "/storage/" + operation,
        body = LinkShareHttpRequestBody.Bytes(platformJson.encodeToString(StorageAuthorizationHandle(handle)).encodeToByteArray()))

    @Test
    fun authorizationCannotBeReadOrCancelledByAnotherSession() = runSuspendTest {
        val first = WebUiPlatformSession("first")
        val second = WebUiPlatformSession("second")
        var polls = 0
        var closed = 0
        val resource = StorageAuthorizationResource({ polls++; StorageAuthorizationPoll("pending") }, { closed++ })
        val handle = first.own(resource, resource.close)
        try {
            assertFailsWith<IllegalArgumentException> { storagePlatformRequest(request("poll",handle),second) }
            assertFailsWith<IllegalArgumentException> { storagePlatformRequest(request("cancel",handle),second) }
            assertEquals(0,polls);assertEquals(0,closed)
            storagePlatformRequest(request("poll",handle),first)
            assertEquals(1,polls)
            storagePlatformRequest(request("cancel",handle),first)
            assertEquals(1,closed)
            assertFailsWith<IllegalArgumentException> { storagePlatformRequest(request("poll",handle),first) }
        } finally { first.close();second.close() }
        assertEquals(1,closed)
    }
}
