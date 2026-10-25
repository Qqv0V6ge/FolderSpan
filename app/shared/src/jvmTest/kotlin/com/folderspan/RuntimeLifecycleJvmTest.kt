package com.folderspan

import com.folderspan.data.StatusEnum
import com.folderspan.data.main.network.Network
import com.folderspan.di.initKoin
import com.folderspan.runtime.RuntimeServiceFailures
import com.folderspan.runtime.acquireRuntimeLockOrNull
import com.folderspan.runtime.installRuntimeShutdownHook
import com.folderspan.runtime.startFolderSpanRuntime
import com.folderspan.service.webui.DEFAULT_WEB_UI_PORT
import com.folderspan.ui.state.main.NetworkState
import com.folderspan.ui.state.main.Task
import com.folderspan.ui.state.main.TaskState
import com.folderspan.ui.state.main.TaskType
import com.folderspan.ui.state.main.TaskRuntimeTransferCheckpoint
import com.folderspan.ui.state.main.TempTaskRuntimePersistenceStore
import com.folderspan.ui.state.main.SyncEndpointType
import com.folderspan.ui.state.main.SyncRunStatus
import com.folderspan.ui.state.main.SyncScheduleType
import com.folderspan.ui.state.main.SyncTask
import com.folderspan.ui.state.main.TempSyncTaskStore
import com.folderspan.utils.installAppLogging
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.koin.core.context.GlobalContext
import java.io.File
import java.net.ServerSocket
import java.net.URLClassLoader
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import java.util.prefs.AbstractPreferences
import java.util.prefs.Preferences
import java.util.prefs.PreferencesFactory
import kotlin.system.exitProcess
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class RuntimeLifecycleJvmTest {
    @Test
    fun desktopBusinessRuntimeRecoversSchedulesAndReleasesResources(): Unit {
        val directory = Files.createTempDirectory("folderspan-runtime-lifecycle").toRealPath()
        val processes = mutableListOf<Process>()
        val classpath = buildList {
            addAll(System.getProperty("java.class.path").split(File.pathSeparator))
            generateSequence(javaClass.classLoader) { it.parent }.filterIsInstance<URLClassLoader>()
                .flatMap { it.urLs.asSequence() }.forEach { add(Path.of(it.toURI()).toString()) }
        }.distinct().joinToString(File.pathSeparator)
        fun start(mode: String, name: String): Process {
            val args = listOf(
                "-Xmx512m", "-Djava.awt.headless=true", "-Duser.home=$directory/home",
                "-Djava.util.prefs.userRoot=$directory/prefs", "-Djava.util.prefs.systemRoot=$directory/system-prefs",
                "-Djava.util.prefs.PreferencesFactory=${RuntimeLifecyclePreferencesFactory::class.java.name}",
                "-Djava.io.tmpdir=$directory/tmp", "-cp", classpath,
                RuntimeLifecycleProbe::class.java.name, mode,
            )
            val argsFile = directory.resolve("$name.args")
            Files.writeString(argsFile, args.joinToString("\n") { arg ->
                "\"" + arg.replace("\\", "\\\\").replace("\"", "\\\"") + "\""
            })
            return ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "java").toString(), "@$argsFile")
                .apply {
                    val home = directory.resolve("home")
                    environment()["APPDATA"] = home.toString()
                    environment()["LOCALAPPDATA"] = home.resolve("AppData/Local").toString()
                    environment()["HOME"] = home.toString()
                    environment()["USERPROFILE"] = home.toString()
                    environment()["XDG_DATA_HOME"] = home.resolve(".local/share").toString()
                    environment()["XDG_CACHE_HOME"] = home.resolve(".cache").toString()
                }
                .directory(directory.toFile()).redirectErrorStream(true)
                .redirectOutput(directory.resolve("$name.log").toFile()).start().also(processes::add)
        }
        fun awaitFile(path: Path, process: Process) {
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30)
            while (!Files.exists(path)) {
                assertTrue(process.isAlive && System.nanoTime() < deadline, "Runtime did not produce $path; logs: $directory")
                Thread.sleep(50)
            }
        }
        var passed = false
        try {
            listOf("home", "prefs", "system-prefs", "tmp").forEach { Files.createDirectories(directory.resolve(it)) }
            // Desktop must recover business services even when Runtime's port is occupied.
            ServerSocket(DEFAULT_WEB_UI_PORT).use { reservedPort ->
                repeat(2) { round ->
                    val seeded = start("seed", "seed-$round")
                    assertTrue(seeded.waitFor(20, TimeUnit.SECONDS))
                    assertEquals(0, seeded.exitValue(), "Seed failed; logs: $directory")
                    Files.deleteIfExists(directory.resolve("ready"))
                    Files.deleteIfExists(directory.resolve("stopped"))
                    Files.deleteIfExists(directory.resolve("stop-request"))
                    Files.deleteIfExists(directory.resolve("target/payload.txt"))
                    val runtime = start("run", "runtime-$round")
                    awaitFile(directory.resolve("ready"), runtime)
                    awaitFile(directory.resolve("target/payload.txt"), runtime)
                    assertEquals("persisted sync payload", Files.readString(directory.resolve("target/payload.txt")))
                    val duplicate = start("run", "duplicate-$round")
                    assertTrue(duplicate.waitFor(20, TimeUnit.SECONDS))
                    assertEquals(23, duplicate.exitValue())
                    if (round == 0 && !System.getProperty("os.name").startsWith("Windows")) {
                        assertEquals(0, ProcessBuilder("kill", "-INT", runtime.pid().toString()).start().waitFor())
                    } else if (System.getProperty("os.name").startsWith("Windows")) {
                        // Windows Process.destroy() terminates the JVM without running shutdown hooks.
                        Files.writeString(directory.resolve("stop-request"), "stop")
                    } else runtime.destroy()
                    assertTrue(runtime.waitFor(20, TimeUnit.SECONDS), "Runtime did not stop; logs: $directory")
                    assertTrue(Files.exists(directory.resolve("stopped")))
                    assertTrue(!reservedPort.isClosed)
                }
            }
            passed = true
        } finally {
            processes.filter(Process::isAlive).forEach(Process::destroy)
            processes.forEach { process ->
                if (!process.waitFor(5, TimeUnit.SECONDS)) {
                    process.destroyForcibly()
                    check(process.waitFor(5, TimeUnit.SECONDS))
                }
            }
            // Keep failed-run logs in the system temporary directory for diagnosis.
            if (passed) {
                directory.toFile().deleteRecursively()
            }
        }
    }
}

