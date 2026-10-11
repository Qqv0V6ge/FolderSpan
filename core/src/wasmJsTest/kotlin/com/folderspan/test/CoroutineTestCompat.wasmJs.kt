package com.folderspan.test

import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.promise
import kotlinx.coroutines.test.TestResult
import kotlin.js.ExperimentalWasmJsInterop
import kotlin.js.toJsReference
import kotlin.js.unsafeCast

@OptIn(DelicateCoroutinesApi::class, ExperimentalWasmJsInterop::class)
actual fun runSuspendTest(block: suspend () -> Unit): TestResult = GlobalScope.promise {
    block()
    Unit.toJsReference()
}.unsafeCast()
