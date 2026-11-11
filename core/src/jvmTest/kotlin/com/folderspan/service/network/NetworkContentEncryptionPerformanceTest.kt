package com.folderspan.service.network

import com.folderspan.data.main.network.Network
import com.folderspan.data.main.network.StorageContentLocationReference
import com.folderspan.ui.state.main.TaskState
import com.folderspan.ui.state.main.TestTaskRuntimePersistenceStore
import com.folderspan.utils.PathUtils
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.*
import org.junit.Assume.assumeTrue
import java.lang.management.ManagementFactory
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermission
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlin.test.*

/**
 * Opt in with phase=write, then phase=cold in three fresh JVM/user.home profiles.
 * Reports contain measured process metrics, never credentials or the synthetic content password.
 */
class NetworkContentEncryptionPerformanceTest {
    @Test fun pathProtocol() = profile("SFTP")
    @Test fun cloudProtocol() = profile("Nextcloud")
    @Test fun nextcloudRequestProfile() = profile("Nextcloud", requestProfile = true)

    private fun profile(case: String, requestProfile: Boolean = false) = runBlocking {
        val phase = System.getenv("FOLDERSPAN_ENCRYPTION_PERFORMANCE_PHASE")
        assumeTrue("Opt-in performance profile", phase == "write" || phase == "cold")
        val repetitions = if (requestProfile) 1 else System.getenv("FOLDERSPAN_ENCRYPTION_PERFORMANCE_SAMPLES")?.toInt() ?: 3
        require(repetitions in 1..3) { "Performance sample count must be 1..3" }
        val directory = Path.of(System.getenv("FOLDERSPAN_ENCRYPTION_PERFORMANCE_DIR") ?: error("Missing private performance directory")).toRealPath()
        val home = Path.of(System.getProperty("user.home")).toRealPath()
        require(home.startsWith(directory)) { "Use a fresh task-owned user.home within the performance directory" }
        require(Files.getPosixFilePermissions(directory).none { it.name.startsWith("GROUP_") || it.name.startsWith("OTHERS_") }) {
            "Performance directory must be owner-only"
        }
        val cache = Path.of(PathUtils.getCachePath(), "content-transfers").toAbsolutePath().normalize()
        require(cache.startsWith(directory)) { "Performance cache must be inside the task-owned directory" }
        val fixture = readNetworkContentEncryptionFixture(case)
        val credential = fixture["credential"]?.let { Json.decodeFromJsonElement<StorageCredential>(it) }
        val account = credential?.let { StorageAccounts.install(it.copy(id = UUID.randomUUID().toString())) }
        var base = Json.decodeFromJsonElement<NetworkConnectionConfig>(fixture.getValue("config"))
        if (account != null) base = base.copy(extras = base.extras.copy(cloud = base.extras.cloud.copy(
            accountId = account.id, connectionId = "performance-" + UUID.randomUUID(),
        )))
        val raw = NetworkClientFactory.create(base)
        val samples = mutableListOf<JsonObject>()
        val local = Files.createTempDirectory(home, "verified-downloads-")
        val statePath = directory.resolve("$case-state.json")
        val networks = mutableListOf<Network>()
        val configured = mutableListOf<NetworkConnectionConfig>()
        try {
            withTimeout(1_800_000) {
                if (phase == "write") {
                    check(!Files.exists(statePath)) { "A performance run already exists; use a new task-owned directory" }
                    val parent = fixture["root"]?.jsonPrimitive?.content ?: raw.rootLocation.path
                    val root = parent.trimEnd('/') + "/performance-" + UUID.randomUUID()
                    assertTrue(raw.createFolder(root).getOrThrow())
                    val scenarios = buildJsonObject {
                        for ((scenario, payload, scenarioFileCount) in listOf(
                            Triple("large", ByteArray(32 * 1024 * 1024) { ((it * 31 + 7) % 251).toByte() }, 1),
                            Triple("small", ByteArray(4 * 1024) { ((it * 17 + 13) % 239).toByte() }, 16),
                        ).filter { !requestProfile || it.first == "small" }) {
                            val fileCount = if (requestProfile) 4 else scenarioFileCount
                            val plainPath = "$root/plain-$scenario"
                            val encryptedPath = "$root/encrypted-$scenario"
                            assertTrue(raw.createFolder(plainPath).getOrThrow())
                            assertTrue(raw.createFolder(encryptedPath).getOrThrow())
                            val entry = raw.stat(StoragePath(encryptedPath)).getOrThrow()
                            val pending = base.copy(extras = base.extras.copy(
                                contentEncryption = StorageContentLocationReference(root = StoragePath(entry.path, entry.reference)),
                            ))
                            val password = "performance content password " + UUID.randomUUID()
                            val reference = StorageContentPlatform.setupPassword(pending, password.toCharArray()).getOrThrow()
                            val encrypted = pending.copy(extras = pending.extras.copy(contentEncryption = reference))
                            configured += encrypted
                            val expectedHash = hash(payload)
                            val inventories = buildJsonObject {
                                for ((mode, config, path) in listOf(
                                    Triple("plain", base, plainPath), Triple("encrypted", encrypted, encryptedPath),
                                )) {
                                    val network = network(config).also { networks += it }
                                    repeat(repetitions) { sample ->
                                        val names = (0 until fileCount).map { "$scenario-$sample-$it.bin" }
                                        samples += measure("$mode.$scenario.upload", sample, fileCount, fileCount * payload.size.toLong(), cache) {
                                            for (name in names) upload(network, "$path/$name", payload)
                                        }
                                        val files = network.getList(path, null, null).getOrThrow().filter { it.name in names }
                                        assertEquals(fileCount, files.size)
                                        samples += measure("$mode.$scenario.download", sample, fileCount, fileCount * payload.size.toLong(), cache) {
                                            for (file in files) assertTrue(network.downloadFileToLocal(file, local.resolve(file.name).toString()) { _, _ -> }.getOrThrow())
                                        }
                                        for (name in names) {
                                            assertEquals(expectedHash, hash(Files.readAllBytes(local.resolve(name))))
                                            Files.delete(local.resolve(name))
                                        }
                                    }
                                    put(mode, inventory(raw, StoragePath(path)))
                                }
                            }
                            put(scenario, buildJsonObject {
                                put("reference", Json.encodeToJsonElement(reference)); put("password", password)
                                put("payloadHash", expectedHash); put("fileCount", fileCount * repetitions)
                                put("inventories", inventories)
                            })
                        }
                    }
                    val inventories = buildJsonObject {
                        for ((scenario, value) in scenarios) put(scenario, value.jsonObject.getValue("inventories"))
                    }
                    writePrivate(statePath, buildJsonObject {
                        put("parentRoot", root); put("scenarios", scenarios); put("inventories", inventories)
                    })
                    writePrivate(directory.resolve("$case-write.json"), report(case, fixture, home, cache, samples, inventories))
                    if (requestProfile) deleteNetworkContentEncryptionFixture(raw, StoragePath(root))
                } else {
                    val sample = (System.getenv("FOLDERSPAN_ENCRYPTION_PERFORMANCE_COLD_SAMPLE") ?: error("Missing cold sample index")).toInt()
                    require(sample in 0..2)
                    val state = Json.parseToJsonElement(Files.readString(statePath)).jsonObject
                    for ((scenario, value) in state.getValue("scenarios").jsonObject) {
                        val scenarioState = value.jsonObject
                        val reference = Json.decodeFromJsonElement<StorageContentLocationReference>(scenarioState.getValue("reference"))
                        val encrypted = base.copy(extras = base.extras.copy(contentEncryption = reference))
                        configured += encrypted
                        val network = network(encrypted).also { networks += it }
                        assertFalse(StorageContentPlatform.status(encrypted).getOrThrow().unlocked)
                        samples += measure("cold.$scenario.first-password-unlock", sample, 0, 0, cache) {
                            StorageContentPlatform.unlockPassword(encrypted, scenarioState.getValue("password").jsonPrimitive.content.toCharArray()).getOrThrow()
                        }
                        var entries = emptyList<com.folderspan.data.file.FileSimpleInfo>()
                        val count = scenarioState.getValue("fileCount").jsonPrimitive.int
                        samples += measure("cold.$scenario.first-history-list-after-unlock", sample, count, 0, cache) {
                            entries = network.getList(reference.root.path, null, null).getOrThrow()
                        }
                        assertEquals(count, entries.size)
                        val file = entries.single { it.name == "$scenario-0-0.bin" }
                        val output = local.resolve("cold-$scenario-verified.bin")
                        samples += measure("cold.$scenario.verified-download", sample, 1, file.size, cache) {
                            assertTrue(network.downloadFileToLocal(file, output.toString()) { _, _ -> }.getOrThrow())
                        }
                        assertEquals(scenarioState.getValue("payloadHash").jsonPrimitive.content, hash(Files.readAllBytes(output)))
                        Files.delete(output)
                    }
                    writePrivate(directory.resolve("$case-cold-$sample.json"), report(case, fixture, home, cache, samples, state.getValue("inventories").jsonObject))
                    if (System.getenv("FOLDERSPAN_ENCRYPTION_PERFORMANCE_CLEANUP") == "1") {
                        val root = state.getValue("parentRoot").jsonPrimitive.content
                        require(root.substringAfterLast('/').matches(Regex("performance-[0-9a-f-]{36}")))
                        deleteNetworkContentEncryptionFixture(raw, StoragePath(root))
                    }
                }
                println("ENCRYPTION_PERFORMANCE measured case=$case phase=$phase reports=$directory")
            }
        } finally {
            for (config in configured) StorageContentPlatform.lock(config)
            for (network in networks) network.close()
            raw.close()
            account?.let { StorageAccounts.store.remove(it.id) }
            local.toFile().deleteRecursively()
        }
    }

