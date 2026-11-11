package com.folderspan.service.network

import com.folderspan.data.file.FileSimpleInfo
import com.folderspan.data.main.network.CloudDriveExtras
import com.folderspan.data.main.network.NetworkDriveExtras
import com.folderspan.test.createInMemorySettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okio.Path.Companion.toPath
import org.junit.Assume.assumeTrue
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.util.UUID
import kotlin.test.*

/** Opt-in real ENOSPC on a bounded private tmpfs; no remote service or ordinary disk is filled. */
class NetworkContentEncryptionDiskSpaceAcceptanceTest {
    @Test
    fun encryptionAndPublicationSurviveActualTmpfsExhaustion() = runBlocking(Dispatchers.IO) {
        val configured = System.getenv("FOLDERSPAN_ENCRYPTION_DISK_SPACE_ROOT").orEmpty()
        assumeTrue("Set a disposable 12 MiB tmpfs case directory", configured.isNotBlank())
        val root = Path.of(configured).toAbsolutePath().normalize()
        require(root.parent?.fileName?.toString() == "tmpfs" &&
            root.parent?.parent?.fileName?.toString()?.startsWith("folderspan-encryption-diskspace-") == true &&
            root.fileName.toString() == "case") { "Refusing a directory outside the private disk-space fixture" }
        require(root.toRealPath() == root && !Files.isSymbolicLink(root)) { "Fixture root must be a real directory" }
        val disk = Files.getFileStore(root)
        require(disk.type() == "tmpfs" && disk.totalSpace in (11L * MIB)..(13L * MIB)) {
            "Disk-space acceptance requires the dedicated 12 MiB tmpfs"
        }
        require(fileNames(root).isEmpty()) { "Disk-space fixture must be empty" }

        val store = TestStorageContentVaultStore()
        val settings = createInMemorySettings()
        val host = StorageContentHostContext(StorageContentKeyStore(settings), settings,
            root.resolve("cache").toString().toPath(), { store.provider() })
        val password = "disposable disk space content password".toCharArray()
        try {
            withTimeout(120_000) {
                val sourcePath = root.resolve("original-source.bin")
                val original = ByteArray(3 * MIB.toInt()) { ((it * 31 + 9) % 251).toByte() }
                Files.write(sourcePath, original)
                val source = FileSimpleInfo.nullFileSimpleInfo().copy(
                    name = sourcePath.fileName.toString(), path = sourcePath.toString(),
                    size = original.size.toLong(), updatedDate = Files.getLastModifiedTime(sourcePath).toMillis())
                val rawConfig = NetworkConnectionConfig("FTP", "disk-space-fixture.invalid", "fixture", "fixture",
                    extras = NetworkDriveExtras(cloud = CloudDriveExtras(connectionId = UUID.randomUUID().toString())))
                val reference = host.setupPassword(rawConfig, password).getOrThrow()
                val config = rawConfig.copy(extras = rawConfig.extras.copy(contentEncryption = reference))
                val filler = root.resolve("filler.bin")
                val uploadTask = 811L
                val uploadEntry = "disk-space-upload"

                fun stageSource(task: Long, stage: String) {
                    assertTrue(Path.of(stage).startsWith(root.resolve("cache").resolve(task.toString())))
                    Files.newOutputStream(Path.of(stage), StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING).use {
                        Files.copy(sourcePath, it)
                    }
                }

                val firstStage = host.createStage(uploadTask, uploadEntry).getOrThrow()
                stageSource(uploadTask, firstStage)
                writeFiller(filler)
                assertTrue(disk.usableSpace < original.size,
                    "The real filesystem must have less room than the complete ciphertext")
                val beforePrepare = fileNames(root)
                val beforeRemote = store.snapshot()
                val beforeWrites = store.writes
                val failedPrepare = host.prepareUpload(config, uploadTask, uploadEntry, source, firstStage)
                requireNoSpace(failedPrepare.exceptionOrNull())
                assertContentEquals(original, Files.readAllBytes(sourcePath), "The real original source must survive")
                assertEquals(beforeRemote, store.snapshot(), "ENOSPC must not publish any remote mutation")
                assertEquals(beforeWrites, store.writes)
                assertFalse(Files.exists(Path.of(firstStage)), "Input stages are consumed on failure")
                assertEquals(beforePrepare - root.relativize(Path.of(firstStage)).toString(), fileNames(root),
                    "The failed ciphertext candidate must be removed")
                assertTrue(host.uploadEncryptionEnabled(config, uploadTask, uploadEntry).getOrThrow(),
                    "Low space must not change the encrypted write policy")

                Files.delete(filler)
                val retryStage = host.createStage(uploadTask, uploadEntry).getOrThrow()
                stageSource(uploadTask, retryStage)
                val prepared = assertNotNull(host.prepareUpload(config, uploadTask, uploadEntry, source, retryStage).getOrThrow())
                assertTrue(prepared.size > original.size, "The retry must produce authenticated ciphertext")
                assertTrue(Path.of(prepared.path).fileName.toString().startsWith(".cipher-"))
                assertFalse(Files.readAllBytes(Path.of(prepared.path)).contentEquals(original))
                assertEquals(beforeRemote, store.snapshot())
                assertEquals(beforeWrites, store.writes)
                val target = StoragePath("/protected.bin")
                host.beginUpload(config, uploadTask, uploadEntry, target).getOrThrow()
                val objectPath = assertNotNull(prepared.target)
                assertTrue(store.provider().upload(prepared.path, objectPath, prepared.size).getOrThrow())
                host.recordUploaded(config, uploadTask, uploadEntry,
                    NetworkFileEntry(objectPath.path.substringAfterLast('/'), objectPath.path, false, prepared.size)).getOrThrow()
                assertFalse(store.files.containsKey(target.path), "No plaintext fallback may be written")
                val entry = assertNotNull(host.logicalList(config, StoragePath("/")).getOrThrow())
                    .single { it.name == "protected.bin" }
                val logical = FileSimpleInfo.nullFileSimpleInfo().copy(
                    name = entry.name, path = entry.path, size = entry.size, storageReference = entry.reference)
                val download = assertNotNull(host.resolveDownload(config, logical).getOrThrow())
                host.cleanup(uploadTask).getOrThrow()
                assertFalse(Files.exists(Path.of(prepared.path)))
                assertContentEquals(original, Files.readAllBytes(sourcePath))

                val downloadTask = 812L
                val destination = root.resolve("destination.bin")
                val oldDestination = "keep the previously published destination".encodeToByteArray()
                Files.write(destination, oldDestination)
                suspend fun downloadStage(): String {
                    val stage = host.createStage(downloadTask, "disk-space-download").getOrThrow()
                    assertTrue(store.provider().download(download.target, stage, download.size).getOrThrow())
                    return stage
                }
                val cipherStage = downloadStage()
                writeFiller(filler)
                assertTrue(disk.usableSpace < original.size,
                    "The decrypt output must exceed the actual remaining tmpfs capacity")
                val beforePublish = fileNames(root)
                val publishedRemote = store.snapshot()
                val publishedWrites = store.writes
                val failedPublish = host.publishDownload(config, downloadTask, download.file, cipherStage, destination.toString())
                requireNoSpace(failedPublish.exceptionOrNull())
                assertContentEquals(oldDestination, Files.readAllBytes(destination),
                    "A failed decrypt must not replace the old destination")
                assertContentEquals(original, Files.readAllBytes(sourcePath))
                assertEquals(publishedRemote, store.snapshot(), "Ciphertext and authenticated metadata stay unchanged")
                assertEquals(publishedWrites, store.writes)
                assertFalse(Files.exists(Path.of(cipherStage)), "Downloaded stages are consumed on failure")
                assertEquals(beforePublish - root.relativize(Path.of(cipherStage)).toString(), fileNames(root),
                    "Failed private plaintext output must be removed")

                Files.delete(filler)
                val retriedCipher = downloadStage()
                assertTrue(host.publishDownload(config, downloadTask, download.file, retriedCipher,
                    destination.toString()).getOrThrow())
                assertContentEquals(original, Files.readAllBytes(destination))
                assertContentEquals(original, Files.readAllBytes(sourcePath))
                assertEquals(publishedRemote, store.snapshot())
                assertEquals(publishedWrites, store.writes)
                assertFalse(Files.exists(Path.of(retriedCipher)))
                host.cleanup(downloadTask).getOrThrow()
            }
        } finally {
            password.fill('\u0000')
            try { host.close() } finally { deleteFixture(root) }
        }
        println("DISK_SPACE_ACCEPTANCE passed filesystem=tmpfs capacityMiB=12 prepare=ENOSPC publish=ENOSPC retry=encrypted cleanup=cleaned")
    }

