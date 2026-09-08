package com.folderspan.service.http.server

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class EasyFileShareServiceTest {
    @Test
    fun backgroundShareServicesStartFileShareAndEasyShareConcurrently() = runTest {
        val events = mutableListOf<String>()

        val job = launch {
            runBackgroundShareServices(
                startFileShare = {
                    events += "file:start"
                    try {
                        awaitCancellation()
                    } finally {
                        events += "file:stop"
                    }
                },
                startEasyShare = {
                    events += "easy:start"
                    try {
                        awaitCancellation()
                    } finally {
                        events += "easy:stop"
                    }
                }
            )
        }
        runCurrent()

        assertEquals(listOf("file:start", "easy:start"), events)

        job.cancelAndJoin()

        assertEquals(
            setOf("file:start", "easy:start", "file:stop", "easy:stop"),
            events.toSet()
        )
    }

    @Test
    fun runningEasyShareServiceStopsWhenCoroutineIsCancelled() = runTest {
        val server = FakeHttpShareFileServer(runningAfterStart = true)

        val job = launch {
            runEasyFileShareService(
                port = 18_080,
                server = server,
                initializeIfNeeded = { true }
            )
        }
        runCurrent()

        assertTrue(job.isActive)
        assertEquals(listOf("start:18080"), server.events)

        job.cancelAndJoin()

        assertEquals(listOf("start:18080", "stop"), server.events)
    }

    @Test
    fun easyShareServiceReturnsAndStopsWhenServerDidNotStart() = runTest {
        val server = FakeHttpShareFileServer(runningAfterStart = false)

        runEasyFileShareService(
            port = 18_081,
            server = server,
            initializeIfNeeded = { true }
        )

        assertEquals(listOf("start:18081", "stop"), server.events)
    }

    @Test
    fun easyShareServiceDoesNothingWhenInitializationReturnsFalse() = runTest {
        val server = FakeHttpShareFileServer(runningAfterStart = true)
        var initializationAttempts = 0

        runEasyFileShareService(
            port = 18_082,
            server = server,
            initializeIfNeeded = {
                initializationAttempts += 1
                false
            }
        )

        assertEquals(1, initializationAttempts)
        assertEquals(emptyList(), server.events)
    }

    @Test
    fun easyShareServiceWaitsForInitializationReadinessSignal() = runTest {
        val server = FakeHttpShareFileServer(runningAfterStart = false)
        val readiness = CompletableDeferred<Unit>()
        var initialized = false

        val job = launch {
            runEasyFileShareService(
                port = 18_083,
                server = server,
                awaitInitializationReady = {
                    readiness.await()
                    true
                },
                initializeIfNeeded = {
                    initialized = true
                    true
                }
            )
        }
        runCurrent()

        assertTrue(job.isActive)
        assertEquals(false, initialized)
        assertEquals(emptyList(), server.events)

        readiness.complete(Unit)
        job.join()

        assertTrue(initialized)
        assertEquals(listOf("start:18083", "stop"), server.events)
    }

    @Test
    fun easyShareServiceDoesNotInitializeWhenReadinessFails() = runTest {
        val server = FakeHttpShareFileServer(runningAfterStart = true)
        var initialized = false

        runEasyFileShareService(
            port = 18_084,
            server = server,
            awaitInitializationReady = { false },
            initializeIfNeeded = {
                initialized = true
                true
            }
        )

        assertEquals(false, initialized)
        assertEquals(emptyList(), server.events)
    }

    private class FakeHttpShareFileServer(
        private val runningAfterStart: Boolean,
    ) : HttpShareFileServerInterface {
        val events = mutableListOf<String>()
        private var running = false

        override fun start(port: Int) {
            events += "start:$port"
            running = runningAfterStart
        }

        override suspend fun stop() {
            events += "stop"
            running = false
        }

        override fun isRunning(): Boolean = running
    }
}
