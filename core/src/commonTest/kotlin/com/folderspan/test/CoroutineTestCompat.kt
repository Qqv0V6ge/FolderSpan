package com.folderspan.test

import kotlinx.coroutines.test.TestResult

expect fun runSuspendTest(block: suspend () -> Unit): TestResult
