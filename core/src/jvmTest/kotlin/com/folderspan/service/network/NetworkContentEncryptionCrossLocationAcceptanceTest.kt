package com.folderspan.service.network

import app.cash.sqldelight.ColumnAdapter
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.folderspan.createSettings
import com.folderspan.data.StatusEnum
import com.folderspan.data.file.*
import com.folderspan.data.main.Local
import com.folderspan.data.main.device.DeviceCategory
import com.folderspan.data.main.device.DeviceConnectType
import com.folderspan.data.main.device.DeviceType
import com.folderspan.data.main.network.*
import com.folderspan.db.*
import com.folderspan.test.ChineseLocalizationTest
import com.folderspan.ui.state.file.*
import com.folderspan.ui.state.main.*
import com.folderspan.utils.DatabaseReady
import com.folderspan.utils.SettingsUtils
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import org.junit.Assume.assumeTrue
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import org.koin.dsl.module
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlin.test.*

/** Opt-in real-service acceptance: production copy coordinator and production Move queue across independent vaults. */
class NetworkContentEncryptionCrossLocationAcceptanceTest : ChineseLocalizationTest() {
    @Test fun sftpIndependentPasswordLocations() = verify("SFTP")
    @Test fun nextcloudIndependentPasswordLocations() = verify("Nextcloud")