    private fun network(config: NetworkConnectionConfig): Network =
        object : Network("Performance", config.pathSeparator, config.protocol, config.host, config.username, config.password, extras = config.extras) {
            override val taskState = TaskState(runtimeStore = TestTaskRuntimePersistenceStore())
        }

    private suspend fun upload(network: Network, target: String, bytes: ByteArray) {
        var offset = 0
        assertTrue(network.uploadFromSource(target, bytes.size.toLong(), { _, _ -> }) {
            if (offset == bytes.size) null else {
                val end = minOf(offset + 1024 * 1024, bytes.size)
                bytes.copyOfRange(offset, end).also { offset = end }
            }
        }.getOrThrow())
    }

    private suspend fun measure(operation: String, sample: Int, files: Int, bytes: Long, cache: Path, block: suspend () -> Unit): JsonObject {
        val running = AtomicBoolean(true)
        val startRss = rss()
        val startTemp = directoryBytes(cache)
        val peakRss = AtomicLong(startRss)
        val peakTemp = AtomicLong(startTemp)
        val sampler = Thread({
            while (running.get()) {
                peakRss.accumulateAndGet(rss(), ::maxOf)
                peakTemp.accumulateAndGet(directoryBytes(cache), ::maxOf)
                try { Thread.sleep(50) } catch (_: InterruptedException) { return@Thread }
            }
        }, "encryption-performance-sampler").apply { isDaemon = true; start() }
        val cpu = ManagementFactory.getOperatingSystemMXBean() as com.sun.management.OperatingSystemMXBean
        val cpuStart = cpu.processCpuTime
        val startedEpochMs = System.currentTimeMillis()
        val started = System.nanoTime()
        var ended = started
        var cpuEnd = cpuStart
        try { block() }
        finally {
            ended = System.nanoTime()
            cpuEnd = cpu.processCpuTime
            peakRss.accumulateAndGet(rss(), ::maxOf)
            peakTemp.accumulateAndGet(directoryBytes(cache), ::maxOf)
            running.set(false); sampler.interrupt(); sampler.join(1_000)
        }
        val elapsed = ended - started
        val cpuElapsed = cpuEnd - cpuStart
        return buildJsonObject {
            put("operation", operation); put("sample", sample); put("fileCount", files); put("payloadBytes", bytes)
            put("startedEpochMs", startedEpochMs); put("endedEpochMs", startedEpochMs + elapsed / 1_000_000)
            put("elapsedMs", elapsed / 1_000_000.0); put("processCpuMs", cpuElapsed / 1_000_000.0)
            put("baselineRssBytes", startRss); put("sampledPeakRssBytes", peakRss.get())
            put("baselineTempBytes", startTemp); put("sampledPeakTempBytes", peakTemp.get())
            put("sampledAdditionalTempBytes", (peakTemp.get() - startTemp).coerceAtLeast(0))
            put("samplingIntervalMs", 50); put("processCpuIncludesSampler", true)
        }
    }

