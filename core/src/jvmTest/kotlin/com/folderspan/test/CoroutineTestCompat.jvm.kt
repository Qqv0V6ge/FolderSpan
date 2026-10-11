package com.folderspan.test

import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.TestResult

actual fun runSuspendTest(block: suspend () -> Unit): TestResult = runBlocking {
    block()
}