    private fun verify(case: String) = runBlocking(Dispatchers.IO) {
        val fixture = readNetworkContentEncryptionFixture(case)
        val resource = System.getenv(if (case == "SFTP") "FOLDERSPAN_ENCRYPTION_SFTP_FAULT_CONTAINER"
            else "FOLDERSPAN_ENCRYPTION_NEXTCLOUD_FAULT_VOLUME").orEmpty()
        val nextcloudContainer = System.getenv("FOLDERSPAN_ENCRYPTION_NEXTCLOUD_FAULT_CONTAINER").orEmpty()
        assumeTrue("Set the disposable backend identifier for a real post-object commit permission fault", resource.isNotBlank())
        assumeTrue("Set the disposable Nextcloud container for scoped permission-cache restoration",
            case != "Nextcloud" || nextcloudContainer.isNotBlank())
        assumeTrue("Set the real disposable Podman storage identity before Test home isolation",
            listOf("FOLDERSPAN_ENCRYPTION_FAULT_PODMAN_GRAPH_ROOT", "FOLDERSPAN_ENCRYPTION_FAULT_PODMAN_RUN_ROOT",
                "FOLDERSPAN_ENCRYPTION_FAULT_PODMAN_DRIVER").all { !System.getenv(it).isNullOrBlank() })
        val baseFixture = decode<NetworkConnectionConfig>(fixture.getValue("config"))
        val credential = fixture["credential"]?.let { decode<StorageCredential>(it) }
        val account = credential?.let { StorageAccounts.install(it.copy(id = UUID.randomUUID().toString())) }
        val local = Files.createTempDirectory("folderspan-cross-vault-")
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        val state = TaskState(runtimeStore = TestTaskRuntimePersistenceStore())
        var raw: StorageProvider? = null
        var source: Network? = null
        var target: Network? = null
        var sourceConfig: NetworkConnectionConfig? = null
        var targetConfig: NetworkConnectionConfig? = null
        var cleanupRoot: StoragePath? = null
        var blocker: ObjectCommitPermissionFault? = null
        var passed = false
        var cleaned = false
        val sourcePassword = ("cross source password " + UUID.randomUUID()).toCharArray()
        val targetPassword = ("cross target password " + UUID.randomUUID()).toCharArray()
        try {
            val database = database(driver)
            val networks = NetworkState(database)
            stopKoin()
            val application = startKoin { modules(module {
                single { database }
                single { state }
                single { networks }
                single { DeviceState() }
                single { NotificationState() }
            }) }
            val devices = application.koin.get<DeviceState>()
            withTimeout(480_000) {
                val base = baseFixture.copy(extras = baseFixture.extras.copy(contentEncryption = null,
                    cloud = baseFixture.extras.cloud.copy(accountId = account?.id ?: baseFixture.extras.cloud.accountId,
                        connectionId = UUID.randomUUID().toString())))
                val provider = NetworkClientFactory.create(base).also { raw = it }
                val name = "cross-vault-" + UUID.randomUUID()
                val parent = fixture["root"]?.jsonPrimitive?.content ?: provider.rootLocation.path
                val directory = parent.trimEnd('/') + "/" + name
                assertTrue(provider.createFolder(directory).getOrThrow())
                cleanupRoot = StoragePath(directory, provider.stat(StoragePath(directory)).getOrThrow().reference)
                suspend fun initialize(child: String, password: CharArray): NetworkConnectionConfig {
                    val path = directory + "/" + child
                    assertTrue(provider.createFolder(path).getOrThrow())
                    val info = provider.stat(StoragePath(path)).getOrThrow()
                    val pending = base.copy(extras = base.extras.copy(
                        cloud = base.extras.cloud.copy(connectionId = UUID.randomUUID().toString()),
                        contentEncryption = StorageContentLocationReference(root = StoragePath(info.path, info.reference))))
                    val ref = StorageContentPlatform.setupPassword(pending, password).getOrThrow()
                    return pending.copy(extras = pending.extras.copy(contentEncryption = ref))
                }
                val srcConfig = initialize("source", sourcePassword).also { sourceConfig = it }
                val dstConfig = initialize("target", targetPassword).also { targetConfig = it }
                assertNotEquals(srcConfig.extras.contentEncryption!!.vaultId, dstConfig.extras.contentEncryption!!.vaultId)
                StorageContentPlatform.lock(srcConfig).getOrThrow()
                StorageContentPlatform.lock(dstConfig).getOrThrow()
                assertTrue(StorageContentPlatform.unlockPassword(dstConfig, sourcePassword).isFailure)
                assertTrue(StorageContentPlatform.unlockPassword(srcConfig, targetPassword).isFailure)
                assertFalse(StorageContentPlatform.status(srcConfig).getOrThrow().unlocked)
                assertFalse(StorageContentPlatform.status(dstConfig).getOrThrow().unlocked)
                StorageContentPlatform.unlockPassword(srcConfig, sourcePassword).getOrThrow()
                StorageContentPlatform.unlockPassword(dstConfig, targetPassword).getOrThrow()
                val src = network(srcConfig, state).also { source = it }
                val dst = network(dstConfig, state).also { target = it }
                assertNotEquals(src.protocolId, dst.protocolId,
                    "The real Move endpoint resolver must distinguish independent same-host password locations")
                networks.entries.add(NetworkEntry(-1, src, false))
                networks.entries.add(NetworkEntry(-2, dst, false))
                val resolve: (com.folderspan.data.main.DiskBase?, String) -> NetworkAccess? = { preferred, id ->
                    (preferred as? NetworkAccess)?.takeIf { it.protocolId == id }
                        ?: networks.entries.firstOrNull { it.network.protocolId == id }?.network
                }
                val coordinator = FileStateCopyCoordinator(state, devices, networks, { src }, FileRecentState())
                val executor = FileRuntimeTaskExecutor(state, devices,
                    FileStateDirectoryCollector({ src }, devices, networks),
                    FileOperationIgnoreResolver(database, devices, { src }, { _, _ -> null }, resolve),
                    { src }, coordinator::runCopyAcrossEndpoints, {}, { _, _ -> null }, resolve)
                val srcRoot = srcConfig.extras.contentEncryption!!.root.path
                val dstRoot = dstConfig.extras.contentEncryption!!.root.path
                val bytes = ByteArray(256 * 1024 + 37) { ((it * 31 + 17) % 251).toByte() }

                upload(src, StoragePath(srcRoot + "/copy-source.bin"), bytes)
                val copySource = src.getList(srcRoot, null, null).getOrThrow().single { it.name == "copy-source.bin" }
                val copiedTarget = copySource.withCopy(name = "copied.bin", path = dstRoot + "/copied.bin",
                    protocolId = dst.protocolId, storageReference = null)
                val copy = task(state, TaskType.Copy, src)
                assertTrue(coordinator.runCopyAcrossEndpoints(copy, copySource, copiedTarget, src, dst).getOrThrow())
                val copied = dst.getList(dstRoot, null, null).getOrThrow().single { it.name == "copied.bin" }
                assertContentEquals(bytes, download(dst, copied, local.resolve("copied.bin")))
                assertContentEquals(bytes, download(src, copySource, local.resolve("still-source.bin")))
                val srcObjects = objects(provider, srcConfig.extras.contentEncryption!!.root)
                val dstObjects = objects(provider, dstConfig.extras.contentEncryption!!.root)
                assertEquals(1, srcObjects.size)
                assertEquals(1, dstObjects.size)
                assertFalse(readRaw(provider, srcObjects.single(), local.resolve("source.object")).contentEquals(
                    readRaw(provider, dstObjects.single(), local.resolve("target.object"))),
                    "The coordinator must decrypt and reencrypt for a different password location")

                upload(src, StoragePath(srcRoot + "/move-source.bin"), bytes)
                val moveSource = src.getList(srcRoot, null, null).getOrThrow().single { it.name == "move-source.bin" }
                val moveTarget = moveSource.withCopy(name = "moved.bin", path = dstRoot + "/moved.bin",
                    protocolId = dst.protocolId, storageReference = null)
                assertTrue(executor.executeMoveTask(task(state, TaskType.Move, src), moveSource, moveTarget).getOrThrow())
                assertTrue(src.getList(srcRoot, null, null).getOrThrow().none { it.name == "move-source.bin" })
                assertContentEquals(bytes, download(dst,
                    dst.getList(dstRoot, null, null).getOrThrow().single { it.name == "moved.bin" }, local.resolve("moved.bin")))

                upload(src, StoragePath(srcRoot + "/failure-source.bin"), bytes)
                val failureSource = src.getList(srcRoot, null, null).getOrThrow().single { it.name == "failure-source.bin" }
                val failureTarget = failureSource.withCopy(name = "failed-commit.bin", path = dstRoot + "/failed-commit.bin",
                    protocolId = dst.protocolId, storageReference = null)
                val beforeFailureObjects = objects(provider, dstConfig.extras.contentEncryption!!.root).map { it.name }.toSet()
                blocker = ObjectCommitPermissionFault.start(case, resource, directory.trim('/') + "/target/.folderspan-vault",
                    credential?.username?.takeIf(String::isNotBlank) ?: credential?.providerAccountId.orEmpty(),
                    nextcloudContainer, local.resolve("fault"))
                val failedMove = executor.executeMoveTask(task(state, TaskType.Move, src), failureSource, failureTarget)
                blocker!!.awaitBlocked()
                assertTrue(failedMove.isFailure || !failedMove.getOrDefault(false),
                    "The destination must fail after object upload when commit publication is denied")
                val retained = src.getList(srcRoot, null, null).getOrThrow().single { it.name == "failure-source.bin" }
                assertContentEquals(bytes, download(src, retained, local.resolve("retained.bin")))
                assertTrue(dst.getList(dstRoot, null, null).getOrThrow().none { it.name == "failed-commit.bin" },
                    "An unpublished ciphertext object must not become a partial logical file")
                val unpublished = objects(provider, dstConfig.extras.contentEncryption!!.root)
                    .filter { it.name !in beforeFailureObjects }
                assertEquals(1, unpublished.size,
                    "The injected failure must follow a new object from this failed Move, not an earlier successful copy")
                assertEquals(srcObjects.single().size, unpublished.single().size,
                    "The completed target object must have the full ciphertext length for the same plaintext size")
                blocker!!.close()
                blocker = null
                passed = true
            }
        } finally {
            sourcePassword.fill('\u0000')
            targetPassword.fill('\u0000')
            try {
                try { blocker?.close() } finally {
                sourceConfig?.let { StorageContentPlatform.lock(it) }
                targetConfig?.let { StorageContentPlatform.lock(it) }
                source?.close()
                target?.close()
                if (passed) cleanupRoot?.let { root -> raw?.let {
                    deleteNetworkContentEncryptionFixture(it, root)
                    cleaned = true
                } }
                else cleanupRoot?.let { println("CROSS_VAULT_ACCEPTANCE retained disposable root=" + it.path) }
                }
            } finally {
                raw?.close()
                account?.let { StorageAccounts.store.remove(it.id) }
                stopKoin()
                driver.close()
                if (passed && cleaned && blocker?.restored != false) local.toFile().deleteRecursively()
                else println("CROSS_VAULT_ACCEPTANCE retained diagnostic folder=" + local)
            }
        }
        check(passed && cleaned)
        println("CROSS_VAULT_ACCEPTANCE passed case=" + case +
            " copy=coordinator move=runtime-executor commitFailure=post-object-permission-denied cleanup=cleaned")
    }

