package com.folderspan.runtime

import com.folderspan.db.FolderSpanDatabase
import com.folderspan.service.http.server.startBackgroundShareServices
import com.folderspan.service.mcp.McpServerSettingsStore
import com.folderspan.service.mcp.http.McpHttpServiceInterface
import com.folderspan.service.mcp.http.McpHttpServiceLifecycle
import com.folderspan.ui.state.main.DeviceState
import com.folderspan.ui.state.main.NetworkState
import com.folderspan.ui.state.main.SyncState
import com.folderspan.ui.state.main.TaskState
import com.folderspan.ui.state.main.WebRtcRoomState
import com.folderspan.ui.state.file.FileState
import com.folderspan.ui.state.file.FileShareState
import com.folderspan.utils.DatabaseReady
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers
import org.koin.core.Koin
import org.koin.core.context.GlobalContext
import strings.AppStrings
import java.nio.file.Path
import kotlin.system.exitProcess

data class FolderSpanRuntimeStatus(
    val lockHeld: Boolean,
    val webUiRunning: Boolean,
    val webUiPort: Int? = null,
    val errorCode: String? = null,
)

private val runtimeMutex = Mutex()

/** Desktop business services; the Web UI listener belongs to startHeadlessPlatformRuntime only. */
suspend fun startFolderSpanRuntime(
    koin: Koin = GlobalContext.get(),
    onStarted: () -> Unit = {},
    runOptionalServices: suspend CoroutineScope.() -> Unit = {},
): Unit = coroutineScope {
    check(runtimeMutex.tryLock()) { "FolderSpan runtime is already running" }
    RuntimeServiceFailures.clear()
    var tasks: TaskState? = null
    var sync: SyncState? = null
    var devices: DeviceState? = null
    var files: FileState? = null
    var mcpLifecycle: McpHttpServiceLifecycle? = null
    var optionalServices: Job? = null
    var sharing: Job? = null
    try {
        koin.get<FolderSpanDatabase>()
        DatabaseReady.await()
        tasks = koin.get<TaskState>()
        sync = koin.get<SyncState>()
        devices = koin.get<DeviceState>()
        files = koin.get<FileState>()
        check(koin.get<NetworkState>().loadPersisted()) { "Network configuration failed to load" }
        check(koin.get<WebRtcRoomState>().loadPersisted()) { "WebRTC configuration failed to load" }
        optionalServices = launch(start = CoroutineStart.UNDISPATCHED) {
            RuntimeServiceFailures.observe("account-services", runOptionalServices)
        }
        devices.start()
        sync.start()
        mcpLifecycle = McpHttpServiceLifecycle(
            koin.get<McpHttpServiceInterface>(), koin.get<McpServerSettingsStore>()::read,
        )
        RuntimeServiceFailures.observe("mcp") { mcpLifecycle.enterForeground() }
        sharing = launch {
            RuntimeServiceFailures.observe("sharing") { startBackgroundShareServices(koin.get<FileShareState>()) }
        }
        onStarted()
        awaitCancellation()
    } finally {
        try {
            withContext(NonCancellable + Dispatchers.IO) {
                // Every cleanup gets a chance, even if another service fails to stop.
                RuntimeServiceFailures.observe("mcp-stop") { mcpLifecycle?.enterBackground() }
                RuntimeServiceFailures.observe("sharing-stop") { sharing?.cancelAndJoin() }
                RuntimeServiceFailures.observe("account-services-stop") { optionalServices?.cancelAndJoin() }
                RuntimeServiceFailures.observe("sync-stop") { sync?.stop() }
                RuntimeServiceFailures.observe("tasks-stop") { tasks?.stop() }
                RuntimeServiceFailures.observe("files-stop") { files?.mainScope?.coroutineContext?.get(Job)?.cancelAndJoin() }
                RuntimeServiceFailures.observe("devices-stop") { devices?.stop() }
            }
        } finally {
            runtimeMutex.unlock()
        }
    }
}

fun printWebUiUrls(urls: List<String>, oneTimeSecret: String?) {
    println("FolderSpan Web UI")
    urls.sortedBy { url -> if (url.startsWith("https://")) 0 else 1 }
        .forEach { url -> println("  $url") }
    if (!oneTimeSecret.isNullOrBlank()) {
        println(AppStrings.ui_web_ui_setup_secret_arg0.format(arg0 = oneTimeSecret))
    }
}

fun acquireRuntimeLockOrNull(directory: Path? = null): FolderSpanInstanceLock? {
    return if (directory == null) {
        FolderSpanInstanceLock.tryAcquire()
    } else {
        FolderSpanInstanceLock.tryAcquire(directory)
    }
}

fun installRuntimeShutdownHook(job: Job, lock: FolderSpanInstanceLock?): Thread = Thread({
    try {
        runBlocking { job.cancelAndJoin() }
    } finally {
        lock?.close()
    }
}, "folderspan-runtime-shutdown").also { Runtime.getRuntime().addShutdownHook(it) }

fun failIfRuntimeLocked(lock: FolderSpanInstanceLock?): Int {
    if (lock != null) return 0
    System.err.println(AppStrings.ui_folderspan_already_running)
    return 1
}

fun exitIfRuntimeLocked(lock: FolderSpanInstanceLock?) {
    val code = failIfRuntimeLocked(lock)
    if (code != 0) exitProcess(code)
}
