package com.folderspan.service

import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.worker.WebWorkerDriver
import com.folderspan.ui.state.main.SyncBaseline
import com.folderspan.ui.state.main.SyncEndpointType
import com.folderspan.ui.state.main.SyncEntryState
import com.folderspan.ui.state.main.SyncFileRecovery
import com.folderspan.ui.state.main.SyncLocalStore
import com.folderspan.ui.state.main.SyncMode
import com.folderspan.ui.state.main.SyncPendingDelete
import com.folderspan.ui.state.main.SyncRecoveryEndpoint
import com.folderspan.ui.state.main.SyncRecoveryPhase
import com.folderspan.ui.state.main.SyncTask
import com.folderspan.ui.state.main.SyncRunItem
import com.folderspan.ui.state.main.SyncRunItemKind
import com.folderspan.ui.state.main.SyncScheduleType
import com.folderspan.ui.state.main.computeNextRun
import com.folderspan.utils.DatabaseReady
import com.folderspan.utils.currentSyncTimeZone
import com.folderspan.extensions.timestampToYMDHM
import com.folderspan.utils.executeAsListAwait
import kotlinx.browser.window
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import kotlinx.datetime.TimeZone
import org.w3c.dom.Worker
import kotlin.random.Random
import kotlin.time.Duration.Companion.minutes
import kotlin.time.TimeSource
import kotlin.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SyncDatabaseBrowserTest {
    @Test fun browserCalendarSchedulesUseNamedTimeZonesAndHandleBothDaylightSavingTransitions() {
        val task = SyncTask(1, "calendar", sourceType = SyncEndpointType.Local, sourcePath = "/source",
            targetType = SyncEndpointType.Local, targetPath = "/target", scheduleType = SyncScheduleType.Daily, scheduleTimeMinutes = 120)
        val now = Instant.parse("2026-11-02T12:00:00Z").toEpochMilliseconds()
        assertEquals(Instant.parse("2026-11-02T18:00:00Z").toEpochMilliseconds(), computeNextRun(task, now, TimeZone.of("Asia/Shanghai")))
        assertEquals(Instant.parse("2026-11-02T17:00:00Z").toEpochMilliseconds(), computeNextRun(task, now, TimeZone.of("Asia/Tokyo")))
        withBrowserTimeZoneForTest("Asia/Shanghai") {
            assertEquals(Instant.parse("2026-11-02T18:00:00Z").toEpochMilliseconds(), computeNextRun(task, now))
            assertEquals("2026-11-03 02:00", computeNextRun(task, now).timestampToYMDHM(currentSyncTimeZone()))
        }
        withBrowserTimeZoneForTest("Asia/Tokyo") {
            assertEquals(Instant.parse("2026-11-02T17:00:00Z").toEpochMilliseconds(), computeNextRun(task, now))
            assertEquals("2026-11-03 02:00", computeNextRun(task, now).timestampToYMDHM(currentSyncTimeZone()))
        }
        val newYork = TimeZone.of("America/New_York")
        assertEquals(Instant.parse("2026-03-08T07:30:00Z").toEpochMilliseconds(), computeNextRun(task.copy(scheduleTimeMinutes = 150),
            Instant.parse("2026-03-08T05:00:00Z").toEpochMilliseconds(), newYork))
        val repeated = task.copy(scheduleTimeMinutes = 90)
        assertEquals(Instant.parse("2026-11-01T05:30:00Z").toEpochMilliseconds(), computeNextRun(repeated,
            Instant.parse("2026-11-01T04:00:00Z").toEpochMilliseconds(), newYork))
        assertEquals(Instant.parse("2026-11-02T06:30:00Z").toEpochMilliseconds(), computeNextRun(repeated,
            Instant.parse("2026-11-01T05:31:00Z").toEpochMilliseconds(), newYork))
    }

    @Test fun hundredThousandBaselinesCommitInBoundedBatchesAndReopenWithStablePages() = runTest(timeout = 5.minutes) {
        println("SQL.js 100000 starting schema")
        val namespace = "https://sync-100000-${Random.nextInt(1, Int.MAX_VALUE)}.invalid"
        var worker = createWorker(namespace)
        var driver: SqlDriver = WebWorkerDriver(worker)
        fun baseline(index: Int): SyncBaseline {
            val state = SyncEntryState(false, index.toLong(), 17_000L + index * 1000L)
            return SyncBaseline(index.toString().padStart(6, '0'), mapOf("source" to state, "target" to state))
        }
        try {
            createSchemaWithAwait(driver)
            println("SQL.js 100000 schema ready")
            DatabaseReady.markReady()
            val task = SyncTask(1, "large browser baseline", sourceType = SyncEndpointType.Local, sourcePath = "/source",
                targetType = SyncEndpointType.Local, targetPath = "/target", mode = SyncMode.Bidirectional, ownerDeviceId = "browser")
            val store = SyncLocalStore(createDatabase(driver), driver) {}
            val fingerprint = store.reconcile(task).fingerprint
            store.confirmPreview(task.id, fingerprint)
            store.saveScheduleTimeZone(task.id, fingerprint, TimeZone.of("Asia/Shanghai"))
            val committing = TimeSource.Monotonic.markNow()
            for (start in 0 until 100_000 step 500) {
                store.commitVerified(task.id, fingerprint, (start until start + 500).map(::baseline))
                if (start == 0 || (start + 500) % 5_000 == 0) println("SQL.js large baseline committed: ${start + 500}/100000")
            }
            println("SQL.js 100000 commits elapsed: ${committing.elapsedNow().inWholeMilliseconds}ms")
            assertEquals(100_000L, scalar(driver, "SELECT count(*) FROM SyncBaselineEntry"))
            assertEquals(baseline(99_999), store.baselinePage(task.id, fingerprint, offset = 99_999).single())
            driver.close()
            worker.terminate()
            val reopening = TimeSource.Monotonic.markNow()
            worker = createWorker(namespace)
            driver = WebWorkerDriver(worker)
            createSchemaWithAwait(driver)
            val reopened = SyncLocalStore(createDatabase(driver), driver) {}
            assertTrue(reopened.localState(task.id)!!.previewConfirmed)
            assertTrue(reopened.localState(task.id)!!.baselineEstablished)
            assertEquals("Asia/Shanghai", reopened.localState(task.id)!!.scheduleTimeZone)
            for (offset in listOf(0, 49_500, 99_500))
                assertEquals((offset until offset + 500).map(::baseline), reopened.baselinePage(task.id, fingerprint, offset.toLong()))
            assertTrue(reopened.baselinePage(task.id, fingerprint, offset = 100_000).isEmpty())
            assertEquals(100_000L, scalar(driver, "SELECT count(*) FROM SyncBaselineEntry"))
            println("SQL.js 100000 reopen and pages elapsed: ${reopening.elapsedNow().inWholeMilliseconds}ms")
        } finally {
            driver.close()
            worker.terminate()
        }
    }

    private suspend fun scalar(driver: SqlDriver, sql: String): Long = driver.executeQuery(null, sql, { cursor ->
        QueryResult.AsyncValue { check(cursor.next().await()); cursor.getLong(0)!! }
    }, 0).await()

    private fun createWorker(namespace: String) = Worker(window.location.origin + "/base/kotlin/sqljs.worker.js?runtime=" + namespace)

    @Test fun workerInitializationMigratesAndDurablyCommitsBeforeReopening() = runTest {
        val tables = listOf("SyncBaselineEntry", "SyncPendingDeletion", "SyncTaskLocalState", "SyncFileRecovery", "SyncRunDetail")
        for (oldVersion in listOf(0, 1, 2)) {
            val namespace = "https://sync-schema-${Random.nextInt(1, Int.MAX_VALUE)}.invalid"
            var worker = createWorker(namespace)
            var driver: SqlDriver = WebWorkerDriver(worker)
            try {
                println("SQL.js migration case: version=$oldVersion")
                if (oldVersion != 0) {
                    createSchemaWithAwait(driver)
                    driver.execute(null, "CREATE TABLE ExistingMarker(value INTEGER NOT NULL)", 0).await()
                    driver.execute(null, "INSERT INTO ExistingMarker VALUES (42)", 0).await()
                    for (table in tables) driver.execute(null, "DROP TABLE $table", 0).await()
                    driver.execute(null, "PRAGMA user_version = $oldVersion", 0).await()
                }
                val ready = CompletableDeferred<Unit>()
                val store = SyncLocalStore(createDatabase(driver), driver) { ready.await() }
                val task = SyncTask(1, "browser", sourceType = SyncEndpointType.Local, sourcePath = "/source",
                    targetType = SyncEndpointType.Local, targetPath = "/target", mode = SyncMode.Bidirectional, ownerDeviceId = "browser")
                val waiting = async { store.reconcile(task) }
                yield()
                assertFalse(waiting.isCompleted)
                println("SQL.js starting initializer: version=$oldVersion")
                createSchemaWithAwait(driver)
                println("SQL.js initializer returned: version=$oldVersion")
                DatabaseReady.markReady()
                ready.complete(Unit)
                val state = waiting.await()
                println("SQL.js schema ready: version=$oldVersion")
                assertEquals(3L, scalar(driver, "PRAGMA user_version"))
                for (table in tables) assertEquals(1L, scalar(driver, "SELECT count(*) FROM sqlite_master WHERE type='table' AND name='$table'"))
                assertEquals(1L, scalar(driver, "SELECT count(*) FROM sqlite_master WHERE type='index' AND name='SyncFileRecoveryTaskGroup'"))
                if (oldVersion != 0) assertEquals(42L, scalar(driver, "SELECT value FROM ExistingMarker"))
                val file = SyncEntryState(false, 42, 17_000)
                val entries = (0 until 600).map { SyncBaseline(it.toString().padStart(3, '0'), mapOf("source" to file, "target" to file)) }
                store.confirmPreview(task.id, state.fingerprint)
                store.saveScheduleTimeZone(task.id, state.fingerprint, TimeZone.of("Asia/Tokyo"))
                store.commitVerified(task.id, state.fingerprint, entries.take(500))
                store.commitVerified(task.id, state.fingerprint, entries.drop(500))
                store.replacePendingDeletions(task.id, state.fingerprint, 7, listOf(SyncPendingDelete("target", "old", mapOf("target" to file))))
                val recoveries = (0..1).map { index -> SyncFileRecovery("record-$index", task.id, state.fingerprint,
                    "browser", "group-$index", "target", SyncRecoveryEndpoint(SyncEndpointType.Local, "", "/target"),
                    entries[index].relativePath, "/target/file-$index", "/target/tmp-$index", "/target/old-$index", file, file, true) }
                recoveries.forEach { store.saveRecovery(it) }
                store.saveRecovery(recoveries.first().copy(phase = SyncRecoveryPhase.Verified))
                store.commitVerified(task.id, state.fingerprint, entries.take(1), completedRecoveryIds = setOf(recoveries.first().id))
                assertEquals(listOf(recoveries.last()), store.recoveries(task.id))
                val details = (0 until 600).map { SyncRunItem("result-$it", it != 599, "done", kind = SyncRunItemKind.Recycle) }
                details.chunked(500).forEachIndexed { batch, rows -> store.appendRunDetails(task.id, "browser", 7, batch * 500L, rows) }
                println("SQL.js baseline committed: version=$oldVersion, entries=600")
                driver.close()
                worker.terminate()
                worker = createWorker(namespace)
                driver = WebWorkerDriver(worker)
                createSchemaWithAwait(driver)
                val reopenedDatabase = createDatabase(driver)
                val reopened = SyncLocalStore(reopenedDatabase, driver) {}
                assertTrue(reopened.localState(task.id)!!.previewConfirmed)
                assertTrue(reopened.localState(task.id)!!.baselineEstablished)
                assertEquals("Asia/Tokyo", reopened.localState(task.id)!!.scheduleTimeZone)
                assertEquals(entries.take(500), reopened.baselinePage(task.id, state.fingerprint))
                assertEquals(entries.drop(500), reopened.baselinePage(task.id, state.fingerprint, offset = 500))
                assertEquals(1L, reopened.pendingDeletionCount(task.id))
                assertEquals(listOf(details.last()) + details.take(99), reopened.runDetailPage(task.id, "browser", 7, 600).map { it.item })
                assertEquals(details.subList(99, 199), reopened.runDetailPage(task.id, "browser", 7, 600, offset = 100).map { it.item })
                assertTrue(reopened.runDetailPage(task.id, "wrong-owner", 7, 600).isEmpty())
                assertEquals(listOf(recoveries.last()), reopened.recoveries(task.id))
                assertEquals(600, reopenedDatabase.syncBaselineEntryQueries.selectPage(task.id, state.fingerprint, 1000, 0).executeAsListAwait().size)
                println("SQL.js reopen verified: version=$oldVersion, entries=600")
                if (oldVersion != 0) assertEquals(42L, scalar(driver, "SELECT value FROM ExistingMarker"))
            } finally {
                driver.close()
                worker.terminate()
            }
        }
    }
}
