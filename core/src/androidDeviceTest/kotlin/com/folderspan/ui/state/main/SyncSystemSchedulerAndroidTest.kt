package com.folderspan.ui.state.main

import android.app.NotificationManager
import android.app.ForegroundServiceStartNotAllowedException
import android.app.job.JobScheduler
import android.content.pm.ServiceInfo
import android.content.Context
import android.content.Intent
import android.content.ComponentName
import android.net.NetworkCapabilities
import android.os.Build
import app.cash.sqldelight.driver.android.AndroidSqliteDriver
import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.db.SqlPreparedStatement
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.filters.SdkSuppress
import androidx.work.Configuration
import androidx.work.ForegroundUpdater
import androidx.work.ListenableWorker
import androidx.work.workDataOf
import androidx.work.NetworkType
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.testing.WorkManagerTestInitHelper
import androidx.work.testing.TestListenableWorkerBuilder
import androidx.concurrent.futures.CallbackToFutureAdapter
import androidx.work.impl.foreground.SystemForegroundService
import com.folderspan.AndroidContextHolder
import com.folderspan.db.FolderSpanDatabase
import com.folderspan.data.main.network.Network
import com.folderspan.data.main.network.buildProtocolId
import com.folderspan.service.mcp.http.createMcpHttpTestDatabase
import com.folderspan.service.sync.SyncConditionsMonitor
import com.folderspan.service.sync.SyncNetworkType
import com.folderspan.service.sync.SyncPlatformConditions
import com.folderspan.utils.SettingsUtils
import com.folderspan.utils.installAppLogging
import com.folderspan.utils.FileAccessPermission
import com.folderspan.utils.SensitiveFileAccessPolicy
import com.folderspan.ui.state.file.FileRecentState
import com.folderspan.ui.state.file.FileState
import com.russhwolf.settings.MapSettings
import com.russhwolf.settings.Settings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import org.koin.dsl.module
import java.io.File
import java.io.RandomAccessFile
import java.util.UUID
import java.util.concurrent.TimeUnit
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class SyncSystemSchedulerAndroidTest {
    @Test fun systemJobMustStillBeExecutingAndCancellationDoesNotLookLikeQuotaLoss() {
        assertFalse(syncSystemJobLostExecution(intArrayOf(JobScheduler.PENDING_JOB_REASON_EXECUTING), wasExecuting = true))
        assertFalse(syncSystemJobLostExecution(intArrayOf(JobScheduler.PENDING_JOB_REASON_INVALID_JOB_ID), wasExecuting = true))
        assertFalse(syncSystemJobLostExecution(intArrayOf(), wasExecuting = true))
        assertFalse(syncSystemJobLostExecution(intArrayOf(JobScheduler.PENDING_JOB_REASON_CONSTRAINT_MINIMUM_LATENCY), wasExecuting = false),
            "A Worker started by WorkManager's in-process scheduler never held a system job lease")
        assertFalse(syncSystemJobLostExecution(intArrayOf(JobScheduler.PENDING_JOB_REASON_QUOTA), wasExecuting = false))
        assertTrue(syncSystemJobLostExecution(intArrayOf(JobScheduler.PENDING_JOB_REASON_QUOTA), wasExecuting = true))
        assertTrue(syncSystemJobLostExecution(intArrayOf(JobScheduler.PENDING_JOB_REASON_CONSTRAINT_MINIMUM_LATENCY), wasExecuting = true))
        assertTrue(syncSystemJobLostExecution(intArrayOf(JobScheduler.PENDING_JOB_REASON_CONSTRAINT_CONNECTIVITY), wasExecuting = true))
    }

    @Test fun constraintsUseWifiOrEthernetAndKeepTheOlderApiFallback() {
        val task = SyncTask(1, "constraints", sourceType = SyncEndpointType.Local, sourcePath = "/source",
            targetType = SyncEndpointType.Local, targetPath = "/target", requireWifi = true, requireCharging = true)
        for (sdk in listOf(26, 27)) {
            val constraints = syncSystemConstraints(task, sdk)
            assertEquals(NetworkType.UNMETERED, constraints.requiredNetworkType)
            assertTrue(constraints.requiresCharging())
        }
        if (Build.VERSION.SDK_INT >= 31) {
            val request = assertNotNull(syncSystemConstraints(task).requiredNetworkRequest)
            assertTrue(request.hasTransport(NetworkCapabilities.TRANSPORT_WIFI))
            assertTrue(request.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET))
            assertFalse(request.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR))
        }
    }

    @Test fun saveEditPauseTakeoverSnapshotAndDeleteReconcileOneUniqueWork() = runBlocking {
        fixture { f ->
            var task = f.save()
            f.sync.start(backgroundOnly = true)
            val first = f.awaitWork(task.id)
            assertEquals(WorkInfo.State.ENQUEUED, first.state)
            repeat(3) { f.sync.notifyChanged() }
            delay(150)
            assertEquals(first.id, f.awaitWork(task.id).id)
            task = f.sync.createOrUpdateTask(task.copy(requireWifi = true)).getOrThrow()
            val changed = f.awaitWork(task.id, differentFrom = first.id)
            assertTrue(f.sync.updateEnabled(task.id, false))
            f.awaitNoPending(task.id)
            assertTrue(f.sync.updateEnabled(task.id, true))
            val resumed = f.awaitWork(task.id, differentFrom = changed.id)
            f.store.saveTask(checkNotNull(f.sync.getTask(task.id)).copy(ownerDeviceId = "another-device"))
            f.sync.reloadTasksFromStore()
            f.awaitNoPending(task.id)
            task = f.sync.createOrUpdateTask(checkNotNull(f.sync.getTask(task.id)), takeOwnership = true).getOrThrow()
            val owned = f.awaitWork(task.id, differentFrom = resumed.id)
            f.store.saveTask(task.copy(requireWifi = false, nextRunAt = task.nextRunAt + 1000))
            f.sync.reloadTasksFromStore()
            f.awaitWork(task.id, differentFrom = owned.id)
            f.sync.deleteTask(task.id)
            f.awaitNoPending(task.id)
        }
    }

    @Test fun withdrawalSkipsBackgroundWorkAndRenewedConsentRequeuesIt() = runBlocking {
        fixture { f ->
            File(f.source, "consent.txt").writeText("kept local")
            val task = f.save(due = true)
            f.sync.start(backgroundOnly = true)
            val first = f.awaitWork(task.id)
            val driver = checkNotNull(WorkManagerTestInitHelper.getTestDriver(f.context))
            SettingsUtils.setWelcomeAgreementAccepted(false)
            driver.setAllConstraintsMet(first.id)
            withTimeout(15_000) {
                while (!checkNotNull(f.manager.getWorkInfoById(first.id).get(5, TimeUnit.SECONDS)).state.isFinished) delay(20)
            }
            val blocked = checkNotNull(f.manager.getWorkInfoById(first.id).get(5, TimeUnit.SECONDS))
            assertTrue(blocked.outputData.getBoolean(SYNC_SOFTWARE_CONSENT_BLOCKED, false))
            assertFalse(File(f.target, "consent.txt").exists())
            assertTrue(f.sync.recordsFor(task.id).isEmpty())
            SettingsUtils.setWelcomeAgreementAccepted(true)
            f.sync.notifyChanged()
            val resumed = f.awaitWork(task.id, differentFrom = first.id)
            driver.setAllConstraintsMet(resumed.id)
            f.awaitRecord(task.id)
            assertEquals("kept local", File(f.target, "consent.txt").readText())
        }
    }

    @Test fun workerCopiesWithoutAnActivityAndSchedulesTheNextDueTime() = runBlocking {
        fixture { f ->
            File(f.source, "a.txt").writeText("headless bytes")
            val task = f.save(due = true)
            f.sync.start(backgroundOnly = true)
            val work = f.awaitWork(task.id)
            checkNotNull(WorkManagerTestInitHelper.getTestDriver(f.context)).setAllConstraintsMet(work.id)
            repeat(3) { f.sync.checkScheduledRuns() }
            f.awaitRecord(task.id)
            assertEquals(SyncRunStatus.Success, f.sync.recordsFor(task.id).first().status, f.sync.recordsFor(task.id).toString())
            assertEquals("headless bytes", File(f.target, "a.txt").readText())
            assertEquals(1, f.sync.recordsFor(task.id).size)
            val channel = assertNotNull(f.context.getSystemService(NotificationManager::class.java).getNotificationChannel(SYNC_PROGRESS_CHANNEL))
            assertEquals(NotificationManager.IMPORTANCE_LOW, channel.importance)
            val next = f.awaitWork(task.id, differentFrom = work.id)
            assertEquals(WorkInfo.State.ENQUEUED, next.state)
            assertTrue(checkNotNull(f.sync.getTask(task.id)).nextRunAt > nowMillis())
        }
    }

    @Test fun workerLoadsSavedNetworkAccountsWithoutOpeningTheNetworkPage() = runBlocking {
        fixture { f ->
            val persisted = NetworkState(f.database)
            val network = Network("owned offline WebDAV", "/", "WebDAV", "http://127.0.0.1:1", "", "")
            persisted.addNetwork(network, persist = true)
            val entryId = persisted.entries.single().id
            f.store.forceNextDue = true
            val task = f.store.saveTask(SyncTask(0, "saved network startup", sourceType = SyncEndpointType.Local,
                sourcePath = f.source.path, targetType = SyncEndpointType.Network, targetRef = network.buildProtocolId(), targetPath = "/archive",
                scheduleType = SyncScheduleType.Interval, intervalMinutes = 60, requireCharging = true, notifyLevel = SyncNotifyLevel.Off,
                ownerDeviceId = "android-system-test"))
            assertTrue(f.sync.networkState.entries.isEmpty())
            f.sync.start(backgroundOnly = true)
            val work = f.awaitWork(task.id)
            checkNotNull(WorkManagerTestInitHelper.getTestDriver(f.context)).setAllConstraintsMet(work.id)
            f.awaitRecord(task.id)
            assertEquals(entryId, f.sync.networkState.entries.single().id)
            assertEquals(network.buildProtocolId(), f.sync.networkState.entries.single().network.buildProtocolId())
            assertEquals(SyncRunStatus.Failure, f.sync.recordsFor(task.id).first().status,
                "The configured account loads; its deliberately closed localhost endpoint must fail normally")
            network.close()
        }
    }

    @SdkSuppress(minSdkVersion = 31)
    @Test fun rejectedForegroundStartCompletesSmallWorkWithinTheBackgroundAllowance() = runBlocking {
        fixture { f ->
            File(f.source, "a.txt").writeText("bounded background fallback")
            val task = f.save(due = true)
            val attempts = AtomicInteger()
            val worker = TestListenableWorkerBuilder<SyncSystemWorker>(f.context,
                inputData = workDataOf(SYNC_SYSTEM_TASK_ID to task.id, SYNC_SYSTEM_DUE to task.nextRunAt,
                    SYNC_SYSTEM_DEFINITION to syncPreviewFingerprint(task)))
                .setForegroundUpdater(ForegroundUpdater { _, _, _ ->
                    attempts.incrementAndGet()
                    CallbackToFutureAdapter.getFuture<Void> { future ->
                        future.setException(ForegroundServiceStartNotAllowedException("owned test background restriction"))
                        "rejected foreground start"
                    }
                }).build()
            withTimeout(15_000) { assertEquals(ListenableWorker.Result.success(), worker.doWork()) }
            assertEquals(1, attempts.get())
            assertEquals("bounded background fallback", File(f.target, "a.txt").readText())
            assertEquals(SyncRunStatus.Success, f.sync.recordsFor(task.id).single().status)
        }
    }

    @Test fun unconfirmedModeDoesNotCopyEvenWhenSystemConstraintsAreMet() = runBlocking {
        fixture { f ->
            File(f.source, "a.txt").writeText("needs confirmation")
            val task = f.save(due = true, mode = SyncMode.Mirror)
            f.sync.start(backgroundOnly = true)
            val work = f.awaitWork(task.id)
            checkNotNull(WorkManagerTestInitHelper.getTestDriver(f.context)).setAllConstraintsMet(work.id)
            withTimeout(10_000) { while (!checkNotNull(f.manager.getWorkInfoById(work.id).get(5, TimeUnit.SECONDS)).state.isFinished) delay(20) }
            assertFalse(File(f.target, "a.txt").exists())
            assertTrue(f.sync.recordsFor(task.id).isEmpty())
            assertFalse(f.sync.localStore!!.localState(task.id)!!.baselineEstablished)
            assertTrue(f.sync.requiresSyncPreview(task))
        }
    }

    @Test fun unmeteredCellularStillWaitsAndWifiRecoveryReturnsToWorkManager() = runBlocking {
        fixture { f ->
            File(f.source, "a.txt").writeText("wifi only")
            f.platform.value = SyncPlatformConditions(true, SyncNetworkType.Cellular, true)
            val task = f.save(due = true, wifi = true)
            f.sync.start(backgroundOnly = true)
            val work = f.awaitWork(task.id)
            val driver = checkNotNull(WorkManagerTestInitHelper.getTestDriver(f.context))
            driver.setAllConstraintsMet(work.id)
            withTimeout(10_000) { while (f.sync.waitingRunConditions[task.id].isNullOrEmpty()) delay(20) }
            assertFalse(File(f.target, "a.txt").exists())
            assertTrue(f.sync.recordsFor(task.id).isEmpty())
            withTimeout(10_000) { while (checkNotNull(f.manager.getWorkInfoById(work.id).get(5, TimeUnit.SECONDS)).let {
                it.state != WorkInfo.State.ENQUEUED || it.runAttemptCount == 0 }) delay(20) }
            f.platform.value = SyncPlatformConditions(true, SyncNetworkType.Ethernet, true)
            withTimeout(10_000) { while (!f.sync.waitingRunConditions[task.id].isNullOrEmpty()) delay(20) }
            delay(100)
            assertFalse(File(f.target, "a.txt").exists(), "Condition callbacks must not copy outside the Worker")
            driver.setAllConstraintsMet(work.id)
            driver.setInitialDelayMet(work.id)
            f.awaitRecord(task.id)
            assertEquals(SyncRunStatus.Success, f.sync.recordsFor(task.id).first().status, f.sync.recordsFor(task.id).toString())
            assertEquals("wifi only", File(f.target, "a.txt").readText())
        }
    }

    @SdkSuppress(minSdkVersion = 35)
    @Test fun runningWorkerIsNotReplacedAndSystemStopPreservesItsDueTimeAndRecoverableVersion() = runBlocking {
        stopDuringOverwrite(WorkInfo.STOP_REASON_TIMEOUT, SyncRunStatus.Interrupted)
    }

    @Test fun notificationCancellationIsRecordedAsUserCanceled() = runBlocking {
        stopDuringOverwrite(WorkInfo.STOP_REASON_CANCELLED_BY_APP, SyncRunStatus.Canceled)
    }

    private suspend fun stopDuringOverwrite(reason: Int, expected: SyncRunStatus) {
        fixture { f ->
            File(f.source, "saved.txt").writeText("kept")
            File(f.target, "saved.txt").writeText("kept")
            val stamp = 1_700_000_000_000L
            File(f.source, "saved.txt").setLastModified(stamp)
            File(f.target, "saved.txt").setLastModified(stamp)
            RandomAccessFile(File(f.source, "z-big.bin"), "rw").use { it.setLength(128L * 1024) }
            File(f.target, "z-big.bin").writeText("old recoverable bytes")
            val task = f.save(due = true, mode = SyncMode.Mirror)
            val fingerprint = syncDefinitionFingerprint(task)
            f.sync.localStore!!.confirmPreview(task.id, fingerprint)
            f.sync.localStore!!.commitVerified(task.id, fingerprint, listOf(SyncBaseline("saved.txt", mapOf(
                "source" to SyncEntryState(false, 4, stamp), "target" to SyncEntryState(false, 4, stamp)))))
            val committed = CountDownLatch(1)
            val release = CountDownLatch(1)
            val first = AtomicBoolean(true)
            f.recoveryGate.afterCommit = {
                if (first.compareAndSet(true, false)) {
                    committed.countDown()
                    check(release.await(10, TimeUnit.SECONDS)) { "Recovery barrier was not released" }
                }
            }
            f.sync.start(backgroundOnly = true)
            val work = f.awaitWork(task.id)
            val driver = checkNotNull(WorkManagerTestInitHelper.getTestDriver(f.context))
            try {
                driver.setAllConstraintsMet(work.id)
                assertTrue(withContext(Dispatchers.IO) { committed.await(10, TimeUnit.SECONDS) }, f.sync.recordsFor(task.id).toString())
                assertEquals(WorkInfo.State.RUNNING, checkNotNull(f.manager.getWorkInfoById(work.id).get(5, TimeUnit.SECONDS)).state)
                f.sync.notifyChanged()
                delay(100)
                assertEquals(work.id, f.awaitWork(task.id).id)
                if (reason == WorkInfo.STOP_REASON_CANCELLED_BY_APP) f.manager.createCancelPendingIntent(work.id).send()
                else {
                    withTimeout(5000) { while (SystemForegroundService.getInstance() == null) delay(10) }
                    InstrumentationRegistry.getInstrumentation().runOnMainSync {
                        checkNotNull(SystemForegroundService.getInstance()).onTimeout(0, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
                    }
                }
                withTimeout(5000) { while (checkNotNull(f.manager.getWorkInfoById(work.id).get(5, TimeUnit.SECONDS)).state == WorkInfo.State.RUNNING) delay(10) }
            } finally {
                release.countDown()
                f.recoveryGate.afterCommit = null
            }
            f.awaitRecord(task.id)
            assertEquals(expected, f.sync.recordsFor(task.id).first().status)
            assertTrue(f.target.walkTopDown().any { it.isFile && it.length() == 21L && it.readText() == "old recoverable bytes" },
                "The old version remains at its original or preserved recovery path")
            assertTrue(f.sync.localStore!!.baselinePage(task.id, fingerprint).any { it.relativePath == "saved.txt" })
            if (expected == SyncRunStatus.Interrupted) {
                assertEquals(task.nextRunAt, f.sync.getTask(task.id)!!.nextRunAt)
                assertEquals(work.id, f.awaitWork(task.id).id, "System retry keeps the WorkManager work and its backoff")
            }
        }
    }

    private class Store : SyncTaskStore {
        private val tasks = linkedMapOf<Long, SyncTask>()
        private val runs = mutableMapOf<Long, MutableList<SyncRunRecord>>()
        var forceNextDue = false
        override fun <T> withExclusiveAccess(block: () -> T): T = synchronized(this) { block() }
        @Synchronized override fun loadTasks() = tasks.values.toList()
        @Synchronized override fun saveTask(task: SyncTask): SyncTask {
            val saved = task.copy(id = task.id.takeIf { it > 0 } ?: (tasks.size + 1L),
                nextRunAt = if (forceNextDue) nowMillis() - 1000 else task.nextRunAt)
            forceNextDue = false
            tasks[saved.id] = saved
            return saved
        }
        @Synchronized override fun deleteTask(taskId: Long) { tasks.remove(taskId) }
        @Synchronized override fun appendRun(record: SyncRunRecord) { runs.getOrPut(record.taskId) { mutableListOf() }.removeAll { it.runId == record.runId }; runs.getValue(record.taskId).add(0, record) }
        @Synchronized override fun loadRuns(taskId: Long) = runs[taskId].orEmpty().toList()
        @Synchronized override fun deleteRuns(taskId: Long) { runs.remove(taskId) }
    }

    private class RecoveryGate { @Volatile var afterCommit: (() -> Unit)? = null }

    private class Fixture(val context: Context, val source: File, val target: File, val store: Store,
        val sync: SyncState, val manager: WorkManager, val platform: MutableStateFlow<SyncPlatformConditions>, val recoveryGate: RecoveryGate, val database: FolderSpanDatabase) {
        suspend fun save(due: Boolean = false, mode: SyncMode = SyncMode.Update, wifi: Boolean = false): SyncTask {
            store.forceNextDue = due
            return sync.createOrUpdateTask(SyncTask(0, "system test", sourceType = SyncEndpointType.Local, sourcePath = source.path,
                targetType = SyncEndpointType.Local, targetPath = target.path, mode = mode, scheduleType = SyncScheduleType.Interval,
                intervalMinutes = 60, requireCharging = true, requireWifi = wifi, notifyLevel = SyncNotifyLevel.Off)).getOrThrow()
        }
        suspend fun awaitWork(taskId: Long, differentFrom: UUID? = null): WorkInfo = withTimeout(15_000) {
            while (true) {
                val info = manager.getWorkInfosForUniqueWork("sync-task-$taskId").get(5, TimeUnit.SECONDS)
                    .firstOrNull { !it.state.isFinished && it.id != differentFrom }
                if (info != null) return@withTimeout info
                delay(20)
            }
            error("unreachable")
        }
        suspend fun awaitNoPending(taskId: Long) = withTimeout(10_000) {
            while (manager.getWorkInfosForUniqueWork("sync-task-$taskId").get(5, TimeUnit.SECONDS).any { !it.state.isFinished }) delay(20)
        }
        suspend fun awaitRecord(taskId: Long) = withTimeout(30_000) {
            while (sync.recordsFor(taskId).isEmpty() || sync.hasActiveSyncRun(taskId)) delay(20)
        }
    }

    private suspend fun fixture(block: suspend (Fixture) -> Unit) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        AndroidContextHolder.initialize(context)
        installAppLogging()
        WorkManagerTestInitHelper.initializeTestWorkManager(context, Configuration.Builder().build(),
            WorkManagerTestInitHelper.ExecutorsMode.PRESERVE_EXECUTORS)
        val manager = WorkManager.getInstance(context)
        val root = File(checkNotNull(context.getExternalFilesDir(null)), "sync-system-${UUID.randomUUID()}").apply { mkdirs() }
        assertEquals(FileAccessPermission.Allowed, SensitiveFileAccessPolicy.permissionForExternalPath(root.path))
        val source = File(root, "source").apply { mkdirs() }
        val target = File(root, "target").apply { mkdirs() }
        val databaseName = "sync-system-${UUID.randomUUID()}.db"
        val rawDriver = AndroidSqliteDriver(FolderSpanDatabase.Schema, context, databaseName)
        val recoveryGate = RecoveryGate()
        val driver = object : SqlDriver by rawDriver {
            override fun execute(identifier: Int?, sql: String, parameters: Int, binders: (SqlPreparedStatement.() -> Unit)?): QueryResult<Long> =
                rawDriver.execute(identifier, sql, parameters, binders).also {
                    if (sql.startsWith("INSERT OR REPLACE INTO SyncFileRecovery")) currentTransaction()?.afterCommit { recoveryGate.afterCommit?.invoke() }
                }
        }
        val database = createMcpHttpTestDatabase(driver, createSchema = false)
        val store = Store()
        val sync = SyncState(store) { SyncDeviceIdentity("android-system-test", "Test device") }
        val taskState = TaskState(TestTaskFailureResultStore(), TestTaskRuntimePersistenceStore(), TempTaskCleanupStore(File(root, "cleanup").path))
        val platformScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val platform = MutableStateFlow(SyncPlatformConditions(true, SyncNetworkType.Wifi, true))
        val monitor = SyncConditionsMonitor(platformScope, true, platform)
        val settings = MapSettings()
        SettingsUtils.init(settings)
        SettingsUtils.setWelcomeAgreementAccepted(true)
        stopKoin()
        startKoin { modules(module {
            single<Settings> { settings }
            single { database }
            single { sync }
            single { SyncLocalStore(database, driver) }
            single { monitor }
            single { NetworkState(database) }
            single { taskState }
            single { DeviceState() }
            single { MainState() }
            single { FileRecentState() }
            single { FileState().also { it.mainScope.cancel() } }
        }) }
        try { block(Fixture(context, source, target, store, sync, manager, platform, recoveryGate, database)) }
        finally {
            sync.stop()
            manager.cancelAllWork().result.get(10, TimeUnit.SECONDS)
            withTimeout(10_000) { while (sync.snapshotTasks().any { sync.hasActiveSyncRun(it.id) }) delay(20) }
            context.stopService(Intent().setComponent(ComponentName(context.packageName, "androidx.work.impl.foreground.SystemForegroundService")))
            InstrumentationRegistry.getInstrumentation().waitForIdleSync()
            platformScope.coroutineContext[Job]!!.cancelAndJoin()
            taskState.stop()
            stopKoin()
            driver.close()
            WorkManagerTestInitHelper.closeWorkDatabase()
            context.deleteDatabase(databaseName)
            root.deleteRecursively()
        }
    }
}
