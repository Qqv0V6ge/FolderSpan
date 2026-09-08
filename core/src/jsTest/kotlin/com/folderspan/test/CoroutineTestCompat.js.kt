@file:Suppress("CAST_NEVER_SUCCEEDS")

package com.folderspan.test

import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.promise
import kotlinx.coroutines.test.TestResult

@OptIn(DelicateCoroutinesApi::class)
actual fun runSuspendTest(block: suspend () -> Unit): TestResult = GlobalScope.promise {
    block()
} as TestResult
