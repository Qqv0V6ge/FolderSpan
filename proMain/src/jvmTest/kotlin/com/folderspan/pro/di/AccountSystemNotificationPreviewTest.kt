package com.folderspan.pro.di

import kotlin.test.Test
import kotlin.test.assertEquals

class AccountSystemNotificationPreviewTest {
    @Test
    fun systemNotificationBodyHandlesExternalRouteMalformedAndMixedContent() {
        val content = "[Docs](https://example.test) [Ticket](route:feedback_tickets?ticketUuid=ticket%2D1) " +
            "[Email](mailto:test@example.test) [broken"

        assertEquals(
            "Docs Ticket Email [broken",
            accountSystemNotificationBody(content),
        )
    }
}
