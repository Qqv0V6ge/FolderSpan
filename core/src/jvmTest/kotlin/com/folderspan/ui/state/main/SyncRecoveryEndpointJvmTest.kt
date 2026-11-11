package com.folderspan.ui.state.main

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.folderspan.data.StatusEnum
import com.folderspan.data.main.Local
import com.folderspan.service.mcp.http.createMcpHttpTestDatabase
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SyncRecoveryEndpointJvmTest {
    @Test fun parallelRecoveriesShareOnlyRecheckedDirectoryParents(): Unit = runBlocking {
        repeat(8) {
            fixture { root, endpoint, io ->
                coroutineScope {
                    val start = CompletableDeferred<Unit>()
                    val jobs = (0 until 16).map { index -> async(Dispatchers.Default) {
                        start.await()
                        io.ensureParent(endpoint, root.resolve(".folderspan-recycle/device/task/run-$index/original.txt").toString())
                    } }
                    start.complete(Unit)
                    jobs.awaitAll()
                }
                repeat(16) { index -> assertTrue(Files.isDirectory(root.resolve(".folderspan-recycle/device/task/run-$index"))) }
            }
        }
    }

    private val operation = Task(taskType = TaskType.Copy, status = StatusEnum.LOADING)
    private val proof = SyncEntryState(false, 0, 0, contentSha256 = "0".repeat(64))

    private suspend fun fixture(block: suspend (Path, SyncRecoveryEndpoint, SyncRecoveryEndpointOperations) -> Unit) {
        val root = Files.createTempDirectory("sync-recovery-io")
        try {
            val endpoint = SyncRecoveryEndpoint(SyncEndpointType.Local, "", root.toString())
            block(root, endpoint, SyncRecoveryEndpointOperations(SyncEndpointFileOperations(), operation, mapOf(endpoint to Local())))
        } finally { root.toFile().deleteRecursively() }
    }

    @Test fun realMoveAcknowledgementLossSurvivesDatabaseCloseAndReopen(): Unit = runBlocking {
        for (interruptAt in listOf("original", "temporary")) fixture { root, endpoint, io ->
            val final = Files.writeString(root.resolve("a.txt"), "old version")
            val temporary = Files.writeString(root.resolve(".folderspan-tmp-device-1-new"), "new version")
            val oldPath = root.resolve(".folderspan-recycle/device/1/run/old")
            val task = SyncTask(1, "sync", sourceType = SyncEndpointType.Local, sourcePath = "/source",
                targetType = SyncEndpointType.Local, targetPath = root.toString(), mode = SyncMode.Mirror, ownerDeviceId = "device")
            val url = "jdbc:sqlite:${root.resolve("journal.sqlite")}"
            var driver = JdbcSqliteDriver(url)
            try {
                var store = SyncLocalStore(createMcpHttpTestDatabase(driver), driver)
                val fingerprint = store.reconcile(task).fingerprint
                val original = checkNotNull(io.read(endpoint, final.toString(), proof))
                val staged = checkNotNull(io.read(endpoint, temporary.toString(), proof))
                val record = SyncFileRecovery("replace", 1, fingerprint, "device", "run", "target", endpoint,
                    "a.txt", final.toString(), temporary.toString(), oldPath.toString(), original, staged, true, stagedState = staged)
                val interrupted = object : SyncRecoveryFileOperations by io {
                    override suspend fun move(endpoint: SyncRecoveryEndpoint, from: String, to: String) {
                        io.move(endpoint, from, to)
                        if (from == (if (interruptAt == "original") final else temporary).toString()) error("lost acknowledgement")
                    }
                }
                assertFailsWith<IllegalStateException> { executeSyncRecoveryGroup(listOf(record), interrupted, store::saveRecoveryGroup, {}) }
                assertEquals(1, store.recoveries(1).size)
                driver.close()
                driver = JdbcSqliteDriver(url)
                store = SyncLocalStore(createMcpHttpTestDatabase(driver, createSchema = false), driver)
                val loaded = store.recoveries(1)
                val complete = executeSyncRecoveryGroup(loaded, io, store::saveRecoveryGroup, {})
                assertEquals("old version", Files.readString(oldPath))
                assertEquals("new version", Files.readString(final))
                assertFalse(Files.exists(temporary))
                assertTrue(complete.all { it.phase == SyncRecoveryPhase.Verified })
                store.commitVerified(1, fingerprint, listOf(SyncBaseline("a.txt", mapOf("target" to complete.single().placedState))),
                    completedRecoveryIds = setOf("replace"))
                assertTrue(store.recoveries(1).isEmpty())
                assertEquals(complete.single().placedState, store.baselinePage(1, fingerprint).single().participants["target"])
            } finally { driver.close() }
        }
    }

    @Test fun failedPlacementRestoresRealOriginalAndKeepsTemporaryForRetry(): Unit = runBlocking {
        fixture { root, endpoint, io ->
            val final = Files.writeString(root.resolve("a"), "old")
            val temporary = Files.writeString(root.resolve("tmp"), "new")
            val oldPath = root.resolve("old")
            val record = SyncFileRecovery("replace", 1, "definition", "device", "run", "target", endpoint,
                "a", final.toString(), temporary.toString(), oldPath.toString(), io.read(endpoint, final.toString(), proof),
                io.read(endpoint, temporary.toString(), proof), false, stagedState = io.read(endpoint, temporary.toString(), proof))
            val journal = linkedMapOf<String, SyncFileRecovery>()
            val failing = object : SyncRecoveryFileOperations by io {
                override suspend fun move(endpoint: SyncRecoveryEndpoint, from: String, to: String) {
                    if (from == temporary.toString()) error("placement rejected")
                    io.move(endpoint, from, to)
                }
            }
            val save: suspend (List<SyncFileRecovery>) -> Unit = { records -> records.forEach { journal[it.id] = it } }
            assertFailsWith<IllegalStateException> { executeSyncRecoveryGroup(listOf(record), failing, save, {}) }
            assertEquals("old", Files.readString(final))
            assertEquals("new", Files.readString(temporary))
            assertFalse(Files.exists(oldPath))
            assertEquals(1, journal.values.single().attempt)
            executeSyncRecoveryGroup(journal.values.toList(), io, save, {})
            assertEquals("new", Files.readString(final))
            assertFalse(Files.exists(oldPath))
        }
    }

    @Test fun directoryCleanupRejectsChangedTreesAndNeverUsesRecursiveDeletion(): Unit = runBlocking {
        fixture { root, endpoint, io ->
            val directory = Files.createDirectory(root.resolve("old"))
            Files.createDirectory(directory.resolve("nested"))
            Files.writeString(directory.resolve("nested/file"), "keep")
            val snapshot = io.tree(endpoint, directory.toString(), emptyMap())
            val state = snapshot.getValue("")
            val arriving = Files.writeString(directory.resolve("arriving"), "new user file")
            assertFailsWith<IllegalStateException> { io.remove(endpoint, directory.toString(), state, snapshot) }
            assertEquals("keep", Files.readString(directory.resolve("nested/file")))
            assertEquals("new user file", Files.readString(arriving))
            Files.delete(arriving)
            io.remove(endpoint, directory.toString(), state, snapshot)
            assertFalse(Files.exists(directory))
        }
    }

    @Test fun recoveryRejectsOutsideRootsLinksAmbiguousParentsAndOccupiedDestinations(): Unit = runBlocking {
        fixture { root, endpoint, io ->
            val file = Files.writeString(root.resolve("a"), "keep")
            val state = checkNotNull(io.read(endpoint, file.toString(), proof))
            assertFailsWith<IllegalArgumentException> { io.remove(endpoint, root.toString(), state, emptyMap()) }
            assertFailsWith<IllegalArgumentException> { io.read(endpoint, root.resolve("../escape").toString(), null) }
            assertFailsWith<IllegalArgumentException> { io.read(endpoint, root.toString() + "-other/file", null) }
            assertFailsWith<IllegalStateException> { io.ensureParent(endpoint, file.resolve("child/new").toString()) }
            val target = Files.writeString(root.resolve("occupied"), "arriving")
            assertFailsWith<IllegalStateException> { io.move(endpoint, file.toString(), target.toString()) }
            val linked = Files.createSymbolicLink(root.resolve("link"), root)
            assertFailsWith<IllegalStateException> { io.read(endpoint, linked.resolve("a").toString(), proof) }
            assertFailsWith<IllegalStateException> { io.tree(endpoint, linked.toString(), emptyMap()) }
            assertEquals("keep", Files.readString(file))
            assertEquals("arriving", Files.readString(target))
        }
    }
}