    private fun requireNoSpace(error: Throwable?) {
        assertNotNull(error, "Actual ENOSPC must fail the operation")
        val causes = generateSequence(error) { it.cause }.take(16).toList()
        assertTrue(causes.any { it.message.orEmpty().contains("No space left on device", ignoreCase = true) },
            "Expected real ENOSPC; actual causes: " +
                causes.joinToString(" -> ") { it::class.simpleName + ": " + it.message.orEmpty() })
    }

    private fun writeFiller(path: Path) {
        val block = ByteArray(64 * 1024) { 0x5a }
        Files.newOutputStream(path, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE).use { output ->
            repeat(64) { output.write(block) }
        }
    }

    private fun fileNames(root: Path): Set<String> = Files.walk(root).use { paths ->
        paths.filter { it != root }.map {
            require(!Files.isSymbolicLink(it)) { "Unexpected symlink in private tmpfs fixture" }
            it
        }.filter { Files.isRegularFile(it, LinkOption.NOFOLLOW_LINKS) }
            .map { root.relativize(it).toString() }.toList().toSet()
    }

    private fun deleteFixture(root: Path) {
        Files.walk(root).use { paths ->
            paths.sorted(Comparator.reverseOrder()).forEach { Files.delete(it) }
        }
    }

    private companion object { const val MIB = 1024L * 1024L }
}