    private fun network(config: NetworkConnectionConfig, state: TaskState) =
        object : Network("Cross-vault acceptance", config.pathSeparator, config.protocol, config.host,
            config.username, config.password, extras = config.extras) { override val taskState = state }

    private fun task(state: TaskState, type: TaskType, source: Network) =
        Task(taskType = type, status = StatusEnum.LOADING, protocol = FileProtocol.Network, protocolId = source.protocolId)
            .also { state.addOrUpdate(it) }

    private suspend fun upload(network: Network, target: StoragePath, bytes: ByteArray) {
        var sent = false
        assertTrue(network.uploadFromSource(target, bytes.size.toLong(), { _, _ -> }) {
            if (sent) null else bytes.also { sent = true }
        }.getOrThrow())
    }

    private suspend fun download(network: Network, file: FileSimpleInfo, target: Path): ByteArray {
        assertTrue(network.downloadFileToLocal(file, target.toString()) { _, _ -> }.getOrThrow())
        return Files.readAllBytes(target)
    }

    private suspend fun objects(provider: StorageProvider, root: StoragePath): List<NetworkFileEntry> {
        val path = root.path.trimEnd('/') + "/.folderspan-vault"
        return provider.list(StoragePath(path, root.reference?.forPath(root.path, path, true))).getOrThrow()
            .filter { it.name.endsWith(".object") }
    }

