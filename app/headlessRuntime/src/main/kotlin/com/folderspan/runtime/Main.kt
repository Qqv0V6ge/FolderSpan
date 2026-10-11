package com.folderspan.runtime

import com.folderspan.createSettings
import com.folderspan.service.webui.WebUiCredentialStore
import com.folderspan.utils.installAppLogging
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlin.system.exitProcess

fun main(args: Array<String>) {
    if (args.isNotEmpty() && !args.contentEquals(arrayOf("--reset-web-ui-key"))) {
        System.err.println("Usage: FolderSpan-Runtime [--reset-web-ui-key]")
        exitProcess(2)
    }
    installAppLogging()
    val lock = acquireRuntimeLockOrNull()
    if (failIfRuntimeLocked(lock) != 0) exitProcess(1)
    val exitCode = try {
        if (args.isNotEmpty()) {
            // Reset offline under the same lock as Desktop and runtime, without starting business services.
            printWebUiUrls(emptyList(), WebUiCredentialStore(createSettings()).rotate())
            return
        }
        runBlocking {
            val job = launch(Dispatchers.Default, start = CoroutineStart.LAZY) {
                startHeadlessPlatformRuntime()
            }
            val shutdownHook = installRuntimeShutdownHook(job, lock)
            try {
                job.start()
                job.join()
            } finally {
                // Removal is refused once JVM shutdown has already begun.
                runCatching { Runtime.getRuntime().removeShutdownHook(shutdownHook) }
            }
        }
        0
    } catch (error: Exception) {
        System.err.println("FolderSpan runtime startup failed: ${error.message}")
        1
    } finally {
        lock?.close()
    }
    if (exitCode != 0) exitProcess(exitCode)
}