/** Preferences root properties do not isolate the Windows registry-backed JDK provider. */
class RuntimeLifecyclePreferencesFactory : PreferencesFactory {
    private val user = RuntimeLifecyclePreferences(null, "")
    private val system = RuntimeLifecyclePreferences(null, "")
    override fun userRoot(): Preferences = user
    override fun systemRoot(): Preferences = system
}

private class RuntimeLifecyclePreferences(parent: AbstractPreferences?, name: String) : AbstractPreferences(parent, name) {
    private val values = mutableMapOf<String, String>()
    override fun putSpi(key: String, value: String) { values[key] = value }
    override fun getSpi(key: String): String? = values[key]
    override fun removeSpi(key: String) { values.remove(key) }
    override fun removeNodeSpi() { values.clear() }
    override fun keysSpi(): Array<String> = values.keys.toTypedArray()
    override fun childrenNamesSpi(): Array<String> = emptyArray()
    override fun childSpi(name: String): AbstractPreferences = RuntimeLifecyclePreferences(this, name)
    override fun syncSpi() = Unit
    override fun flushSpi() = Unit
}

/** Child JVM uses production DI/lifecycle and isolated working directory, home and preferences. */
object RuntimeLifecycleProbe {
    @JvmStatic
    fun main(args: Array<String>) {
        installAppLogging()
        val root = Path.of(System.getProperty("user.dir"))
        if (args[0] == "seed") {
            val source = Files.createDirectories(root.resolve("source"))
            val target = Files.createDirectories(root.resolve("target"))
            Files.writeString(source.resolve("payload.txt"), "persisted sync payload")
            TempSyncTaskStore().saveTask(SyncTask(
                id = 1, name = "Recovered schedule", sourceType = SyncEndpointType.Local,
                sourcePath = source.toString(), targetType = SyncEndpointType.Local, targetPath = target.toString(),
                scheduleType = SyncScheduleType.Interval, intervalMinutes = 1, nextRunAt = 1,
                lastStatus = SyncRunStatus.Running,
            ))
            val tasks = TempTaskRuntimePersistenceStore()
            tasks.saveTaskSnapshot(Task(taskType = TaskType.Copy, key = 9191, status = StatusEnum.LOADING))
            tasks.saveTransferCheckpoint(TaskRuntimeTransferCheckpoint(
                taskKey = 9191, entryId = "retained", destPath = target.resolve("checkpoint.bin").toString(),
                fileSize = 100, chunkSize = 10, completedChunks = 4, createdAt = 1,
            ))
            initKoin()
            runBlocking {
                val networks = GlobalContext.get().get<NetworkState>()
                check(networks.loadPersisted())
                if (networks.entries.isEmpty()) networks.addNetwork(Network(
                    name = "Saved fixture", pathSeparator = "/", protocol = "WebDav",
                    host = "http://127.0.0.1:9", username = "", password = "",
                ), persist = true)
            }
            return
        }
        val lock = acquireRuntimeLockOrNull() ?: exitProcess(23)
        val exitCode = try {
            initKoin()
            runBlocking {
                val job = launch(Dispatchers.Default, start = CoroutineStart.LAZY) {
                    try {
                        startFolderSpanRuntime(onStarted = {
                            check(RuntimeServiceFailures.state.value["account-services"] == "service_failed")
                            val koin = GlobalContext.get()
                            check(koin.get<NetworkState>().entries.single().network.name == "Saved fixture")
                            check(koin.get<TaskState>().getTask(9191)?.status == StatusEnum.FAILURE)
                            check(TempTaskRuntimePersistenceStore().loadTransferCheckpoint(9191, "retained")?.completedChunks == 4)
                            Files.writeString(root.resolve("ready"), "ready")
                        }) { error("optional service failure fixture") }
                    } finally {
                        Files.writeString(root.resolve("stopped"), "stopped")
                    }
                }
                val hook = installRuntimeShutdownHook(job, lock)
                try {
                    if (System.getProperty("os.name").startsWith("Windows")) {
                        launch(Dispatchers.IO) {
                            while (!Files.exists(root.resolve("stop-request"))) delay(50)
                            exitProcess(0)
                        }
                    }
                    job.start()
                    job.join()
                } finally {
                    runCatching { Runtime.getRuntime().removeShutdownHook(hook) }
                }
            }
            0
        } catch (error: Exception) {
            System.err.println("Lifecycle probe failed: ${error.message}")
            1
        } finally {
            lock.close()
        }
        if (exitCode != 0) exitProcess(exitCode)
    }
}