    private suspend fun inventory(provider: StorageProvider, root: StoragePath): JsonObject {
        val entries = mutableListOf<NetworkFileEntry>()
        val pending = ArrayDeque<StoragePath>()
        val seen = mutableSetOf<String>()
        pending.add(root)
        while (pending.isNotEmpty()) {
            val current = pending.removeFirst()
            check(seen.add(current.path))
            for (entry in provider.list(current).getOrThrow()) {
                if (entry.isDirectory) pending.add(StoragePath(entry.path, entry.reference)) else entries += entry
            }
        }
        val metadata = entries.filter { "/.folderspan-vault/" in it.path && !it.name.endsWith(".object") }
        return buildJsonObject {
            put("fileObjects", entries.size); put("totalRemoteBytes", entries.sumOf { it.size })
            put("metadataObjects", metadata.size); put("metadataBytes", metadata.sumOf { it.size })
            put("ciphertextObjectBytes", entries.filter { it.name.endsWith(".object") }.sumOf { it.size })
        }
    }

    private fun report(case: String, fixture: JsonObject, home: Path, cache: Path, samples: List<JsonObject>, inventory: JsonObject) =
        buildJsonObject {
            put("case", case); put("serviceVersion", fixture["serviceVersion"] ?: JsonNull)
            put("executingPlatform", "Linux JVM"); put("javaVersion", System.getProperty("java.version"))
            put("userHome", home.toString()); put("cacheDirectory", cache.toString())
            put("commit", git("rev-parse", "HEAD").trim()); put("workingTreeDirty", git("status", "--porcelain").isNotBlank())
            put("samples", JsonArray(samples)); put("remoteInventory", inventory)
            put("measurementScope", "Test worker process CPU/RSS; task-owned content-transfers cache; service and Gradle resources excluded")
            put("coldScope", "Each cold sample requires a fresh JVM and user.home; large and small use independent password locations; history work can occur during both unlock and first listing")
        }

    private fun git(vararg args: String): String =
        ProcessBuilder(listOf("git") + args).redirectError(ProcessBuilder.Redirect.DISCARD).start().let { process ->
            val text = process.inputStream.bufferedReader().readText()
            check(process.waitFor() == 0)
            text
        }

    private fun writePrivate(path: Path, value: JsonObject) {
        Files.writeString(path, Json { prettyPrint = true }.encodeToString(JsonObject.serializer(), value))
        Files.setPosixFilePermissions(path, setOf(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE))
    }

    private fun rss(): Long = Files.readAllLines(Path.of("/proc/self/status"))
        .single { it.startsWith("VmRSS:") }.trim().split(Regex("\\s+"))[1].toLong() * 1024

    private fun directoryBytes(root: Path): Long =
        runCatching {
            if (!Files.exists(root)) 0 else Files.walk(root).use { paths ->
                paths.filter { Files.isRegularFile(it) }.mapToLong { runCatching { Files.size(it) }.getOrDefault(0L) }.sum()
            }
        }.getOrDefault(0L)

    private fun hash(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes)
        .joinToString("") { "%02x".format(it.toInt() and 255) }
}
