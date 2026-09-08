package com.folderspan.notification

import kotlin.test.Test
import kotlin.test.assertEquals

class AccountNotificationPreviewsTest {
    @Test
    fun listPreviewHandlesExternalRouteMalformedAndMixedContent() {
        val cases = mapOf(
            "[Docs](https://example.test/docs)" to "Docs",
            "[Settings](route:settings)" to "Settings",
            "Keep [unclosed](https://example.test" to "Keep [unclosed](https://example.test",
            "Before [Docs](https://example.test) and [Email](mailto:test@example.test)." to
                "Before Docs and Email.",
        )

        cases.forEach { (content, expected) ->
            assertEquals(expected, accountNotificationListPreview(content), content)
        }
    }
}
