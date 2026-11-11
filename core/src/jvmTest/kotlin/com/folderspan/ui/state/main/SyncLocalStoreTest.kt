package com.folderspan.ui.state.main

import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.folderspan.db.FolderSpanDatabase
import com.folderspan.service.mcp.http.createMcpHttpTestDatabase
import com.folderspan.data.file.FileSimpleInfo
import com.folderspan.service.network.StorageReference
import com.folderspan.utils.executeAsOneOrNullAwait
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import kotlinx.datetime.TimeZone
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SyncLocalStoreTest {
    @Test fun resultDetailsPageFailuresFirstWithinCapturedWindowAndKeepOwnerHistoryIsolated() = runBlocking {
        withStore { store ->
            val original = task()
            store.reconcile(original)
            val entries = (0 until 10_013).map { SyncRunItem("file-$it", it % 101 != 0, "result",
                kind = if (it % 2 == 0) SyncRunItemKind.Delete else SyncRunItemKind.Copy) }
            entries.chunked(500).forEachIndexed { batch, rows -> store.appendRunDetails(1, "device", 7, batch * 500L, rows) }
            assertFailsWith<IllegalArgumentException> { store.appendRunDetails(1, "device", 7, 0, entries.take(501)) }
            val captured = 10_000L
            val expected = entries.take(captured.toInt()).withIndex().sortedBy { it.value.success }
            val actual = buildList {
                var offset = 0L
                while (true) {
                    val page = store.runDetailPage(1, "device", 7, captured, offset, 100)
                    assertTrue(page.size <= 100)
                    addAll(page)
                    if (page.size < 100) break
                    offset += page.size
                }
            }
            assertEquals(expected.map { SyncRunDetailRow(it.index.toLong(), it.value) }, actual)
            assertEquals(expected.filter { it.value.kind == SyncRunItemKind.Delete }.take(500).map { it.index.toLong() },
                store.runDetailPage(1, "device", 7, captured, limit = 10_000, kind = SyncRunItemKind.Delete).map { it.ordinal })
            assertTrue(store.runDetailPage(1, "other", 7, captured).isEmpty())
            store.reconcile(original.copy(sourcePath = "/changed", mode = SyncMode.Update))
            assertEquals(100, store.runDetailPage(1, "device", 7, captured).size)
            store.appendRunDetails(1, "device", 8, 0, entries.take(1))
            store.pruneRunDetails(1, "device", setOf(8))
            assertTrue(store.runDetailPage(1, "device", 7, captured).isEmpty())
            store.deleteRunDetails(1, "device", setOf(8))
            assertTrue(store.runDetailPage(1, "device", 8, 1).isEmpty())
            store.appendRunDetails(1, "device", 9, 0, entries.take(1))
            store.reconcile(original.copy(ownerDeviceId = "new-device"))
            assertTrue(store.runDetailPage(1, "device", 9, 1).isEmpty())
        }
    }

    @Test fun recorderCountsAllDetailsBoundsHeadersAndReportsStorageFailureWithoutLosingResults() = runBlocking {
        withStore { store ->
            val task = task()
            val recorder = SyncRunDetails(task, 12, store)
            val items = (0 until 1234).map { SyncRunItem("file-$it", it != 1233, "done", kind = SyncRunItemKind.Copy) }
            recorder.add(items)
            val record = recorder.finish(SyncRunRecord(12, 1, "manual", 12, 13, SyncRunStatus.PartialSuccess, 1234, 1233, 1, items = items))
            assertEquals(listOf(SyncRunResultCount(SyncRunItemKind.Copy, 1233, 1)), record.resultCounts)
            assertEquals(1234L, record.detailCount)
            assertEquals(0L, record.unsavedDetailCount)
            assertEquals(200, record.items.size)
            assertFalse(record.items.first().success)
            assertEquals("file-1233", store.runDetailPage(1, "device", 12, record.detailCount).first().item.path)
        }
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        try {
            val store = SyncLocalStore(createMcpHttpTestDatabase(driver), driver) { error("unavailable") }
            val recorder = SyncRunDetails(task(), 3, store)
            recorder.add(SyncRunItem("done", true, "done", kind = SyncRunItemKind.Delete))
            val record = recorder.finish(SyncRunRecord(3, 1, "manual", 3, 4, SyncRunStatus.Success, 1, 1, 0))
            assertEquals(0L, record.detailCount)
            assertEquals(1L, record.unsavedDetailCount)
            assertEquals(listOf(SyncRunResultCount(SyncRunItemKind.Delete, 1, 0)), record.resultCounts)
        } finally { driver.close() }
    }

    @Test fun fixedTargetsStayLocalAndInvalidationKeepsRecoveryAndCalendarZone() = runBlocking {
        withStore { store ->
            val fixed = StorageDestination(ref = "chosen", path = "/chosen", networkEntryId = 2, connectionBinding = "account")
            val group = task().copy(selectRecommended = true, destinations = listOf(fixed, fixed.copy(ref = "other", path = "/other", networkEntryId = 3)))
            val fingerprint = store.reconcile(group).fingerprint
            store.confirmPreview(group.id, fingerprint, listOf(fixed), "source-account")
            store.saveScheduleTimeZone(group.id, fingerprint, TimeZone.of("Asia/Shanghai"))
            store.commitVerified(group.id, fingerprint, listOf(SyncBaseline("a", mapOf("source" to file))))
            store.replacePendingDeletions(group.id, fingerprint, 9, listOf(SyncPendingDelete("target-0", "a", emptyMap())))
            val journal = recovery(fingerprint)
            store.saveRecovery(journal)
            val same = store.reconcile(group.copy(name = "renamed"))
            assertEquals(listOf(fixed), same.fixedTargets)
            assertEquals("source-account", same.fixedSourceBinding)
            assertTrue(same.confirms(group))
            store.invalidateFixedTargets(group.id, fingerprint)
            val invalid = store.localState(group.id)!!
            assertFalse(invalid.confirms(group))
            assertFalse(invalid.baselineEstablished)
            assertTrue(invalid.fixedTargets.isEmpty())
            assertEquals("", invalid.fixedSourceBinding)
            assertEquals("Asia/Shanghai", invalid.scheduleTimeZone)
            assertTrue(store.baselinePage(group.id, fingerprint).isEmpty())
            assertEquals(0L, store.pendingDeletionCount(group.id))
            assertEquals(listOf(journal), store.recoveries(group.id))
            store.confirmPreview(group.id, fingerprint, listOf(fixed), "source-account")
            assertTrue(store.reconcile(group.copy(destinations = group.destinations.reversed())).fixedTargets.isEmpty())
        }
    }

    @Test fun advancingAndCommittingAGroupDoesNotDecodeOrDiscardAnotherGroupsBrokenPayload() = runBlocking {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        try {
            val database = createMcpHttpTestDatabase(driver)
            val store = SyncLocalStore(database, driver)
            val fingerprint = store.reconcile(task()).fingerprint
            val record = recovery(fingerprint)
            store.saveRecovery(record)
            database.syncFileRecoveryQueries.upsert("other", 1, fingerprint, "other-group", "{broken").await()
            store.saveRecovery(record.copy(phase = SyncRecoveryPhase.Verified))
            store.commitVerified(1, fingerprint, listOf(SyncBaseline("a", mapOf("target" to file))),
                completedRecoveryIds = setOf(record.id))
            assertNull(database.syncFileRecoveryQueries.selectRecord(record.id).executeAsOneOrNullAwait())
            assertEquals("{broken", database.syncFileRecoveryQueries.selectRecord("other").executeAsOneOrNullAwait()?.payload)
            assertEquals(listOf("a"), store.baselinePage(1, fingerprint).map { it.relativePath })
        } finally { driver.close() }
    }

    @Test fun committing500PathsWith1000RecoveryRecordsKeepsGroupsWholeAndBoundsSqlBindings() = runBlocking {
        withStore { store ->
            val fingerprint = store.reconcile(task()).fingerprint
            val completed = mutableSetOf<String>()
            val baselines = (0 until 500).map { index ->
                val path = "file-$index"
                val group = (0..1).map { participant -> recovery(fingerprint).copy(id = "$path-$participant", groupId = path,
                    participant = "target-$participant", relativePath = path, logicalPath = path,
                    finalPath = "/target-$participant/$path", temporaryPath = "/target-$participant/tmp-$index",
                    oldPath = "/target-$participant/old-$index", endpoint = SyncRecoveryEndpoint(SyncEndpointType.Local, "", "/target-$participant")) }
                store.saveRecoveryGroup(group)
                store.saveRecoveryGroup(group.map { it.copy(phase = SyncRecoveryPhase.Verified) })
                completed += group.map { it.id }
                SyncBaseline(path, group.associate { it.participant to file })
            }
            store.commitVerified(1, fingerprint, baselines, completedRecoveryIds = completed)
            assertTrue(store.recoveries(1).isEmpty())
            assertEquals(500, store.baselinePage(1, fingerprint).size)
        }
    }

    @Test fun groupBaselineAndFrozenRecoveryKeepEveryParticipantAndActualCopyPath() = runBlocking {
        withStore { store ->
            val group = task().copy(mode = SyncMode.BackupThenClean,
                destinations = listOf(StorageDestination(ref = "first", path = "/one"), StorageDestination(ref = "second", path = "/two")))
            val fingerprint = store.reconcile(group).fingerprint
            val states = mapOf("source" to file, "target-0" to file.copy(modifiedAt = 12_000), "target-1" to file.copy(modifiedAt = 14_000))
            val copies = mapOf("target-0" to SyncBackupCopy(file, "a(1)", states.getValue("target-0")),
                "target-1" to SyncBackupCopy(file, "a(2)", states.getValue("target-1")))
            val baseline = SyncBaseline("a", states, copies, StorageReference("current", "parent"))
            store.commitVerified(group.id, fingerprint, listOf(baseline))
            assertEquals(listOf(baseline), store.baselinePage(group.id, fingerprint))
            val locations = states.keys.associateWith { SyncRecoveryLocation(SyncRecoveryEndpoint(SyncEndpointType.Local, "", "/$it"), "/$it/a") }
            val record = recovery(fingerprint).copy(expectedParticipants = states, participantLocations = locations,
                previewFingerprint = syncPreviewFingerprint(group), backupPaths = copies.mapValues { it.value.relativePath },
                expectedBackupStates = states - "source", selectedSourceReference = baseline.selectedSourceReference)
            store.saveRecovery(record)
            assertEquals(listOf(record), store.recoveries(group.id))
            store.reconcile(group.copy(destinations = group.destinations.reversed()))
            assertEquals(listOf(record), store.recoveries(group.id))
            assertFalse(store.localState(group.id)!!.baselineEstablished)
            assertFalse(store.localState(group.id)!!.previewConfirmed)
        }
    }

    @Test fun baselineLookupKeepsTheLatestSelectedSourceBindingAfterDeletion() = runBlocking {
        withStore { store ->
            val fingerprint = store.reconcile(task()).fingerprint
            val baseline = SyncBaseline("a", mapOf("source" to null, "target" to file),
                selectedSourceReference = StorageReference("replacement", "parent"))
            store.commitVerified(1, fingerprint, listOf(baseline))
            assertEquals(baseline, store.baselineEntry(1, fingerprint, "a"))
            assertNull(store.baselineEntry(1, fingerprint, "missing"))
            store.reconcile(task().copy(targetPath = "/changed"))
            assertFailsWith<IllegalStateException> { store.baselineEntry(1, fingerprint, "a") }
        }
    }

    @Test fun recoveryGroupsAreAtomicAndCannotChangeTheirFrozenOperations() = runBlocking {
        withStore { store ->
            val fingerprint = store.reconcile(task()).fingerprint
            val first = recovery(fingerprint)
            val second = first.copy(id = "second", participant = "another", finalPath = "/another/a",
                temporaryPath = "/another/tmp", oldPath = "/another/old", endpoint = first.endpoint.copy(rootPath = "/another"))
            assertFailsWith<IllegalStateException> { store.saveRecoveryGroup(listOf(first, second.copy(phase = SyncRecoveryPhase.Placed))) }
            assertTrue(store.recoveries().isEmpty())
            store.saveRecoveryGroup(listOf(first, second))
            assertFailsWith<IllegalStateException> { store.saveRecovery(first.copy(id = "unplanned")) }
            assertFailsWith<IllegalStateException> { store.saveRecovery(first.copy(finalPath = "/unrelated/a")) }
            val advanced = first.copy(phase = SyncRecoveryPhase.OldPreserved, oldStoredState = file.copy(modifiedAt = file.modifiedAt + 1000))
            store.saveRecovery(advanced)
            assertFailsWith<IllegalStateException> { store.saveRecovery(first) }
            val restored = first.copy(attempt = 1, restoredState = file)
            store.saveRecovery(restored)
            assertFailsWith<IllegalStateException> { store.saveRecovery(advanced) }
            val resumed = restored.copy(phase = SyncRecoveryPhase.OldPreserved, oldStoredState = file)
            store.saveRecovery(resumed)
            store.reconcile(task().copy(targetPath = "/new-definition"))
            store.saveRecovery(resumed.copy(phase = SyncRecoveryPhase.Placed, placedState = file))
            assertFailsWith<IllegalStateException> { store.saveRecovery(first.copy(id = "new-old-operation", groupId = "different")) }
            store.deleteTaskState(task().id)
            store.saveRecovery(resumed.copy(phase = SyncRecoveryPhase.Verified, placedState = file))
            assertEquals(SyncRecoveryPhase.Verified, store.recoveries().first { it.id == first.id }.phase)
            assertEquals(2, store.recoveries().size)
            assertFailsWith<IllegalStateException> { store.commitVerified(task().id, fingerprint, emptyList()) }
        }
    }

    @Test fun baselineCommitCannotDiscardAnUnverifiedOrPartialRecoveryGroup() = runBlocking {
        withStore { store ->
            val fingerprint = store.reconcile(task()).fingerprint
            val first = recovery(fingerprint)
            val second = first.copy(id = "second", participant = "another", finalPath = "/another/a")
            store.saveRecoveryGroup(listOf(first, second))
            val baseline = SyncBaseline("a", mapOf("source" to file, "target" to file))
            val bothIds = setOf(first.id, second.id)
            assertFailsWith<IllegalStateException> { store.commitVerified(1, fingerprint, listOf(baseline), completedRecoveryIds = bothIds) }
            assertTrue(store.baselinePage(1, fingerprint).isEmpty())
            store.saveRecoveryGroup(listOf(first.copy(phase = SyncRecoveryPhase.Verified), second.copy(phase = SyncRecoveryPhase.Verified)))
            assertFailsWith<IllegalStateException> { store.commitVerified(1, fingerprint, listOf(baseline), completedRecoveryIds = setOf(first.id)) }
            assertFailsWith<IllegalStateException> { store.commitVerified(1, fingerprint, listOf(baseline.copy(relativePath = "other")), completedRecoveryIds = bothIds) }
            assertEquals(2, store.recoveries().size)
            assertTrue(store.baselinePage(1, fingerprint).isEmpty())
            store.commitVerified(1, fingerprint, listOf(baseline), completedRecoveryIds = bothIds)
            assertTrue(store.recoveries().isEmpty())
            assertEquals(listOf(baseline), store.baselinePage(1, fingerprint))
        }
    }

    private fun task() = SyncTask(1, "sync", sourceType = SyncEndpointType.Local, sourcePath = "/source",
        targetType = SyncEndpointType.Local, targetPath = "/target", mode = SyncMode.Bidirectional, ownerDeviceId = "device")
    private val file = SyncEntryState(false, 42, 1_700_000_000_000)
    private fun recovery(fingerprint: String) = SyncFileRecovery("recovery", 1, fingerprint, "device", "run", "target",
        SyncRecoveryEndpoint(SyncEndpointType.Local, "", "/target"), "a", "/target/a", "/target/tmp", "/target/old", file, file, true)

    private suspend fun withStore(block: suspend (SyncLocalStore) -> Unit) {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        try { block(SyncLocalStore(createMcpHttpTestDatabase(driver), driver)) }
        finally { driver.close() }
    }

    @Test fun unchangedDefinitionPreservesConfirmedEmptyAndPopulatedBaselines() = runBlocking {
        withStore { store ->
            val initial = store.reconcile(task())
            assertFalse(initial.baselineEstablished)
            store.confirmPreview(1, initial.fingerprint)
            store.saveScheduleTimeZone(1, initial.fingerprint, TimeZone.of("Asia/Shanghai"))
            store.commitVerified(1, initial.fingerprint, emptyList())
            val empty = store.reconcile(task().copy(name = "renamed", lastRunAt = 123))
            assertTrue(empty.baselineEstablished)
            assertTrue(empty.previewConfirmed)
            assertEquals("Asia/Shanghai", empty.scheduleTimeZone)
            store.commitVerified(1, initial.fingerprint, listOf(SyncBaseline("a", mapOf("source" to file, "target" to file))))
            assertEquals(listOf("a"), store.baselinePage(1, initial.fingerprint).map { it.relativePath })
            assertTrue(store.markConfirmationNotified(1, initial.fingerprint))
            assertFalse(store.markConfirmationNotified(1, initial.fingerprint))
        }
    }

    @Test fun definitionChangeInvalidatesOrdinaryStateButKeepsRecoveryAndRejectsStaleCommit() = runBlocking {
        withStore { store ->
            val old = store.reconcile(task()).fingerprint
            store.confirmPreview(1, old)
            store.commitVerified(1, old, listOf(SyncBaseline("a", mapOf("source" to file))))
            store.replacePendingDeletions(1, old, 2, listOf(SyncPendingDelete("target", "a", mapOf("target" to file))))
            store.saveRecovery(recovery(old))
            val fresh = store.reconcile(task().copy(targetPath = "/different"))
            assertFalse(fresh.previewConfirmed)
            assertEquals("", fresh.scheduleTimeZone)
            assertFailsWith<IllegalStateException> { store.saveScheduleTimeZone(1, old, TimeZone.of("Asia/Tokyo")) }
            assertFalse(fresh.baselineEstablished)
            assertEquals(0L, fresh.pendingRunId)
            assertEquals(0L, store.pendingDeletionCount(1))
            assertTrue(store.baselinePage(1, fresh.fingerprint).isEmpty())
            assertEquals(1, store.recoveries().size)
            assertFailsWith<IllegalStateException> { store.commitVerified(1, old, emptyList()) }
            store.commitVerified(1, fresh.fingerprint, emptyList(), completedRecoveryIds = setOf("recovery"))
            assertEquals(1, store.recoveries().size)
            store.deleteTaskState(1)
            assertNull(store.localState(1))
            assertEquals(1, store.recoveries().size)
            store.removeRecovery("recovery")
            assertTrue(store.recoveries().isEmpty())
        }
    }

    @Test fun fingerprintIncludesOrderedParticipantsAndOwnerButNotRuntimeState() {
        val task = task()
        val fingerprint = syncDefinitionFingerprint(task)
        assertEquals(fingerprint, syncDefinitionFingerprint(task.copy(lastRunAt = 100, nextRunAt = 200, lastStatus = SyncRunStatus.Success)))
        assertTrue(fingerprint != syncDefinitionFingerprint(task.copy(mode = SyncMode.Mirror)))
        assertTrue(fingerprint != syncDefinitionFingerprint(task.copy(ownerDeviceId = "another")))
        assertTrue(fingerprint != syncDefinitionFingerprint(task.copy(sourcePath = "/another")))
        val first = StorageDestination(ref = "first", path = "/one")
        val second = StorageDestination(ref = "second", path = "/two")
        val group = task.copy(destinations = listOf(first, second), selectedSources = listOf(FileSimpleInfo.pathFileSimpleInfo("/source/photos")))
        val groupFingerprint = syncDefinitionFingerprint(group)
        assertTrue(groupFingerprint != syncDefinitionFingerprint(group.copy(destinations = listOf(second, first))))
        assertTrue(groupFingerprint != syncDefinitionFingerprint(group.copy(destinations = listOf(first))))
        assertTrue(groupFingerprint != syncDefinitionFingerprint(group.copy(destinations = listOf(first, second, first.copy(ref = "third")))))
        assertTrue(groupFingerprint != syncDefinitionFingerprint(group.copy(selectedSources = listOf(FileSimpleInfo.pathFileSimpleInfo("/source/documents")))))
    }

    @Test fun batchesAreBoundedAndPagesAreStable() = runBlocking {
        withStore { store ->
            val fingerprint = store.reconcile(task()).fingerprint
            val entries = (0..599).map { SyncBaseline(it.toString().padStart(3, '0'), mapOf("source" to file)) }
            assertFailsWith<IllegalArgumentException> { store.commitVerified(1, fingerprint, entries) }
            assertTrue(store.baselinePage(1, fingerprint).isEmpty())
            store.commitVerified(1, fingerprint, entries.take(500))
            store.commitVerified(1, fingerprint, entries.drop(500))
            assertEquals(500, store.baselinePage(1, fingerprint, limit = 1000).size)
            assertEquals(entries.drop(500), store.baselinePage(1, fingerprint, offset = 500))
        }
    }

    @Test fun databaseReadinessIsAwaitedAndFailureWritesNothing() = runBlocking {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        try {
            val database = createMcpHttpTestDatabase(driver)
            val ready = CompletableDeferred<Unit>()
            val store = SyncLocalStore(database, driver) { ready.await() }
            val waiting = async { store.reconcile(task()) }
            yield()
            assertFalse(waiting.isCompleted)
            ready.complete(Unit)
            assertNotNull(waiting.await())
            val failed = SyncLocalStore(database, driver) { error("database unavailable") }
            assertFailsWith<IllegalStateException> { failed.deleteTaskState(1) }
            assertNotNull(store.localState(1))
            Unit
        } finally { driver.close() }
    }

    @Test fun newAndBothLegacyDatabaseVersionsKeepExistingDataAcrossRepeatedMigration() {
        assertEquals(3L, FolderSpanDatabase.Schema.version)
        for (oldVersion in listOf(1L, 2L)) {
            val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
            try {
                driver.execute(null, "CREATE TABLE legacy_data (value TEXT NOT NULL)", 0)
                driver.execute(null, "INSERT INTO legacy_data VALUES ('keep')", 0)
                driver.execute(null, "PRAGMA user_version=$oldVersion", 0)
                repeat(2) { FolderSpanDatabase.Schema.migrate(driver, oldVersion, FolderSpanDatabase.Schema.version) }
                val names = driver.executeQuery(null, "SELECT name FROM sqlite_master WHERE type='table'", { cursor ->
                    val values = mutableListOf<String>()
                    while (cursor.next().value) values += cursor.getString(0)!!
                    QueryResult.Value(values)
                }, 0).value
                assertTrue(names.containsAll(listOf("SyncBaselineEntry", "SyncPendingDeletion", "SyncTaskLocalState", "SyncFileRecovery", "SyncRunDetail")))
                assertEquals("keep", driver.executeQuery(null, "SELECT value FROM legacy_data", { cursor ->
                    cursor.next()
                    QueryResult.Value(cursor.getString(0))
                }, 0).value)
            } finally { driver.close() }
        }
    }
}
