package com.folderspan.utils

import io.github.aakira.napier.Antilog
import io.github.aakira.napier.LogLevel
import io.github.aakira.napier.Napier
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull

class AppLoggingAndroidTest {
    @AfterTest
    fun tearDown() {
        Napier.takeLogarithm()
    }

    @Test
    fun untaggedLogsFromShortThreadsReachEveryAntilog() {
        installAppLogging()
        val received = AtomicInteger()
        Napier.base(object : Antilog() {
            override fun performLog(priority: LogLevel, tag: String?, throwable: Throwable?, message: String?) {
                received.incrementAndGet()
            }
        })
        val failure = AtomicReference<Throwable?>()
        val worker = object : Thread() {
            override fun run() {
                try {
                    for (depth in 0..3) emitLog(depth)
                } catch (error: Throwable) {
                    failure.set(error)
                }
            }

            private fun emitLog(depth: Int) {
                if (depth > 0) {
                    emitLog(depth - 1)
                } else {
                    Napier.log(LogLevel.INFO, null, null, "Device connection approved")
                    Napier.log(LogLevel.ERROR, "device-connect", IllegalStateException("test error"), "Connection log")
                }
            }
        }
        worker.start()
        worker.join(5_000)

        assertFalse(worker.isAlive, "日志线程未结束")
        assertNull(failure.get(), "短堆栈日志不应抛出异常")
        assertEquals(8, received.get())
    }
}