    private suspend fun readRaw(provider: StorageProvider, file: NetworkFileEntry, path: Path): ByteArray {
        assertTrue(provider.download(StoragePath(file.path, file.reference), path.toString(), file.size).getOrThrow())
        return Files.readAllBytes(path)
    }

    private inline fun <reified T> decode(value: JsonElement): T = try { Json.decodeFromJsonElement<T>(value) }
    catch (_: Exception) { throw IllegalArgumentException("Malformed private cross-location fixture") }

    private fun database(driver: JdbcSqliteDriver): FolderSpanDatabase {
        SettingsUtils.init(createSettings())
        FolderSpanDatabase.Schema.create(driver)
        DatabaseReady.markReady()
        val strings = object : ColumnAdapter<List<String>, String> {
            override fun decode(databaseValue: String) = databaseValue.split(',').filter(String::isNotEmpty)
            override fun encode(value: List<String>) = value.joinToString(",")
        }
        return FolderSpanDatabase(driver,
            DeviceAdapter = com.folderspan.db.Device.Adapter(typeAdapter = enumAdapter<DeviceType>()),
            DeviceConnectAdapter = DeviceConnect.Adapter(connectionTypeAdapter = enumAdapter<DeviceConnectType>(), categoryAdapter = enumAdapter<DeviceCategory>()),
            FileBookmarkAdapter = FileBookmark.Adapter(typeAdapter = enumAdapter<DrawerBookmarkType>(), protocolAdapter = enumAdapter<FileProtocol>()),
            FileFavoriteAdapter = FileFavorite.Adapter(enumAdapter<FileProtocol>()),
            FileRecentAdapter = FileRecent.Adapter(enumAdapter<FileProtocol>()),
            FileFilterAdapter = FileFilter.Adapter(typeAdapter = enumAdapter<FileFilterType>(), extensionsAdapter = strings),
            DeviceReceiveShareAdapter = DeviceReceiveShare.Adapter(enumAdapter<DeviceConnectType>()),
            FilePathPreferenceAdapter = FilePathPreference.Adapter(protocolAdapter = enumAdapter<FileProtocol>(), sortAdapter = enumAdapter<FileFilterSort>(), ignoreFilesAdapter = strings))
    }
    private inline fun <reified T : Enum<T>> enumAdapter() = object : ColumnAdapter<T, String> {
        override fun decode(databaseValue: String): T = enumValueOf(databaseValue)
        override fun encode(value: T) = value.name
    }

