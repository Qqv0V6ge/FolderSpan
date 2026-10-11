package com.folderspan.test

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.TestResult
import kotlinx.coroutines.withTimeout
import platform.Foundation.NSDate
import platform.Foundation.NSRunLoop
import platform.Foundation.dateWithTimeIntervalSinceNow
import platform.Foundation.runUntilDate

actual fun runSuspendTest(block: suspend () -> Unit): TestResult {
    // Native WebRTC dispatches its events to MainScope. Keep the Darwin main loop
    // alive while the suspend test runs, as an iOS application would.
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    val result = scope.async { withTimeout(120_000L) { block() } }
    try {
        while (!result.isCompleted) {
            NSRunLoop.mainRunLoop.runUntilDate(NSDate.dateWithTimeIntervalSinceNow(0.01))
        }
        runBlocking { result.await() }
    } finally {
        scope.cancel()
    }
}
