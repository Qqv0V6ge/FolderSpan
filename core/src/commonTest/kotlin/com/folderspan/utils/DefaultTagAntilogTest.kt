package com.folderspan.utils

import io.github.aakira.napier.Antilog
import io.github.aakira.napier.LogLevel
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

class DefaultTagAntilogTest {
    @Test
    fun suppliesDefaultTagAndPreservesExplicitTagAndLogPayload() {
        val error = IllegalStateException("connection error")
        val receivedTags = mutableListOf<String?>()
        val logger = DefaultTagAntilog(object : Antilog() {
            override fun performLog(priority: LogLevel, tag: String?, throwable: Throwable?, message: String?) {
                assertEquals(LogLevel.ERROR, priority)
                assertSame(error, throwable)
                assertEquals("Device connection approved", message)
                receivedTags += tag
            }
        })

        logger.log(LogLevel.ERROR, null, error, "Device connection approved")
        logger.log(LogLevel.ERROR, "device-connect", error, "Device connection approved")

        assertEquals(listOf<String?>("app", "device-connect"), receivedTags)
    }

    @Test
    fun preservesDelegateFilteringWithResolvedTag() {
        var received = 0
        val logger = DefaultTagAntilog(object : Antilog() {
            override fun isEnable(priority: LogLevel, tag: String?): Boolean = tag == "app" && priority == LogLevel.INFO

            override fun performLog(priority: LogLevel, tag: String?, throwable: Throwable?, message: String?) {
                received++
            }
        })

        assertTrue(logger.isEnable(LogLevel.INFO, null))
        assertFalse(logger.isEnable(LogLevel.DEBUG, null))
        assertFalse(logger.isEnable(LogLevel.INFO, "disabled"))
        logger.log(LogLevel.INFO, null, null, "accepted")
        logger.log(LogLevel.DEBUG, null, null, "filtered")

        assertEquals(1, received)
    }
}