    private class ObjectCommitPermissionFault(
        private val process: Process,
        private val arguments: List<String>,
        private val control: Path,
    ) : AutoCloseable {
        var restored = false
            private set
        suspend fun awaitBlocked() = await("blocked")
        private suspend fun await(name: String) {
            withTimeout(20_000) {
                while (!Files.exists(control.resolve(name))) {
                    check(process.isAlive) { "The disposable permission-fault watcher exited before its event" }
                    delay(10)
                }
            }
        }
        override fun close() {
            Files.writeString(control.resolve("release"), "restore")
            if (!process.waitFor(5, TimeUnit.SECONDS)) {
                process.destroy()
                if (!process.waitFor(5, TimeUnit.SECONDS)) {
                    process.destroyForcibly()
                    check(process.waitFor(5, TimeUnit.SECONDS)) { "The disposable fault watcher did not stop" }
                }
            }
            if (!Files.exists(control.resolve("mode.json"))) {
                restored = true // The watcher failed before inspecting or changing permissions.
                return
            }
            val restore = faultCommand(arguments + "--restore").redirectErrorStream(true)
                .redirectOutput(control.resolve("restore.log").toFile()).start()
            if (!restore.waitFor(30, TimeUnit.SECONDS)) {
                restore.destroyForcibly()
                restore.waitFor(5, TimeUnit.SECONDS)
                error("Permission restore timed out; retained mode.json identifies only this fixture")
            }
            check(restore.exitValue() == 0) {
                "Could not restore the disposable namespace permissions; retained mode.json identifies only this fixture"
            }
            restored = true
        }
        companion object {
            private fun faultCommand(arguments: List<String>): ProcessBuilder {
                // Test HOME is private; explicit storage options select only the disposable service store.
                val podman = listOf("podman",
                    "--root", checkNotNull(System.getenv("FOLDERSPAN_ENCRYPTION_FAULT_PODMAN_GRAPH_ROOT")),
                    "--runroot", checkNotNull(System.getenv("FOLDERSPAN_ENCRYPTION_FAULT_PODMAN_RUN_ROOT")),
                    "--storage-driver", checkNotNull(System.getenv("FOLDERSPAN_ENCRYPTION_FAULT_PODMAN_DRIVER")))
                return ProcessBuilder(podman + arguments)
            }

            suspend fun start(case: String, resource: String, relative: String, user: String, container: String, control: Path): ObjectCommitPermissionFault {
                Files.createDirectories(control)
                val script = control.resolve("permission-fault.py")
                Files.writeString(script, PYTHON)
                val arguments = listOf("unshare", "python3", script.toString(), "--case", case,
                    "--resource", resource, "--relative", relative, "--user", user, "--container", container,
                    "--control", control.toString())
                val process = faultCommand(arguments).redirectErrorStream(true).redirectOutput(control.resolve("watcher.log").toFile()).start()
                val fault = ObjectCommitPermissionFault(process, arguments, control)
                try { fault.await("armed"); return fault }
                catch (error: Throwable) {
                    runCatching { fault.close() }.exceptionOrNull()?.let(error::addSuppressed)
                    throw error
                }
            }
            private val PYTHON = """
import argparse, ctypes, json, os, re, struct, subprocess, time
from pathlib import Path

p = argparse.ArgumentParser()
p.add_argument('--case', required=True)
p.add_argument('--resource', required=True)
p.add_argument('--relative', required=True)
p.add_argument('--user', default='')
p.add_argument('--container', default='')
p.add_argument('--control', required=True)
p.add_argument('--restore', action='store_true')
a = p.parse_args()
parts = a.relative.split('/')
if (len(parts) < 3 or any(part in ('', '.', '..') or '\\' in part for part in parts)
        or not re.fullmatch(r'cross-vault-[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}', parts[-3])
        or parts[-2:] != ['target', '.folderspan-vault']):
    raise RuntimeError('Refusing a permission fault outside its random acceptance namespace')
control = Path(a.control)
podman = ['podman',
    '--root', os.environ['FOLDERSPAN_ENCRYPTION_FAULT_PODMAN_GRAPH_ROOT'],
    '--runroot', os.environ['FOLDERSPAN_ENCRYPTION_FAULT_PODMAN_RUN_ROOT'],
    '--storage-driver', os.environ['FOLDERSPAN_ENCRYPTION_FAULT_PODMAN_DRIVER']]
if a.case == 'SFTP':
    merged = subprocess.check_output(podman + ['inspect', '--format', '{{.GraphDriver.Data.MergedDir}}', a.resource], text=True).strip()
    base = Path(merged) / 'srv/sftp'
elif a.case == 'Nextcloud':
    if not re.fullmatch(r'[A-Za-z0-9_-]+', a.user) or not re.fullmatch(r'[A-Za-z0-9][A-Za-z0-9_.-]+', a.container):
        raise RuntimeError('Invalid disposable Nextcloud data user')
    volume = subprocess.check_output(podman + ['volume', 'inspect', '--format', '{{.Mountpoint}}', a.resource], text=True).strip()
    base = Path(volume) / 'data' / a.user / 'files'
else:
    raise RuntimeError('Unsupported disposable permission-fault backend')
target = base / a.relative
state = control / 'mode.json'
if a.restore:
    if state.exists():
        saved = json.loads(state.read_text())
        if saved['target'] != str(target):
            raise RuntimeError('Permission restore target changed')
        os.chmod(target, saved['mode'])
        if a.case == 'Nextcloud':
            # chmod alone leaves the failed object's cached DELETE permission stale.
            scope = a.user + '/files/' + '/'.join(parts[:-2])
            subprocess.run(podman + ['exec', '--user', 'www-data', a.container, 'php', 'occ',
                'files:scan', '--path=' + scope, '--no-interaction'], check=True, timeout=25)
    raise SystemExit(0)
original = target.stat().st_mode & 0o7777
state.write_text(json.dumps({'target': str(target), 'mode': original}))
libc = ctypes.CDLL(None, use_errno=True)
libc.inotify_init1.argtypes = [ctypes.c_int]
libc.inotify_init1.restype = ctypes.c_int
libc.inotify_add_watch.argtypes = [ctypes.c_int, ctypes.c_char_p, ctypes.c_uint32]
libc.inotify_add_watch.restype = ctypes.c_int
fd = libc.inotify_init1(os.O_NONBLOCK | os.O_CLOEXEC)
if fd < 0 or libc.inotify_add_watch(fd, os.fsencode(target), 0x8 | 0x80) < 0:
    raise OSError(ctypes.get_errno(), 'Cannot watch the disposable object directory')
(control / 'armed').touch()
blocked = False
try:
    deadline = time.monotonic() + 180
    while time.monotonic() < deadline and not (control / 'release').exists():
        if blocked:
            time.sleep(0.02)
            continue
        try:
            data = os.read(fd, 65536)
        except BlockingIOError:
            time.sleep(0.002)
            continue
        offset = 0
        while offset + 16 <= len(data):
            wd, mask, cookie, length = struct.unpack_from('iIII', data, offset)
            name = data[offset + 16:offset + 16 + length].split(b'\0', 1)[0].decode('utf-8', 'replace')
            offset += 16 + length
            if name.endswith('.object') and mask & (0x8 | 0x80):
                os.chmod(target, original & ~0o222)
                (control / 'blocked').write_text(name)
                blocked = True
                break
    if not blocked:
        raise RuntimeError('No completed independent ciphertext object was observed')
finally:
    os.close(fd)
    os.chmod(target, original)

""".trimIndent()
        }
    }
}
