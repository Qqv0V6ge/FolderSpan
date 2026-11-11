package com.folderspan.ui.state.main

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.db.SqlPreparedStatement
import com.folderspan.service.mcp.http.createMcpHttpTestDatabase
import com.folderspan.service.mcp.automation.McpSyncFacade
import com.folderspan.service.mcp.automation.McpAutomationException
import com.folderspan.notification.RequestNotificationDispatcher
import com.folderspan.notification.SyncNotificationEvent
import com.folderspan.notification.syncNotificationTarget
import com.folderspan.db.FolderSpanDatabase
import com.folderspan.data.file.FileSimpleInfo
import com.folderspan.data.main.Local
import com.folderspan.data.main.network.Network
import com.folderspan.data.main.network.NetworkDriveExtras
import com.folderspan.data.main.network.NetworkEntry
import com.folderspan.data.main.network.SftpDriveExtras
import com.folderspan.service.sync.SyncConditionsMonitor
import com.folderspan.service.sync.SyncExecutionConditions
import kotlinx.coroutines.NonCancellable
import com.folderspan.service.sync.SyncNetworkType
import com.folderspan.service.sync.SyncPlatformConditions
import com.folderspan.service.sync.SyncRunCondition
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CompletableDeferred
import com.folderspan.service.operation.TransferWaitReason
import com.folderspan.service.operation.observeOperationTransferWait
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.MutableStateFlow
import com.folderspan.service.network.CloudStorageNode
import com.folderspan.service.network.IdStorageProvider
import com.folderspan.service.network.NetworkConnectionConfig
import com.folderspan.service.network.StorageEntryNotFoundException
import com.folderspan.service.network.StorageHttpApi
import com.folderspan.service.network.StorageProvider
import com.folderspan.service.network.StoragePath
import com.folderspan.service.network.StorageReference
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import com.folderspan.ui.state.file.FileRecentState
import com.folderspan.ui.state.file.FileState
import com.folderspan.utils.FileAccessPermission
import com.folderspan.utils.FileUtils
import com.folderspan.utils.PathUtils
import com.folderspan.utils.SensitiveFileAccessPolicy
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.cancel
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import org.koin.dsl.module
import org.apache.sshd.common.config.keys.PublicKeyEntry
import org.apache.sshd.common.file.virtualfs.VirtualFileSystemFactory
import org.apache.sshd.common.keyprovider.KeyPairProvider
import org.apache.sshd.server.SshServer
import org.apache.sshd.server.auth.password.PasswordAuthenticator
import org.apache.sshd.sftp.server.SftpSubsystemFactory
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.FileTime
import java.io.ByteArrayOutputStream
import java.security.KeyPairGenerator
import strings.AppStrings
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.test.assertNull
import kotlinx.datetime.TimeZone

class SyncStateExecutionJvmTest {

    @Test fun realSftpCopyAssociatesActualMetricsAndScopedWaitWithItsSyncTask(): Unit = runBlocking {
        fixture { sync, source, target, _ ->
            Files.writeString(source.resolve("a.txt"), "actual SFTP delivery")
            val entered = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            withSftpTargets(sync, listOf(target), copyFailure = {
                val close = observeOperationTransferWait(TransferWaitReason.DeviceBusy)
                try { entered.complete(Unit); release.await() } finally { close() }
                null
            }) { destinations ->
                val task = sync.createOrUpdateTask(destinations.single().applyTo(save(sync, source, target)).copy(parallelism = 4)).getOrThrow()
                sync.runNowIfIdle(task.id)
                withTimeout(10_000) { entered.await() }
                val active = sync.activeOperationTasks(task.id).single()
                assertEquals(1, active.activeParallelCount())
                assertEquals(setOf(TransferWaitReason.DeviceBusy), sync.taskState.transferWaitReasons(active.key))
                release.complete(Unit)
                val result = awaitFinishedRun(sync, task.id)
                assertEquals(SyncRunStatus.Success, result.status, result.toString())
                assertEquals("actual SFTP delivery", Files.readString(target.resolve("a.txt")))
                assertTrue(sync.activeOperationTasks(task.id).isEmpty())
                assertTrue(sync.taskState.transferWaitReasons(active.key).isEmpty())
            }
        }
    }

    @Test fun mcpAcceptsConditionWaitingThenRunsOnceWithoutAnOverride(): Unit = runBlocking {
        val platform = MutableStateFlow(SyncPlatformConditions(true, SyncNetworkType.Cellular, true))
        fixture(platform = platform) { sync, source, target, _ ->
            Files.writeString(source.resolve("a.txt"), "MCP condition gate")
            val task = sync.createOrUpdateTask(save(sync, source, target).copy(requireWifi = true)).getOrThrow()
            withTimeout(5_000) { while (sync.conditionsMonitor.state.value.network != SyncNetworkType.Cellular) delay(1) }
            val facade = McpSyncFacade(sync)
            assertEquals("waiting_conditions", facade.run(task.id).pendingState)
            assertEquals("waiting_conditions", facade.run(task.id).pendingState)
            assertEquals("waiting_conditions", facade.list(null, 10).items.single().pendingState)
            assertFalse(Files.exists(target.resolve("a.txt")))
            assertTrue(sync.recordsFor(task.id).isEmpty())
            platform.value = platform.value.copy(network = SyncNetworkType.Wifi)
            val result = awaitFinishedRun(sync, task.id)
            assertEquals(SyncRunStatus.Success, result.status, result.toString())
            assertEquals(1, sync.recordsFor(task.id).size)
            assertEquals("MCP condition gate", Files.readString(target.resolve("a.txt")))
            assertEquals("none", facade.list(null, 10).items.single().pendingState)
        }
    }

    @Test fun mcpRunsKeepPendingDeletesBehindTheThreshold(): Unit = runBlocking {
        fixture { sync, source, target, _ ->
            Files.writeString(source.resolve("keep"), "keep source")
            Files.writeString(target.resolve("old-1"), "keep old one")
            Files.writeString(target.resolve("old-2"), "keep old two")
            val task = sync.createOrUpdateTask(save(sync, source, target).copy(mode = SyncMode.Mirror, deleteThreshold = 1)).getOrThrow()
            val facade = McpSyncFacade(sync)
            assertEquals("invalid_state", assertFailsWith<McpAutomationException> { facade.run(task.id) }.code)
            assertEquals("preview_required", facade.list(null, 10).items.single().pendingState)
            sync.localStore!!.confirmPreview(task.id, syncDefinitionFingerprint(task))
            sync.refreshSyncAttention(task.id)
            facade.run(task.id)
            awaitFinishedRun(sync, task.id)
            assertEquals("pending_deletions", facade.list(null, 10).items.single().pendingState)
            assertEquals(2L, sync.taskAttention[task.id]!!.pendingDeletionCount)
            facade.run(task.id)
            awaitFinishedRun(sync, task.id)
            assertEquals(2, sync.recordsFor(task.id).size)
            assertEquals("keep old one", Files.readString(target.resolve("old-1")))
            assertEquals("keep old two", Files.readString(target.resolve("old-2")))
            assertEquals("pending_deletions", facade.list(null, 10).items.single().pendingState)
        }
    }

    @Test fun updatePreviewDoesNotReplaceAFileWhenTheSourceDirectoryDisappeared(): Unit = runBlocking {
        fixture { sync, source, target, _ ->
            val directory = Files.createDirectory(source.resolve("folder"))
            Files.writeString(target.resolve("folder"), "keep target file")
            val task = save(sync, source, target)
            val preview = sync.createSyncPreview(task.id).getOrThrow()
            Files.delete(directory)
            sync.confirmSyncPreview(preview.token).getOrThrow()
            val result = awaitFinishedRun(sync, task.id)
            assertEquals(0, result.failureCount, result.toString())
            assertEquals(1, result.skippedCount, result.toString())
            assertEquals("keep target file", Files.readString(target.resolve("folder")))
        }
    }

    @Test fun updatePreviewChecksUnknownTargetTimesByContent(): Unit = runBlocking {
        fixture { sync, source, target, _ ->
            for (name in listOf("stable", "changed")) {
                Files.writeString(source.resolve(name), "authorized replacement")
                Files.writeString(target.resolve(name), "old")
                Files.setLastModifiedTime(target.resolve(name), FileTime.fromMillis(0))
            }
            val task = save(sync, source, target)
            val preview = sync.createSyncPreview(task.id).getOrThrow()
            assertTrue(preview.comparisonReadBytes >= 6)
            Files.writeString(target.resolve("changed"), "new")
            Files.setLastModifiedTime(target.resolve("changed"), FileTime.fromMillis(0))
            sync.confirmSyncPreview(preview.token).getOrThrow()
            val result = awaitFinishedRun(sync, task.id)
            assertEquals(0, result.failureCount, result.toString())
            assertEquals(1, result.skippedCount, result.toString())
            assertEquals("new", Files.readString(target.resolve("changed")))
            assertEquals("authorized replacement", Files.readString(target.resolve("stable")))
        }
    }

    @Test fun updateRootFileConflictsAndEmptyDirectoryOptionUseTheirPreviewedPaths(): Unit = runBlocking {
        for (policy in SyncConflictPolicy.entries) for (empty in listOf(false, true)) fixture { sync, source, target, _ ->
            Files.createDirectory(source.resolve("empty"))
            Files.delete(target)
            Files.writeString(target, "original root")
            val occupied = Files.createDirectory(target.resolveSibling("target(1)"))
            Files.writeString(occupied.resolve("keep"), "untouched")
            val task = sync.createOrUpdateTask(save(sync, source, target).copy(conflictPolicy = policy, includeEmptyDirectories = empty)).getOrThrow()
            val preview = sync.createSyncPreview(task.id).getOrThrow()
            assertEquals("original root", Files.readString(target))
            sync.confirmSyncPreview(preview.token).getOrThrow()
            val result = awaitFinishedRun(sync, task.id)
            assertEquals(SyncRunStatus.Success, result.status, result.toString())
            when (policy) {
                SyncConflictPolicy.Skip -> assertEquals("original root", Files.readString(target))
                SyncConflictPolicy.Replace -> {
                    assertTrue(Files.isDirectory(target))
                    assertEquals(empty, Files.exists(target.resolve("empty")))
                }
                SyncConflictPolicy.Rename -> {
                    assertEquals("original root", Files.readString(target))
                    val renamed = target.resolveSibling("target(2)")
                    assertTrue(Files.isDirectory(renamed))
                    assertEquals(empty, Files.exists(renamed.resolve("empty")))
                }
            }
            assertEquals("untouched", Files.readString(occupied.resolve("keep")))
        }
    }

    @Test fun updateSourceChangesAfterPreviewAreSkippedAndDoNotExpandThePlan(): Unit = runBlocking {
        fixture { sync, source, target, _ ->
            Files.writeString(source.resolve("a.txt"), "planned")
            Files.writeString(target.resolve("a.txt"), "old")
            val task = save(sync, source, target)
            val preview = sync.createSyncPreview(task.id).getOrThrow()
            Files.writeString(source.resolve("a.txt"), "source changed after preview")
            sync.confirmSyncPreview(preview.token).getOrThrow()
            val result = awaitFinishedRun(sync, task.id)
            assertEquals(1, result.skippedCount, result.toString())
            assertEquals("old", Files.readString(target.resolve("a.txt")))
        }
    }

    @Test fun updateFrozenRetriesRecheckTheAuthorizedVersionAndKeepLaterFiles(): Unit = runBlocking {
        for (changeAfterFailure in listOf(false, true)) fixture { sync, source, target, _ ->
            Files.writeString(source.resolve("a.txt"), "authorized source")
            var attempts = 0
            withSftpTargets(sync, listOf(target), copyFailure = {
                attempts++
                if (attempts == 1) {
                    if (changeAfterFailure) Files.writeString(target.resolve("a.txt"), "arrived during retry")
                    IllegalStateException("retry once")
                } else null
            }) { destinations ->
                val task = sync.createOrUpdateTask(destinations.single().applyTo(save(sync, source, target)).copy(retryCount = 1)).getOrThrow()
                val preview = sync.createSyncPreview(task.id).getOrThrow()
                sync.confirmSyncPreview(preview.token).getOrThrow()
                val result = awaitFinishedRun(sync, task.id)
                assertEquals(if (changeAfterFailure) 1 else 2, attempts, result.toString())
                assertEquals(if (changeAfterFailure) 1 else 0, result.failureCount, result.toString())
                assertEquals(if (changeAfterFailure) "arrived during retry" else "authorized source", Files.readString(target.resolve("a.txt")))
            }
        }
    }

    @Test fun updatePreviewConfirmsOnlyFrozenPathsAndSkipsNewTargetVersions(): Unit = runBlocking {
        fixture { sync, source, target, _ ->
            Files.writeString(source.resolve("copy.txt"), "frozen source")
            Files.writeString(source.resolve("changed.txt"), "authorized overwrite")
            Files.writeString(target.resolve("changed.txt"), "old")
            val task = save(sync, source, target)
            val preview = sync.createSyncPreview(task.id).getOrThrow()
            assertFalse(preview.requiresFirstConfirmation)
            assertEquals(1, preview.counts.getValue(SyncPreviewAction.Overwrite).count)
            assertFalse(Files.exists(target.resolve("copy.txt")))
            Files.writeString(source.resolve("later.txt"), "next run only")
            Files.writeString(target.resolve("changed.txt"), "edited after preview")
            sync.confirmSyncPreview(preview.token).getOrThrow()
            val result = awaitFinishedRun(sync, task.id)
            assertEquals(0, result.failureCount, result.toString())
            assertEquals(1, result.skippedCount, result.toString())
            assertEquals("frozen source", Files.readString(target.resolve("copy.txt")))
            assertEquals("edited after preview", Files.readString(target.resolve("changed.txt")))
            assertFalse(Files.exists(target.resolve("later.txt")))
        }
    }

    @Test fun updatePreviewKeepsTheChosenRenameAndNeverChoosesAnotherNameAtConfirmation(): Unit = runBlocking {
        fixture { sync, source, target, _ ->
            Files.writeString(source.resolve("a.txt"), "new version")
            Files.writeString(target.resolve("a.txt"), "old")
            val task = sync.createOrUpdateTask(save(sync, source, target).copy(conflictPolicy = SyncConflictPolicy.Rename)).getOrThrow()
            val preview = sync.createSyncPreview(task.id).getOrThrow()
            val path = sync.syncPreviewPage(preview.token).single { it.action == SyncPreviewAction.ConflictCopy }.relativePath
            Files.writeString(target.resolve(path), "arrived after preview")
            sync.confirmSyncPreview(preview.token).getOrThrow()
            val result = awaitFinishedRun(sync, task.id)
            assertEquals(1, result.skippedCount, result.toString())
            assertEquals("old", Files.readString(target.resolve("a.txt")))
            assertEquals("arrived after preview", Files.readString(target.resolve(path)))
            assertFalse(Files.exists(target.resolve("a(2).txt")))
        }
    }

    @Test fun updateSingleFilePreviewKeepsAnExactTargetFilenameAndMissingParent(): Unit = runBlocking {
        fixture { sync, source, target, _ ->
            val file = Files.writeString(source.resolve("original.txt"), "single file")
            val path = target.resolve("missing/renamed.txt")
            val task = save(sync, file, path)
            val preview = sync.createSyncPreview(task.id).getOrThrow()
            assertTrue(sync.syncPreviewPage(preview.token).any { it.relativePath == "renamed.txt" && it.sourceRelativePath == "original.txt" })
            assertFalse(Files.exists(path.parent))
            sync.confirmSyncPreview(preview.token).getOrThrow()
            val result = awaitFinishedRun(sync, task.id)
            assertEquals(SyncRunStatus.Success, result.status, result.toString())
            assertEquals("single file", Files.readString(path))
            assertFalse(Files.exists(path.parent.resolve("original.txt")))
        }
    }

    @Test fun updateDirectoryReplacementRefusesChildrenAddedAfterPreview(): Unit = runBlocking {
        fixture { sync, source, target, _ ->
            Files.writeString(source.resolve("item"), "replace directory")
            val conflict = Files.createDirectory(target.resolve("item"))
            Files.writeString(conflict.resolve("old"), "keep old")
            val task = save(sync, source, target)
            val preview = sync.createSyncPreview(task.id).getOrThrow()
            Files.writeString(conflict.resolve("new"), "keep new")
            sync.confirmSyncPreview(preview.token).getOrThrow()
            val result = awaitFinishedRun(sync, task.id)
            assertEquals(1, result.skippedCount, result.toString())
            assertEquals(0, result.failureCount, result.toString())
            assertEquals("keep old", Files.readString(conflict.resolve("old")))
            assertEquals("keep new", Files.readString(conflict.resolve("new")))
        }
    }

    @Test fun updateGroupPreviewUsesTheSameFrozenActionsForTwoRealSftpTargets(): Unit = runBlocking {
        fixture { sync, source, target, _ ->
            val second = Files.createDirectory(target.parent.resolve("second"))
            Files.writeString(source.resolve("a.txt"), "group version")
            Files.writeString(target.resolve("a.txt"), "old")
            withSftpTargets(sync, listOf(target, second)) { destinations ->
                val task = sync.createOrUpdateTask(destinations.first().applyTo(save(sync, source, target)).copy(destinations = destinations)).getOrThrow()
                val preview = sync.createSyncPreview(task.id).getOrThrow()
                Files.writeString(target.resolve("a.txt"), "keep changed target")
                Files.writeString(source.resolve("later.txt"), "next run")
                sync.confirmSyncPreview(preview.token).getOrThrow()
                val result = awaitFinishedRun(sync, task.id)
                assertEquals(0, result.failureCount, result.toString())
                assertEquals(2, result.targets.size)
                assertEquals("keep changed target", Files.readString(target.resolve("a.txt")))
                assertEquals("group version", Files.readString(second.resolve("a.txt")))
                assertFalse(Files.exists(target.resolve("later.txt")))
                assertFalse(Files.exists(second.resolve("later.txt")))
            }
        }
    }

    @Test fun conditionsStopBetweenRecoveryGroupsAfterTheCurrentGroupsBaselineCommits(): Unit = runBlocking {
        var armed = false
        var missing = emptySet<SyncRunCondition>()
        fixture(onBaselineWrite = { if (armed) { armed = false; missing = setOf(SyncRunCondition.Wifi) } }) { sync, source, target, _ ->
            Files.writeString(source.resolve("a.txt"), "first recovery content")
            Files.writeString(source.resolve("b.txt"), "second recovery content")
            val task = sync.createOrUpdateTask(save(sync, source, target).copy(mode = SyncMode.Mirror)).getOrThrow()
            val store = sync.localStore!!
            store.confirmPreview(task.id, syncDefinitionFingerprint(task))
            val operation = sync.createOperationTask(task)
            val prepared = sync.prepareSyncRun(task, operation).getOrThrow()
            for (path in prepared.preparation.plan.paths.filter { it.operations.isNotEmpty() }) {
                sync.executeSyncPathRecovery(prepared, path, operation, 9)
            }
            assertEquals(2, store.recoveries(task.id).map { it.groupId }.distinct().size)
            armed = true
            var canceled = false
            try {
                coroutineScope {
                    val owner = this
                    val gate = SyncExecutionConditions(this, { missing }, { owner.cancel() })
                    try { withContext(gate) { sync.recoverSyncTaskBeforeScan(task, operation) } }
                    finally { withContext(NonCancellable) { gate.close() } }
                }
            } catch (_: CancellationException) { canceled = true }
            assertTrue(canceled)
            assertEquals(1, store.baselinePage(task.id, syncDefinitionFingerprint(task)).size)
            assertEquals(1, store.recoveries(task.id).map { it.groupId }.distinct().size)
            assertEquals("first recovery content", Files.readString(target.resolve("a.txt")))
            assertEquals("second recovery content", Files.readString(target.resolve("b.txt")))
            missing = emptySet()
            sync.recoverSyncTaskBeforeScan(task, operation)
            assertEquals(2, store.baselinePage(task.id, syncDefinitionFingerprint(task)).size)
            assertTrue(store.recoveries(task.id).isEmpty())
        }
    }

    @Test fun cancelingAStorageGroupFromTheTaskListDoesNotRestartItWhenConditionsReturn(): Unit = runBlocking {
        val platform = MutableStateFlow(SyncPlatformConditions(true, SyncNetworkType.Wifi, true))
        fixture(platform = platform) { sync, source, target, _ ->
            Files.writeString(source.resolve("a.txt"), "first group content")
            Files.writeString(source.resolve("b.txt"), "remaining group content")
            var copies = 0
            var taskId = 0L
            withSftpTargets(sync, listOf(target), copyFailure = {
                if (++copies == 1) {
                    val group = sync.activeStorageGroups.getValue(taskId).first()
                    sync.taskState.requestCancel(group)
                    platform.value = platform.value.copy(network = SyncNetworkType.Cellular)
                    withTimeout(5_000) {
                        while (sync.conditionsMonitor.state.value.network != SyncNetworkType.Cellular) delay(1)
                    }
                }
                null
            }) { destinations ->
                val task = sync.createOrUpdateTask(destinations.single().applyTo(save(sync, source, target)).copy(
                    backup = true, destinations = destinations, selectRecommended = false, requireWifi = true,
                    notifyLevel = SyncNotifyLevel.Off)).getOrThrow()
                taskId = task.id
                withTimeout(5_000) {
                    while (sync.conditionsMonitor.state.value.network != SyncNetworkType.Wifi) delay(1)
                }
                sync.runTaskInternal(task, "manual")
                assertEquals(SyncRunStatus.Canceled, sync.recordsFor(task.id).first().status)
                assertEquals(1, copies)
                assertFalse(task.id in sync.waitingRunConditions)
                assertTrue(Files.exists(source.resolve("a.txt")))
                assertTrue(Files.exists(source.resolve("b.txt")))
                platform.value = platform.value.copy(network = SyncNetworkType.Wifi)
                withTimeout(5_000) {
                    while (sync.conditionsMonitor.state.value.network != SyncNetworkType.Wifi) delay(1)
                }
                sync.conditionRuns.drain()
                assertEquals(1, sync.recordsFor(task.id).size)
                assertEquals(1, copies)
            }
        }
    }

    @Test fun retryWithAnOldStorageSnapshotStillWaitsForTheCurrentTasksConditions(): Unit = runBlocking {
        val platform = MutableStateFlow(SyncPlatformConditions(true, SyncNetworkType.Cellular, true))
        fixture(platform = platform) { sync, source, target, _ ->
            Files.writeString(source.resolve("a.txt"), "retained retry content")
            var fail = true
            var copies = 0
            withSftpTargets(sync, listOf(target), copyFailure = {
                copies++
                if (fail) IllegalStateException("injected retry failure") else null
            }) { destinations ->
                val task = sync.createOrUpdateTask(destinations.single().applyTo(save(sync, source, target)).copy(
                    backup = true, destinations = destinations, selectRecommended = false, retryCount = 0,
                    notifyLevel = SyncNotifyLevel.Off)).getOrThrow()
                sync.runTaskInternal(task, "manual")
                val original = sync.recordsFor(task.id).first()
                assertFalse(original.policySnapshot!!.requireWifi)
                assertTrue(original.targets.any { it.status != SyncRunStatus.Success })
                sync.createOrUpdateTask(sync.getTask(task.id)!!.copy(requireWifi = true)).getOrThrow()
                fail = false
                val beforeRetry = copies
                sync.retryStorageTargets(task.id, original.runId, original.targets.mapTo(mutableSetOf()) { it.operationTaskKey })
                assertEquals(setOf(SyncRunCondition.Wifi), sync.waitingRunConditions[task.id])
                assertEquals(beforeRetry, copies)
                platform.value = platform.value.copy(network = SyncNetworkType.Wifi)
                withTimeout(20_000) {
                    while (sync.recordsFor(task.id).size < 2 || sync.hasActiveSyncRun(task.id)) { sync.conditionRuns.drain(); delay(10) }
                }
                val finished = sync.recordsFor(task.id).first()
                assertEquals(SyncRunStatus.Success, finished.status, finished.toString())
                assertTrue(finished.policySnapshot!!.requireWifi)
                assertEquals(beforeRetry + 1, copies)
                assertEquals("retained retry content", Files.readString(target.resolve("a.txt")))
            }
        }
    }

    @Test fun conditionLossDuringRealSftpTransferKeepsCurrentFileAndBaselineThenResumesOnlyRemainingWork(): Unit = runBlocking {
        val platform = MutableStateFlow(SyncPlatformConditions(true, SyncNetworkType.Wifi, true))
        fixture(platform = platform) { sync, source, target, store ->
            Files.writeString(source.resolve("first.txt"), "first actual SFTP content")
            Files.writeString(source.resolve("second.txt"), "second actual SFTP content")
            var copies = 0
            withSftpTargets(sync, listOf(target), copyFailure = {
                if (++copies == 1) {
                    platform.value = platform.value.copy(network = SyncNetworkType.Cellular)
                    withTimeout(5_000) {
                        while (sync.conditionsMonitor.state.value.network != SyncNetworkType.Cellular) delay(1)
                    }
                }
                null
            }) { destinations ->
                val task = sync.createOrUpdateTask(destinations.single().applyTo(save(sync, source, target)).copy(
                    mode = SyncMode.Mirror, parallelism = 1, requireWifi = true, notifyLevel = SyncNotifyLevel.Off,
                    scheduleType = SyncScheduleType.Interval, enabled = true)).getOrThrow()
                val due = nowMillis() - 1_000
                store.saveTask(task.copy(nextRunAt = due))
                sync.reloadTasksFromStore()
                sync.localStore!!.confirmPreview(task.id, syncDefinitionFingerprint(task))
                withTimeout(5_000) {
                    while (sync.conditionsMonitor.state.value.network != SyncNetworkType.Wifi) delay(1)
                }
                sync.runTaskInternal(sync.getTask(task.id)!!, "schedule", scheduledAt = due)
                val canceled = sync.recordsFor(task.id).first()
                assertEquals(SyncRunStatus.Canceled, canceled.status, canceled.toString())
                assertEquals(1, copies)
                assertEquals(1, canceled.copiedCount)
                assertTrue(canceled.message.contains(AppStrings.sync_condition_wifi))
                assertEquals(due, store.loadTasks().single().nextRunAt)
                assertEquals(1, sync.localStore!!.baselinePage(task.id, syncDefinitionFingerprint(task)).size)
                assertEquals(1L, Files.list(target).use { files -> files.filter { !it.fileName.toString().startsWith(".folderspan-") }.count() })
                assertEquals(setOf(SyncRunCondition.Wifi), sync.waitingRunConditions[task.id])
                assertTrue(sync.localStore!!.recoveries(task.id).isEmpty())
                platform.value = platform.value.copy(network = SyncNetworkType.Wifi)
                withTimeout(20_000) {
                    while (sync.recordsFor(task.id).size < 2 || sync.hasActiveSyncRun(task.id)) { sync.conditionRuns.drain(); delay(10) }
                }
                val finished = sync.recordsFor(task.id).first()
                assertEquals(SyncRunStatus.Success, finished.status, finished.toString())
                assertEquals(2, copies)
                assertEquals(1, finished.copiedCount)
                assertEquals(2, sync.localStore!!.baselinePage(task.id, syncDefinitionFingerprint(task)).size)
                assertTrue(store.loadTasks().single().nextRunAt > nowMillis())
                assertTrue(sync.waitingRunConditions.isEmpty())
                assertEquals("first actual SFTP content", Files.readString(target.resolve("first.txt")))
                assertEquals("second actual SFTP content", Files.readString(target.resolve("second.txt")))
            }
        }
    }

    @Test fun conditionWaitingSurvivesAsOneDueScheduleAfterRestartAndCancellationRemovesManualWaiting(): Unit = runBlocking {
        val platform = MutableStateFlow(SyncPlatformConditions(true, SyncNetworkType.Cellular, true))
        fixture(platform = platform) { sync, source, target, store ->
            Files.writeString(source.resolve("a.txt"), "restart condition content")
            val task = sync.createOrUpdateTask(save(sync, source, target).copy(requireWifi = true,
                scheduleType = SyncScheduleType.Interval, enabled = true, notifyLevel = SyncNotifyLevel.Off)).getOrThrow()
            val due = nowMillis() - 10_000
            store.saveTask(task.copy(nextRunAt = due)); sync.reloadTasksFromStore()
            sync.checkScheduledRuns()
            assertEquals(setOf(SyncRunCondition.Wifi), sync.waitingRunConditions[task.id])
            repeat(3) { assertTrue(sync.runNowIfIdle(task.id) != null) }
            assertEquals(1, sync.waitingRunConditions.size)
            assertTrue(store.loadRuns(task.id).isEmpty())
            assertEquals(due, store.loadTasks().single().nextRunAt)
            sync.stop()
            val restarted = SyncState(store) { SyncDeviceIdentity("fixture", "Fixture") }
            try {
                restarted.reloadTasksFromStore()
                assertEquals(due, restarted.getTask(task.id)!!.nextRunAt)
                restarted.checkScheduledRuns()
                assertEquals(setOf(SyncRunCondition.Wifi), restarted.waitingRunConditions[task.id])
                platform.value = platform.value.copy(network = SyncNetworkType.Wifi)
                withTimeout(20_000) {
                    while (restarted.recordsFor(task.id).isEmpty() || restarted.hasActiveSyncRun(task.id)) {
                        restarted.conditionRuns.drain(); delay(10)
                    }
                }
                assertEquals(1, store.loadRuns(task.id).size)
                assertEquals("restart condition content", Files.readString(target.resolve("a.txt")))
                platform.value = platform.value.copy(network = SyncNetworkType.Cellular)
                withTimeout(5_000) {
                    while (restarted.conditionsMonitor.state.value.network != SyncNetworkType.Cellular) delay(1)
                }
                assertTrue(restarted.runNowIfIdle(task.id) != null)
                assertTrue(task.id in restarted.waitingRunConditions)
                restarted.cancelActiveRun(task.id)
                assertFalse(task.id in restarted.waitingRunConditions)
                withTimeout(5_000) { while (store.loadRuns(task.id).size < 2) delay(1) }
                platform.value = platform.value.copy(network = SyncNetworkType.Wifi)
                delay(50); restarted.conditionRuns.drain(); delay(50)
                assertEquals(2, store.loadRuns(task.id).size)
                assertEquals(SyncRunStatus.Canceled, store.loadRuns(task.id).first().status)
            } finally { restarted.stop() }
        }
    }
    @Test fun reloadDetectsTimeZoneChangesWhileTheApplicationWasClosedAndKeepsTheZoneLocal(): Unit = runBlocking {
        fixture { sync, source, target, store ->
            val saved = save(sync, source, target)
            val calendar = sync.createOrUpdateTask(saved.copy(scheduleType = SyncScheduleType.Daily)).getOrThrow()
            val zone = TimeZone.currentSystemDefault()
            val previous = TimeZone.of(if (zone.id == "Asia/Tokyo") "Asia/Shanghai" else "Asia/Tokyo")
            val fingerprint = syncDefinitionFingerprint(calendar)
            sync.localStore!!.saveScheduleTimeZone(calendar.id, fingerprint, previous)
            val cached = calendar.copy(nextRunAt = nowMillis() - 123_456)
            store.saveTask(cached)
            val beforeReload = computeNextRun(calendar, nowMillis(), zone)
            sync.reloadTasksFromStore()
            val fresh = sync.getTask(calendar.id)!!
            assertTrue(fresh.nextRunAt in setOf(beforeReload, computeNextRun(fresh, nowMillis(), zone)))
            assertTrue(fresh.nextRunAt != cached.nextRunAt)
            assertEquals(zone.id, sync.localStore!!.localState(calendar.id)!!.scheduleTimeZone)
            sync.localStore!!.saveScheduleTimeZone(calendar.id, fingerprint, previous)
            val interval = fresh.copy(scheduleType = SyncScheduleType.Interval, nextRunAt = nowMillis() - 5 * 60 * 60_000)
            store.saveTask(interval)
            sync.reloadTasksFromStore()
            assertEquals(interval.nextRunAt, sync.getTask(calendar.id)!!.nextRunAt)
        }
    }

    @Test fun interruptedThreeParticipantSftpConflictRecoversWithoutRetransferringTheCompletedTarget(): Unit = runBlocking {
        var armed = false
        var writes = 0
        fixture(onRecoveryWrite = {
            if (armed && ++writes == 30) {
                armed = false
                throw CancellationException("simulated process interruption")
            }
        }) { sync, source, target, _ ->
            val second = Files.createDirectory(target.parent.resolve("second"))
            val versions = listOf("source version", "first target different version", "second target entirely other version")
            val roots = listOf(source, target, second)
            val stamp = System.currentTimeMillis() / 1000 * 1000
            roots.forEachIndexed { index, root ->
                val file = Files.writeString(root.resolve("a.txt"), versions[index])
                Files.setLastModifiedTime(file, FileTime.fromMillis(stamp))
            }
            var copies = 0
            val ordinary = save(sync, source, target)
            withSftpTargets(sync, listOf(target, second), copyFailure = { copies++; null }) { destinations ->
                val task = sync.createOrUpdateTask(destinations.first().applyTo(ordinary).withMode(SyncMode.Bidirectional)
                    .copy(destinations = destinations, selectRecommended = false)).getOrThrow()
                val store = sync.localStore!!
                store.confirmPreview(task.id, syncDefinitionFingerprint(task))
                val operation = sync.createOperationTask(task)
                val prepared = sync.prepareSyncRun(task, operation).getOrThrow()
                assertEquals(8, prepared.preparation.plan.operations.size)
                armed = true
                val interrupted = sync.executeSyncPreparedRun(prepared, operation, "manual")
                assertEquals(SyncRunStatus.Canceled, interrupted.status,
                    "${interrupted.message}; writes=$writes; copies=$copies; ${interrupted.items}")
                assertFalse(armed)
                assertEquals(30, writes)
                val pending = store.recoveries(task.id)
                assertEquals(8, pending.size)
                assertTrue(pending.any { it.participant == "target-0" && it.relativePath == "a.txt" && it.phase == SyncRecoveryPhase.Verified })
                assertTrue(store.baselinePage(task.id, syncDefinitionFingerprint(task)).isEmpty())
                val copiedBeforeRecovery = copies
                sync.recoverSyncTaskBeforeScan(task, sync.createOperationTask(task))
                assertEquals(copiedBeforeRecovery, copies)
                assertTrue(store.recoveries(task.id).isEmpty())
                assertEquals(3, store.baselineEntry(task.id, syncDefinitionFingerprint(task), "a.txt")!!.participants.size)
                roots.forEach { root ->
                    assertEquals(versions.first(), Files.readString(root.resolve("a.txt")))
                    val retained = Files.list(root).use { children -> children.filter {
                        Files.isRegularFile(it) && !syncReservedPath(it.fileName.toString())
                    }.map { Files.readString(it) }.toList().toSet() }
                    assertEquals(versions.toSet(), retained)
                }
                val nextOperation = sync.createOperationTask(task)
                val next = sync.prepareSyncRun(task, nextOperation).getOrThrow()
                assertTrue(next.preparation.plan.operations.isEmpty())
                val repeated = sync.executeSyncPreparedRun(next, nextOperation, "manual")
                assertEquals(SyncRunStatus.Success, repeated.status)
                assertEquals(copiedBeforeRecovery, copies)
                assertEquals(0, repeated.copiedCount)
            }
        }
    }

    @Test fun schedulerRestartsWithOneCatchUpForEveryTimedSchedule(): Unit = runBlocking {
        fixture { sync, source, target, store ->
            Files.writeString(source.resolve("file"), "catch-up")
            val saved = save(sync, source, target)
            val due = saved.copy(scheduleType = SyncScheduleType.Interval, intervalMinutes = 30,
                nextRunAt = nowMillis() - 5 * 60 * 60_000)
            store.saveTask(due)
            sync.start()
            awaitFinishedRun(sync, saved.id)
            assertEquals("catch-up", Files.readString(target.resolve("file")))
            assertEquals(1, sync.recordsFor(saved.id).size)
            assertTrue(sync.getTask(saved.id)!!.nextRunAt > nowMillis())
            sync.checkScheduledRuns()
            assertEquals(1, sync.recordsFor(saved.id).size)
            for (type in listOf(SyncScheduleType.Daily, SyncScheduleType.Weekly)) {
                val current = sync.getTask(saved.id)!!
                store.saveTask(current.copy(scheduleType = type, scheduleWeekdays = 1 or 8, nextRunAt = nowMillis() - 1))
                sync.reloadTasksFromStore()
                val previousRun = sync.recordsFor(saved.id).first().runId
                sync.checkScheduledRuns()
                withTimeout(10_000) { while (sync.recordsFor(saved.id).first().runId == previousRun || sync.hasActiveSyncRun(saved.id)) delay(10) }
                assertTrue(sync.getTask(saved.id)!!.nextRunAt > nowMillis())
            }
            assertEquals(3, sync.recordsFor(saved.id).size)
        }
    }

    @Test fun staleScheduledJobsCannotRunAfterPauseEditOrAnotherTriggerConsumesTheirDueTime(): Unit = runBlocking {
        fixture { sync, source, target, store ->
            Files.writeString(source.resolve("file"), "once")
            val saved = save(sync, source, target)
            val due = saved.copy(scheduleType = SyncScheduleType.Interval, nextRunAt = nowMillis() - 1)
            store.saveTask(due)
            sync.reloadTasksFromStore()
            assertTrue(sync.updateEnabled(due.id, false))
            sync.runTaskInternal(due, "schedule", scheduledAt = due.nextRunAt)
            assertTrue(sync.recordsFor(due.id).isEmpty())
            assertFalse(Files.exists(target.resolve("file")))
            withTimeout(5_000) { while (store.loadTasks().single().enabled) delay(10) }
            val enabled = sync.createOrUpdateTask(sync.getTask(due.id)!!.copy(enabled = true)).getOrThrow()
            assertTrue(enabled.nextRunAt > nowMillis())
            sync.runTaskInternal(due, "schedule", scheduledAt = due.nextRunAt)
            assertTrue(sync.recordsFor(due.id).isEmpty())
            val freshDue = enabled.copy(nextRunAt = nowMillis() - 1)
            store.saveTask(freshDue)
            sync.reloadTasksFromStore()
            coroutineScope {
                val event = async { sync.runTaskInternal(freshDue, "event") }
                val scheduled = async { sync.runTaskInternal(freshDue, "schedule", scheduledAt = freshDue.nextRunAt) }
                event.await(); scheduled.await()
            }
            sync.runTaskInternal(freshDue, "schedule", scheduledAt = freshDue.nextRunAt)
            assertEquals(1, sync.recordsFor(due.id).size)
            assertEquals("once", Files.readString(target.resolve("file")))
        }
    }

    @Test fun timezoneChangesRecalculateDailyAndWeeklyButPreserveIntervalSchedules(): Unit = runBlocking {
        fixture { sync, source, target, store ->
            val saved = save(sync, source, target)
            val now = nowMillis()
            for (type in listOf(SyncScheduleType.Daily, SyncScheduleType.Weekly, SyncScheduleType.Interval)) {
                val task = saved.copy(scheduleType = type, scheduleWeekdays = 1 or 8, nextRunAt = now + 123_456)
                store.saveTask(task)
                sync.reloadTasksFromStore()
                val first = TimeZone.of("Asia/Shanghai")
                val second = TimeZone.of("Asia/Tokyo")
                sync.checkScheduledRuns(now, first)
                sync.checkScheduledRuns(now, second)
                val expected = if (type == SyncScheduleType.Interval) task.nextRunAt else computeNextRun(task, now, second)
                assertEquals(expected, sync.getTask(task.id)!!.nextRunAt)
                assertEquals(expected, store.loadTasks().single().nextRunAt)
            }
        }
    }

    @Test fun automaticConfirmationNotificationIsPostedOncePerDefinitionAndClearedOnConfirmation(): Unit = runBlocking {
        RequestNotificationDispatcher.setAppInForeground(true)
        try {
            fixture { sync, source, target, _ ->
                val state = NotificationState()
                org.koin.mp.KoinPlatform.getKoin().loadModules(listOf(module { single { state } }))
                val task = sync.createOrUpdateTask(save(sync, source, target).copy(mode = SyncMode.Mirror)).getOrThrow()
                assertEquals("invalid_state", assertFailsWith<McpAutomationException> { McpSyncFacade(sync).run(task.id) }.code)
                withTimeout(5_000) { while (state.notifications.isEmpty()) delay(10) }
                val revision = state.revision
                assertEquals(SyncNotificationEvent.PreviewRequired, syncNotificationTarget(state.notifications.single().metadata)?.event)
                sync.runTaskInternal(sync.getTask(task.id)!!, "schedule")
                assertEquals(revision, state.revision)
                assertTrue(sync.localStore!!.localState(task.id)!!.confirmationNotified)
                val changed = sync.createOrUpdateTask(sync.getTask(task.id)!!.copy(mode = SyncMode.Bidirectional)).getOrThrow()
                assertTrue(state.notifications.isEmpty())
                sync.runTaskInternal(changed, "event")
                assertEquals(1, state.notifications.size)
                val preview = sync.createSyncPreview(task.id).getOrThrow()
                sync.confirmSyncPreview(preview.token).getOrThrow()
                awaitFinishedRun(sync, task.id)
                assertTrue(state.notifications.isEmpty())
            }
        } finally { RequestNotificationDispatcher.setAppInForeground(false) }
    }

    @Test fun localBackupPreviewCopiesRenamesVerifiesAndCleansOnlySourceFiles(): Unit = runBlocking {
        fixture { sync, source, target, _ ->
            Files.createDirectories(source.resolve("photos/empty"))
            Files.writeString(source.resolve("photos/a.jpg"), "new photo bytes")
            Files.createDirectories(target.resolve("photos"))
            Files.writeString(target.resolve("photos/a.jpg"), "old photo")
            Files.writeString(target.resolve("unrelated.txt"), "keep unrelated backup")
            val task = sync.createOrUpdateTask(save(sync, source, target).copy(mode = SyncMode.BackupThenClean,
                conflictPolicy = SyncConflictPolicy.Rename)).getOrThrow()
            val preview = sync.createSyncPreview(task.id).getOrThrow()
            assertEquals(1, preview.counts.getValue(SyncPreviewAction.CleanSource).count)
            assertEquals(1, preview.counts.getValue(SyncPreviewAction.Copy).count)
            assertEquals("new photo bytes", Files.readString(source.resolve("photos/a.jpg")))
            sync.confirmSyncPreview(preview.token).getOrThrow()
            val run = awaitFinishedRun(sync, task.id)
            assertEquals(SyncRunStatus.Success, run.status, run.items.toString())
            assertFalse(Files.exists(source.resolve("photos/a.jpg")))
            assertTrue(Files.isDirectory(source.resolve("photos/empty")))
            assertTrue(Files.isDirectory(target.resolve("photos/empty")))
            assertEquals("old photo", Files.readString(target.resolve("photos/a.jpg")))
            assertEquals("new photo bytes", Files.readString(target.resolve("photos/a(1).jpg")))
            assertEquals("keep unrelated backup", Files.readString(target.resolve("unrelated.txt")))
            assertTrue(sync.localStore!!.recoveries(task.id).isEmpty())
            assertTrue(sync.prepareSyncRun(sync.getTask(task.id)!!, sync.createOperationTask(task)).getOrThrow().preparation.plan.operations.isEmpty())
        }
    }

    @Test fun aFailedSftpItemRetriesWithoutBlockingTheGoodItemAndLaterRetryKeepsItsBaseline(): Unit = runBlocking {
        fixture { sync, source, target, _ ->
            Files.writeString(source.resolve("bad.txt"), "bad source")
            Files.writeString(source.resolve("good.txt"), "good source")
            var fail = true
            var attempts = 0
            withSftpTargets(sync, listOf(target), copyFailure = { file ->
                if (file.name == "bad.txt" && fail) { attempts++; IllegalStateException("injected item failure") } else null
            }) { destinations ->
                val task = sync.createOrUpdateTask(destinations.single().applyTo(save(sync, source, target)).copy(
                    mode = SyncMode.Mirror, retryCount = 1)).getOrThrow()
                val preview = sync.createSyncPreview(task.id).getOrThrow()
                sync.confirmSyncPreview(preview.token).getOrThrow()
                val first = awaitFinishedRun(sync, task.id)
                assertEquals(SyncRunStatus.PartialSuccess, first.status, first.items.toString())
                assertEquals(1, first.successCount)
                assertEquals(1, first.failureCount)
                assertEquals(1, first.unsavedFailureCount)
                assertTrue(attempts >= 2)
                assertEquals("good source", Files.readString(target.resolve("good.txt")))
                assertFalse(Files.exists(target.resolve("bad.txt")))
                assertEquals(listOf("good.txt"), sync.localStore!!.baselinePage(task.id, syncDefinitionFingerprint(task)).map { it.relativePath })
                fail = false
                sync.retryFailedItems(task.id, first.runId)
                val retry = awaitFinishedRun(sync, task.id)
                assertEquals(SyncRunStatus.Success, retry.status, retry.items.toString())
                assertEquals(first.runId, retry.retryOfRunId)
                assertEquals(1, retry.copiedCount)
                assertEquals("bad source", Files.readString(target.resolve("bad.txt")))
                assertEquals("good source", Files.readString(target.resolve("good.txt")))
                assertTrue(sync.localStore!!.recoveries(task.id).isEmpty())
            }
        }
    }

    @Test fun directoryContentsArrivingAfterPreviewAreNeverAdoptedForReplacement(): Unit = runBlocking {
        for (recycle in listOf(false, true)) fixture { sync, source, target, _ ->
            Files.writeString(source.resolve("replace"), "source file replaces a directory")
            val directory = Files.createDirectory(target.resolve("replace"))
            Files.writeString(directory.resolve("original.txt"), "existing content")
            val task = sync.createOrUpdateTask(save(sync, source, target).copy(mode = SyncMode.Mirror, recycleEnabled = recycle)).getOrThrow()
            val preview = sync.createSyncPreview(task.id).getOrThrow()
            Files.writeString(directory.resolve("arriving.txt"), "not in the preview")
            sync.confirmSyncPreview(preview.token).getOrThrow()
            val result = awaitFinishedRun(sync, task.id)
            assertEquals(0, result.failureCount, result.items.toString())
            assertEquals(0, result.successCount)
            assertTrue(result.skippedCount >= 1)
            assertTrue(Files.isDirectory(directory))
            assertEquals("existing content", Files.readString(directory.resolve("original.txt")))
            assertEquals("not in the preview", Files.readString(directory.resolve("arriving.txt")))
            assertTrue(sync.localStore!!.baselinePage(task.id, syncDefinitionFingerprint(task)).isEmpty())
            assertTrue(sync.localStore!!.recoveries(task.id).isEmpty())
        }
    }

    @Test fun previewIsReadOnlyPagedDiscardableAndInvalidatedByDefinitionChanges(): Unit = runBlocking {
        fixture { sync, source, target, _ ->
            Files.writeString(source.resolve("a.txt"), "new source content")
            Files.writeString(target.resolve("a.txt"), "old")
            val task = sync.createOrUpdateTask(save(sync, source, target).copy(mode = SyncMode.Mirror)).getOrThrow()
            val store = sync.localStore!!
            val preview = sync.createSyncPreview(task.id).getOrThrow()
            assertTrue(preview.requiresFirstConfirmation)
            assertEquals(1, preview.counts.getValue(SyncPreviewAction.Overwrite).count)
            assertEquals(1, preview.counts.getValue(SyncPreviewAction.Recycle).count)
            assertEquals(3L, preview.counts.getValue(SyncPreviewAction.Recycle).bytes)
            assertEquals(SyncPreviewAction.Recycle, sync.syncPreviewPage(preview.token, action = SyncPreviewAction.Recycle).single().action)
            assertTrue(sync.syncPreviewPage(preview.token, offset = 1, action = SyncPreviewAction.Recycle).isEmpty())
            assertEquals("a.txt", sync.syncPreviewPage(preview.token, limit = 1).single().relativePath)
            assertTrue(sync.syncPreviewPage(preview.token, offset = preview.itemCount).isEmpty())
            assertEquals("old", Files.readString(target.resolve("a.txt")))
            assertFalse(store.localState(task.id)!!.previewConfirmed)
            assertFalse(store.localState(task.id)!!.baselineEstablished)
            assertTrue(store.recoveries(task.id).isEmpty())
            assertFalse(Files.exists(target.resolve(".folderspan-recycle")))
            sync.discardSyncPreview(preview.token)
            assertTrue(sync.confirmSyncPreview(preview.token).isFailure)
            val next = sync.createSyncPreview(task.id).getOrThrow()
            sync.createOrUpdateTask(task.copy(mode = SyncMode.Bidirectional)).getOrThrow()
            assertTrue(sync.confirmSyncPreview(next.token).isFailure)
            assertEquals("old", Files.readString(target.resolve("a.txt")))
            assertFalse(store.localState(task.id)!!.previewConfirmed)
        }
    }

    @Test fun firstConfirmationQueuesOnlyItsFrozenPlanAndThenEnablesAutomaticRuns(): Unit = runBlocking {
        fixture { sync, source, target, _ ->
            Files.writeString(source.resolve("a.txt"), "authorized source")
            Files.writeString(target.resolve("old.txt"), "recover old")
            val task = sync.createOrUpdateTask(save(sync, source, target).copy(mode = SyncMode.Mirror)).getOrThrow()
            assertNull(sync.runNowIfIdle(task.id))
            val preview = sync.createSyncPreview(task.id).getOrThrow()
            Files.writeString(source.resolve("later.txt"), "outside this preview")
            sync.confirmSyncPreview(preview.token).getOrThrow()
            val result = awaitFinishedRun(sync, task.id)
            assertEquals(SyncRunStatus.Success, result.status, result.items.toString())
            assertEquals("authorized source", Files.readString(target.resolve("a.txt")))
            assertFalse(Files.exists(target.resolve("old.txt")))
            assertFalse(Files.exists(target.resolve("later.txt")))
            assertTrue(sync.confirmSyncPreview(preview.token).isFailure)
            val current = sync.getTask(task.id)!!
            assertFalse(sync.requiresSyncPreview(current))
            sync.runTaskInternal(current, "schedule")
            assertEquals("outside this preview", Files.readString(target.resolve("later.txt")))
            assertEquals(2, sync.recordsFor(task.id).size)
        }
    }

    @Test fun confirmationSkipsPathsChangedAfterPreviewWithoutAcceptingTheirBaseline(): Unit = runBlocking {
        fixture { sync, source, target, _ ->
            Files.writeString(source.resolve("a.txt"), "source version")
            Files.writeString(target.resolve("a.txt"), "old")
            val task = sync.createOrUpdateTask(save(sync, source, target).copy(mode = SyncMode.Mirror)).getOrThrow()
            val preview = sync.createSyncPreview(task.id).getOrThrow()
            Files.writeString(target.resolve("a.txt"), "user edited after preview")
            sync.confirmSyncPreview(preview.token).getOrThrow()
            val result = awaitFinishedRun(sync, task.id)
            assertEquals(1, result.skippedCount)
            assertEquals(0, result.failureCount)
            assertEquals("user edited after preview", Files.readString(target.resolve("a.txt")))
            assertTrue(sync.localStore!!.baselinePage(task.id, syncDefinitionFingerprint(task)).isEmpty())
            assertTrue(sync.localStore!!.recoveries(task.id).isEmpty())
        }
    }

    @Test fun threshold100RunsBut101WaitAndNewRunsInvalidateOldPendingConsent(): Unit = runBlocking {
        fixture { sync, source, target, _ ->
            Files.writeString(source.resolve("keep.txt"), "keep")
            val task = sync.createOrUpdateTask(save(sync, source, target).copy(mode = SyncMode.Mirror, deleteThreshold = 100)).getOrThrow()
            val first = sync.createSyncPreview(task.id).getOrThrow()
            sync.confirmSyncPreview(first.token).getOrThrow()
            awaitFinishedRun(sync, task.id)
            repeat(100) { Files.writeString(target.resolve("exact-$it"), "exact $it") }
            sync.runTaskInternal(sync.getTask(task.id)!!, "schedule")
            val deletionRun = sync.recordsFor(task.id).first()
            assertEquals(SyncRunStatus.Success, deletionRun.status, deletionRun.toString())
            assertEquals(0L, sync.taskAttention[task.id]!!.pendingDeletionCount)
            repeat(100) { assertFalse(Files.exists(target.resolve("exact-$it")), "exact-$it survived: $deletionRun") }
            repeat(101) { Files.writeString(target.resolve("pending-$it"), "pending $it") }
            sync.runTaskInternal(sync.getTask(task.id)!!, "schedule")
            assertEquals(101L, sync.taskAttention[task.id]!!.pendingDeletionCount)
            val previousRun = sync.taskAttention[task.id]!!.pendingRunId
            val pending = sync.createSyncPreview(task.id, pendingDeletionsOnly = true).getOrThrow()
            assertEquals(previousRun, pending.pendingRunId)
            assertEquals(101, pending.counts.getValue(SyncPreviewAction.Delete).count)
            assertTrue(sync.syncPreviewPage(pending.token, limit = 500).all { it.action in listOf(SyncPreviewAction.Delete, SyncPreviewAction.Recycle) })
            sync.runTaskInternal(sync.getTask(task.id)!!, "schedule")
            assertTrue(sync.confirmSyncPreview(pending.token).isFailure)
            assertFalse(sync.rejectPendingSyncDeletions(task.id, previousRun))
            val currentRun = sync.taskAttention[task.id]!!.pendingRunId
            assertTrue(sync.rejectPendingSyncDeletions(task.id, currentRun))
            assertEquals(0L, sync.localStore!!.pendingDeletionCount(task.id))
            repeat(101) { assertTrue(Files.exists(target.resolve("pending-$it"))) }
            sync.runTaskInternal(sync.getTask(task.id)!!, "schedule")
            assertEquals(101L, sync.taskAttention[task.id]!!.pendingDeletionCount)
        }
    }

    private suspend fun awaitFinishedRun(sync: SyncState, taskId: Long): SyncRunRecord = withTimeout(30_000) {
        while (sync.recordsFor(taskId).isEmpty() || sync.hasActiveSyncRun(taskId)) delay(10)
        sync.recordsFor(taskId).first()
    }

    @Test fun cancellationFlushes600VerifiedPathsIncludingTheFinalShortBatch(): Unit = runBlocking {
        var writes = 0
        var cancel: () -> Unit = {}
        fixture(onBaselineWrite = { if (++writes == 550) cancel() }) { sync, source, target, _ ->
            repeat(600) { Files.writeString(source.resolve("$it.txt"), "file $it") }
            val task = sync.createOrUpdateTask(save(sync, source, target).copy(mode = SyncMode.Mirror)).getOrThrow()
            val store = sync.localStore!!
            val fingerprint = syncDefinitionFingerprint(task)
            store.confirmPreview(task.id, fingerprint)
            val operation = sync.createOperationTask(task)
            cancel = { sync.taskState.requestCancel(operation) }
            val prepared = sync.prepareSyncRun(task, operation).getOrThrow()
            val result = sync.executeSyncPreparedRun(prepared, operation, "manual")
            assertEquals(SyncRunStatus.Canceled, result.status)
            assertEquals(600, result.successCount,
                "failures=${result.failureCount}, skipped=${result.skippedCount}, items=${result.items.filter { it.message != AppStrings.ui_synchronization_completed }}")
            assertEquals(0, result.failureCount)
            assertEquals(600, writes)
            assertEquals(500, store.baselinePage(task.id, fingerprint).size)
            assertEquals(100, store.baselinePage(task.id, fingerprint, 500).size)
            assertTrue(store.recoveries(task.id).isEmpty())
            val resumed = sync.createOperationTask(task)
            val next = sync.prepareSyncRun(task, resumed).getOrThrow()
            assertTrue(next.preparation.plan.operations.isEmpty())
            val repeated = sync.executeSyncPreparedRun(next, resumed, "manual")
            assertEquals(SyncRunStatus.Success, repeated.status)
            assertEquals(0, repeated.copiedCount)
            assertEquals("file 599", Files.readString(target.resolve("599.txt")))
        }
    }

    @Test fun failedSecondBaselineBatchKeepsTheFirst500And100Recoveries(): Unit = runBlocking {
        var writes = 0
        var injectFailure = true
        fixture(onBaselineWrite = { if (++writes == 550 && injectFailure) error("baseline batch failed") }) { sync, source, target, _ ->
            repeat(600) { Files.writeString(source.resolve("$it.txt"), "file $it") }
            val task = sync.createOrUpdateTask(save(sync, source, target).copy(mode = SyncMode.Mirror)).getOrThrow()
            val store = sync.localStore!!
            val fingerprint = syncDefinitionFingerprint(task)
            store.confirmPreview(task.id, fingerprint)
            val operation = sync.createOperationTask(task)
            val prepared = sync.prepareSyncRun(task, operation).getOrThrow()
            val result = sync.executeSyncPreparedRun(prepared, operation, "manual")
            assertEquals(SyncRunStatus.PartialSuccess, result.status)
            assertEquals(500, result.successCount)
            assertEquals(100, result.failureCount)
            assertEquals(500, store.baselinePage(task.id, fingerprint).size)
            assertTrue(store.baselinePage(task.id, fingerprint, 500).isEmpty())
            assertEquals(100, store.recoveries(task.id).size)
            injectFailure = false
            sync.recoverSyncTaskBeforeScan(task, operation)
            assertEquals(100, store.baselinePage(task.id, fingerprint, 500).size)
            assertTrue(store.recoveries(task.id).isEmpty())
            assertTrue(sync.prepareSyncRun(task, operation).getOrThrow().preparation.plan.operations.isEmpty())
            assertEquals("file 599", Files.readString(target.resolve("599.txt")))
        }
    }

    @Test fun backgroundBootstrapLoadsDefinitionsWithoutStartingTheApplicationTimer(): Unit = runBlocking {
        fixture { sync, source, target, store ->
            Files.writeString(source.resolve("a.txt"), "headless definition")
            val task = sync.createOrUpdateTask(save(sync, source, target).copy(scheduleType = SyncScheduleType.Interval, intervalMinutes = 60)).getOrThrow()
            store.saveTask(task.copy(nextRunAt = nowMillis() - 1000))
            sync.start(backgroundOnly = true)
            delay(1200)
            assertFalse(Files.exists(target.resolve("a.txt")))
            assertTrue(sync.recordsFor(task.id).isEmpty())
            sync.start()
            withTimeout(4000) { while (sync.recordsFor(task.id).isEmpty()) delay(10) }
            assertEquals("headless definition", Files.readString(target.resolve("a.txt")))
        }
    }

    @Test fun systemStopAndUserCancellationKeepDifferentStatusesAndPreserveRecovery(): Unit = runBlocking {
        fixture { sync, source, target, _ ->
            val task = save(sync, source, target)
            val recovery = SyncFileRecovery("pending", task.id, syncDefinitionFingerprint(task), task.ownerDeviceId, "group", "target",
                SyncRecoveryEndpoint(SyncEndpointType.Local, "", target.toString()), "a", target.resolve("a").toString(),
                target.resolve("tmp").toString(), target.resolve("old").toString(), null, null, false)
            sync.localStore!!.saveRecovery(recovery)
            for ((id, stopped) in listOf(1L to SyncSystemStop.Interrupted, 2L to SyncSystemStop.UserCanceled)) {
                withContext(SyncSystemRun { stopped }) {
                    sync.onTaskFinished(task, SyncRunRecord(id, task.id, "schedule", id, id + 1, SyncRunStatus.Canceled, 0, 0, 0))
                }
                val expected = if (stopped == SyncSystemStop.Interrupted) SyncRunStatus.Interrupted else SyncRunStatus.Canceled
                assertEquals(expected, sync.recordsFor(task.id).first().status)
                assertEquals(expected, sync.getTask(task.id)!!.lastStatus)
                assertEquals(listOf(recovery), sync.localStore!!.recoveries(task.id))
            }
        }
    }

    @Test fun runDetailsRemainPagedAcrossDefinitionChangesAndClearingHistoryPreservesRecovery(): Unit = runBlocking {
        fixture { sync, source, target, _ ->
            Files.writeString(source.resolve("a.txt"), "source bytes")
            val task = save(sync, source, target)
            sync.runTaskInternal(task, "manual")
            val record = sync.recordsFor(task.id).first()
            assertEquals(1L, record.resultCounts.single { it.kind == SyncRunItemKind.Copy }.successCount)
            assertTrue(sync.runDetailPage(record).any { it.item.path.endsWith("a.txt") })
            val recovery = SyncFileRecovery("pending", task.id, syncDefinitionFingerprint(task), task.ownerDeviceId, "pending-group", "target",
                SyncRecoveryEndpoint(SyncEndpointType.Local, "", target.toString()), "a.txt", target.resolve("a.txt").toString(),
                target.resolve("temporary").toString(), target.resolve("old").toString(), null, null, false)
            sync.localStore!!.saveRecovery(recovery)
            sync.createOrUpdateTask(task.copy(name = "renamed", sourcePath = source.resolve("next").toString())).getOrThrow()
            assertTrue(sync.runDetailPage(record).any { it.item.path.endsWith("a.txt") })
            assertEquals(listOf(recovery.oldPath, recovery.temporaryPath, recovery.finalPath),
                sync.pendingRecoveryPage(task.id).single().item.recoveryLocations.map { it.path })
            val legacyItems = (0 until 1001).map { SyncRunItem("old-$it.txt", false, "failed", FileSimpleInfo.pathFileSimpleInfo("/source/$it.txt")) }
            val legacy = record.copy(runId = record.runId + 1, items = emptyList(), resultCounts = emptyList(), detailCount = 0,
                targets = listOf(StorageTargetRun(StorageDestination(SyncEndpointType.Local, "", target.toString()), 71, items = legacyItems)))
            sync.publishStorageRun(checkNotNull(sync.getTask(task.id)), legacy)
            val legacyPage = sync.runDetailPage(legacy, offset = 100)
            assertEquals(legacyItems.subList(100, 200).map { it.copy(participant = "target-0") }, legacyPage.map { it.item })
            sync.clearRunRecords(task.id)
            withTimeout(2000) {
                while (sync.localStore!!.runDetailPage(task.id, task.ownerDeviceId, record.runId, record.detailCount).isNotEmpty()) delay(5)
            }
            assertTrue(sync.runDetailPage(record).isEmpty())
            assertEquals(1, sync.pendingRecoveryPage(task.id).size)
        }
    }

    @Test fun preparedMirrorRunCreatesCopiesRecyclesThenRemovesOnlyEmptyDirectories(): Unit = runBlocking {
        fixture { sync, source, target, _ ->
            Files.createDirectories(source.resolve("new/nested"))
            Files.writeString(source.resolve("new/nested/a.txt"), "source bytes")
            Files.createDirectories(target.resolve("old/nested"))
            Files.writeString(target.resolve("old/nested/extra.txt"), "recover these bytes")
            val task = sync.createOrUpdateTask(save(sync, source, target).copy(mode = SyncMode.Mirror)).getOrThrow()
            sync.localStore!!.confirmPreview(task.id, syncDefinitionFingerprint(task))
            val operation = sync.createOperationTask(task)
            val prepared = sync.prepareSyncRun(task, operation).getOrThrow()
            val result = sync.executeSyncPreparedRun(prepared, operation, "manual")
            assertEquals(SyncRunStatus.Success, result.status, result.items.toString())
            assertEquals("source bytes", Files.readString(target.resolve("new/nested/a.txt")))
            assertFalse(Files.exists(target.resolve("old")))
            val recycled = Files.walk(target.resolve(".folderspan-recycle")).use { stream ->
                stream.filter { Files.isRegularFile(it) }.toList()
            }
            assertEquals(1, recycled.size)
            assertEquals("extra.txt", recycled.single().fileName.toString())
            assertEquals("recover these bytes", Files.readString(recycled.single()))
            val detailRows = sync.localStore!!.runDetailPage(task.id, task.ownerDeviceId, result.runId, result.detailCount)
            val recycleRow = detailRows.single { it.item.kind == SyncRunItemKind.Recycle }.item
            assertEquals(recycled.single().toString(), recycleRow.recoveryLocations.single().path)
            assertTrue(recycleRow.recoveryGroup.isNotEmpty())
            assertEquals(1L, result.resultCounts.single { it.kind == SyncRunItemKind.Recycle }.successCount)
            assertTrue(detailRows.any { it.item.kind == SyncRunItemKind.Delete && it.item.path == "old/nested/extra.txt" })
            assertTrue(sync.localStore!!.recoveries(task.id).isEmpty())
            assertTrue(sync.prepareSyncRun(task, operation).getOrThrow().preparation.plan.operations.isEmpty())
        }
    }

    @Test fun preparedBidirectionalRunsMergeEditDeleteAndKeepBothAcrossSeveralRounds(): Unit = runBlocking {
        fixture { sync, source, target, _ ->
            Files.writeString(source.resolve("a.txt"), "first source")
            Files.writeString(target.resolve("b.txt"), "first target")
            val task = sync.createOrUpdateTask(save(sync, source, target).copy(mode = SyncMode.Bidirectional)).getOrThrow()
            sync.localStore!!.confirmPreview(task.id, syncDefinitionFingerprint(task))
            val operation = sync.createOperationTask(task)
            suspend fun run() {
                val prepared = sync.prepareSyncRun(sync.getTask(task.id)!!, operation).getOrThrow()
                val result = sync.executeSyncPreparedRun(prepared, operation, "manual")
                assertEquals(SyncRunStatus.Success, result.status, result.items.toString())
                assertTrue(sync.localStore!!.recoveries(task.id).isEmpty())
            }
            run()
            assertEquals("first source", Files.readString(target.resolve("a.txt")))
            assertEquals("first target", Files.readString(source.resolve("b.txt")))
            Files.writeString(source.resolve("a.txt"), "edited source")
            Files.setLastModifiedTime(source.resolve("a.txt"), FileTime.fromMillis(20_000))
            Files.writeString(target.resolve("b.txt"), "edited target")
            Files.setLastModifiedTime(target.resolve("b.txt"), FileTime.fromMillis(30_000))
            run()
            assertEquals("edited source", Files.readString(target.resolve("a.txt")))
            assertEquals("edited target", Files.readString(source.resolve("b.txt")))
            Files.delete(source.resolve("a.txt"))
            run()
            assertFalse(Files.exists(target.resolve("a.txt")))
            Files.writeString(source.resolve("b.txt"), "left")
            Files.setLastModifiedTime(source.resolve("b.txt"), FileTime.fromMillis(40_000))
            Files.writeString(target.resolve("b.txt"), "right")
            Files.setLastModifiedTime(target.resolve("b.txt"), FileTime.fromMillis(50_000))
            run()
            assertEquals("right", Files.readString(source.resolve("b.txt")))
            assertEquals("right", Files.readString(target.resolve("b.txt")))
            for (root in listOf(source, target)) {
                val conflicts = Files.list(root).use { stream -> stream.filter { it.fileName.toString().startsWith("b (") }.toList() }
                assertEquals(1, conflicts.size)
                assertEquals("left", Files.readString(conflicts.single()))
            }
            Files.delete(source.resolve("b.txt"))
            Files.writeString(target.resolve("b.txt"), "target survivor")
            Files.setLastModifiedTime(target.resolve("b.txt"), FileTime.fromMillis(60_000))
            run()
            assertEquals("target survivor", Files.readString(source.resolve("b.txt")))
            assertTrue(sync.prepareSyncRun(task, operation).getOrThrow().preparation.plan.operations.isEmpty())
        }
    }

    @Test fun deletionThresholdDefers350PathsAndRechecksTheFrozenApproval(): Unit = runBlocking {
        fixture { sync, source, target, _ ->
            Files.writeString(source.resolve("new.txt"), "new file still copies")
            repeat(350) { Files.writeString(target.resolve("old-$it.txt"), "old $it") }
            val task = sync.createOrUpdateTask(save(sync, source, target).copy(mode = SyncMode.Mirror, deleteThreshold = 100)).getOrThrow()
            val store = sync.localStore!!
            val fingerprint = syncDefinitionFingerprint(task)
            store.confirmPreview(task.id, fingerprint)
            val operation = sync.createOperationTask(task)
            val first = sync.prepareSyncRun(task, operation).getOrThrow()
            assertEquals(350, protectSyncRunPlan(first).pendingDeletions.size)
            val run = sync.executeSyncPreparedRun(first, operation, "schedule")
            assertEquals(SyncRunStatus.Success, run.status, run.items.toString())
            assertEquals(350, run.skippedCount)
            assertEquals("new file still copies", Files.readString(target.resolve("new.txt")))
            assertEquals(350L, store.pendingDeletionCount(task.id))
            assertEquals(listOf("new.txt"), store.baselinePage(task.id, fingerprint).map { it.relativePath })
            val approved = store.pendingDeletionPage(task.id, fingerprint).toSet()
            val changed = target.resolve("old-0.txt")
            Files.writeString(changed, "changed since preview")
            val refreshed = sync.prepareSyncRun(task, operation).getOrThrow()
            val confirmation = sync.executeSyncPreparedRun(refreshed, operation, "confirm", approvedDeletions = approved)
            assertEquals(SyncRunStatus.Success, confirmation.status, confirmation.items.toString())
            assertEquals("changed since preview", Files.readString(changed))
            assertEquals(1L, store.pendingDeletionCount(task.id))
            repeat(349) { assertFalse(Files.exists(target.resolve("old-${it + 1}.txt"))) }
            assertTrue(store.recoveries(task.id).isEmpty())
        }
    }

    @Test fun suddenlyEmptySourceRequiresConsentToTheExactPreparedPlan(): Unit = runBlocking {
        fixture { sync, source, target, _ ->
            Files.writeString(source.resolve("a.txt"), "keep recoverable")
            val task = sync.createOrUpdateTask(save(sync, source, target).copy(mode = SyncMode.Mirror)).getOrThrow()
            sync.localStore!!.confirmPreview(task.id, syncDefinitionFingerprint(task))
            val operation = sync.createOperationTask(task)
            val initial = sync.prepareSyncRun(task, operation).getOrThrow()
            assertEquals(SyncRunStatus.Success, sync.executeSyncPreparedRun(initial, operation, "manual").status)
            Files.delete(source.resolve("a.txt"))
            val empty = sync.prepareSyncRun(task, operation).getOrThrow()
            assertEquals(listOf("source" to ""), protectSyncRunPlan(empty).unexpectedlyEmptyScopes)
            assertFailsWith<IllegalStateException> { sync.executeSyncPreparedRun(empty, operation, "schedule") }
            assertFailsWith<IllegalStateException> { sync.executeSyncPreparedRun(empty, operation, "confirm", consent = initial) }
            assertEquals("keep recoverable", Files.readString(target.resolve("a.txt")))
            assertEquals(SyncRunStatus.Success, sync.executeSyncPreparedRun(empty, operation, "confirm", consent = empty).status)
            assertFalse(Files.exists(target.resolve("a.txt")))
            assertTrue(Files.isDirectory(source))
            assertTrue(sync.localStore!!.localState(task.id)!!.baselineEstablished)
            assertTrue(sync.localStore!!.baselinePage(task.id, syncDefinitionFingerprint(task)).isEmpty())
        }
    }

    @Test fun selectedCloudReplacementPersistsItsCurrentIdAcrossModeChangesAndTakeover(): Unit = runBlocking {
        fixture { sync, _, target, taskStore ->
            val http = HttpClient(MockEngine { error("The memory backend does not use HTTP") })
            try {
                val config = NetworkConnectionConfig("GoogleDrive", "https://memory.invalid", "", "")
                val backend = MemoryIds(config, StorageHttpApi({ "test" }, http))
                val network = object : Network("Cloud", "/", "GoogleDrive", "https://memory.invalid", "", "") {
                    override val client: StorageProvider = backend
                }
                val entry = NetworkEntry(1, network, true)
                sync.networkState.entries.add(entry)
                val selected = network.getFile(FileSimpleInfo.pathFileSimpleInfo("/parent/a.txt").withCopy(
                    storageReference = StorageReference("old", "P"))).getOrThrow()
                val targetFile = Files.writeString(target.resolve("a.txt"), "new target version")
                Files.setLastModifiedTime(targetFile, FileTime.fromMillis(30_000))
                val task = sync.createOrUpdateTask(SyncTask(0, "Cloud selection", sourceType = SyncEndpointType.Network,
                    sourceRef = selected.protocolId, sourcePath = selected.path, sourceStorageReference = selected.storageReference,
                    targetType = SyncEndpointType.Local, targetPath = target.toString(), mode = SyncMode.Bidirectional,
                    selectedSources = listOf(selected))).getOrThrow()
                val fingerprint = syncDefinitionFingerprint(task)
                val store = checkNotNull(sync.localStore)
                store.confirmPreview(task.id, fingerprint)
                val operation = sync.createOperationTask(task)
                val prepared = sync.prepareSyncRun(task, operation).getOrThrow()
                sync.executeSyncPathRecovery(prepared, prepared.preparation.plan.paths.single(), operation, 15)
                assertNull(currentCoroutineContext()[TaskUploadSourceIdentity])
                sync.recoverSyncTaskBeforeScan(task, operation)
                val saved = checkNotNull(sync.getTask(task.id))
                val current = saved.resolvedSelectedSources().single().storageReference!!
                assertTrue(current.id != "old")
                assertEquals("P", current.parentId)
                assertEquals("old", saved.selectedSources.single().storageReference!!.id)
                assertEquals(fingerprint, syncDefinitionFingerprint(saved))
                assertEquals("new target version", backend.contents.getValue(current.id).decodeToString())
                assertTrue(backend.nodes.getValue("old").parents != listOf("P"))
                assertTrue(sync.prepareSyncRun(saved, operation).getOrThrow().preparation.plan.operations.isEmpty())
                val mirror = sync.createOrUpdateTask(saved.copy(mode = SyncMode.Mirror)).getOrThrow()
                assertFalse(store.localState(task.id)!!.previewConfirmed)
                assertEquals(current.id, sync.prepareSyncRun(mirror, operation).getOrThrow()
                    .preparation.participants.first().state("a.txt")!!.storageReference!!.id)
                val imported = taskStore.saveTask(mirror.copy(ownerDeviceId = "other"))
                sync.reloadTasksFromStore()
                val taken = sync.createOrUpdateTask(imported, takeOwnership = true).getOrThrow()
                assertEquals("fixture", taken.ownerDeviceId)
                assertFalse(store.localState(task.id)!!.previewConfirmed)
                assertEquals(current.id, sync.prepareSyncRun(taken, operation).getOrThrow()
                    .preparation.participants.first().state("a.txt")!!.storageReference!!.id)
                val cache = Files.createTempDirectory(Path.of(PathUtils.getCachePath()), "sync-permission-")
                try {
                    val owned = Files.writeString(cache.resolve("stage"), "data")
                    val unrelated = Files.writeString(cache.resolve("stage-other"), "data")
                    assertTrue(SensitiveFileAccessPolicy.classify(owned.toString()).isProtected)
                    val remote = StoragePath("/parent/authorized", StorageReference(ancestorId = "P", ancestorPath = "/parent"))
                    assertTrue(network.uploadFileFromLocal(owned.toString(), remote, 4).isFailure)
                    withContext(TaskUploadSourceIdentity(selected, privateStagePath = owned.toString())) {
                        assertTrue(network.uploadFileFromLocal(unrelated.toString(), remote, 4).isFailure)
                        assertTrue(network.uploadFileFromLocal(owned.toString(), remote, 4).getOrThrow())
                    }
                    assertNull(currentCoroutineContext()[TaskUploadSourceIdentity])
                    assertTrue(network.uploadFileFromLocal(owned.toString(), remote, 4).isFailure)
                } finally { cache.toFile().deleteRecursively() }
            } finally { http.close() }
        }
    }

    @Test fun deletingSelectedTargetDirectoryCleansChildrenAndPreservesSelectedPhysicalRoot(): Unit = runBlocking {
        fixture { sync, source, target, _ ->
            val directory = Files.createDirectory(source.resolve("photos"))
            val targetDirectory = Files.createDirectory(target.resolve("photos"))
            Files.writeString(directory.resolve("a"), "keep recoverable")
            Files.writeString(targetDirectory.resolve("a"), "keep recoverable")
            val ordinary = save(sync, source, target)
            val task = sync.createOrUpdateTask(ordinary.copy(mode = SyncMode.Bidirectional,
                selectedSources = listOf(FileUtils.getFile(FileAccessPermission.Allowed, directory.toString()).getOrThrow()))).getOrThrow()
            val store = checkNotNull(sync.localStore)
            val fingerprint = syncDefinitionFingerprint(task)
            store.confirmPreview(task.id, fingerprint)
            suspend fun state(path: Path) = FileUtils.getFile(FileAccessPermission.Allowed, path.toString()).getOrThrow().syncEntryState()
            store.commitVerified(task.id, fingerprint, listOf(
                SyncBaseline("photos", mapOf("source" to state(directory), "target-0" to state(targetDirectory))),
                SyncBaseline("photos/a", mapOf("source" to state(directory.resolve("a")), "target-0" to state(targetDirectory.resolve("a")))),
            ))
            targetDirectory.toFile().deleteRecursively()
            val operation = sync.createOperationTask(task)
            val prepared = sync.prepareSyncRun(task, operation).getOrThrow()
            assertTrue(prepared.preparation.plan.operations.any { it.kind == SyncOperationKind.Delete && it.participant == "source" })
            for (path in prepared.preparation.plan.paths.filter { it.operations.isNotEmpty() }) {
                sync.executeSyncPathRecovery(prepared, path, operation, 14)
            }
            assertTrue(Files.isDirectory(directory))
            assertTrue(Files.isDirectory(targetDirectory))
            assertFalse(Files.exists(directory.resolve("a")))
            val deleted = store.recoveries(task.id).single { it.action == SyncRecoveryAction.Delete }
            assertEquals("keep recoverable", Files.readString(Path.of(deleted.oldPath)))
            assertTrue(Path.of(deleted.oldPath).startsWith(directory))
            sync.recoverSyncTaskBeforeScan(task, operation)
            assertTrue(sync.prepareSyncRun(task, operation).getOrThrow().preparation.plan.operations.isEmpty())
        }
    }

    @Test fun backupSourceChangedDuringSharedSftpCopyIsNeverCleaned(): Unit = runBlocking {
        fixture { sync, source, target, _ ->
            val sourceFile = Files.writeString(source.resolve("a.txt"), "new source version")
            val originalTime = Files.getLastModifiedTime(sourceFile).toMillis()
            val key = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
            val server = SshServer.setUpDefaultServer().apply {
                host = "127.0.0.1"; port = 0
                keyPairProvider = KeyPairProvider.wrap(key)
                passwordAuthenticator = PasswordAuthenticator { _, _, _ -> true }
                subsystemFactories = listOf(SftpSubsystemFactory.Builder().build())
                fileSystemFactory = VirtualFileSystemFactory(target)
            }
            try {
                server.start()
                var copied = false
                val network = object : Network("target", "/", "SFTP", "127.0.0.1:${server.port}", "test", "test",
                    extras = NetworkDriveExtras(sftp = SftpDriveExtras(knownHosts = "[127.0.0.1]:${server.port} ${PublicKeyEntry.toString(key.public)}"))) {
                    override suspend fun copyTo(task: Task, srcFileSimpleInfo: FileSimpleInfo, destFileSimpleInfo: FileSimpleInfo): Result<Boolean> {
                        val result = super.copyTo(task, srcFileSimpleInfo, destFileSimpleInfo)
                        if (result.getOrDefault(false)) {
                            copied = true
                            Files.writeString(sourceFile, "new source CHANGED")
                            Files.setLastModifiedTime(sourceFile, FileTime.fromMillis(originalTime + 1))
                        }
                        return result
                    }
                }
                val entry = NetworkEntry(1, network, true)
                sync.networkState.entries.add(entry)
                val ordinary = save(sync, source, target)
                val task = sync.createOrUpdateTask(entry.storageDestination("/").applyTo(ordinary).withMode(SyncMode.BackupThenClean)).getOrThrow()
                sync.localStore!!.confirmPreview(task.id, syncDefinitionFingerprint(task))
                val operation = sync.createOperationTask(task)
                val prepared = sync.prepareSyncRun(task, operation).getOrThrow()
                assertFailsWith<IllegalStateException> { sync.executeSyncPathRecovery(prepared, prepared.preparation.plan.paths.single(), operation, 13) }
                assertTrue(copied)
                assertEquals("new source CHANGED", Files.readString(sourceFile))
                assertFalse(Files.exists(target.resolve("a.txt")))
                assertTrue(sync.localStore!!.recoveries(task.id).isEmpty())
            } finally { server.stop(true) }
        }
    }

    @Test fun failedOrCanceledSecondBackupKeepsSourceAndRetriesTheWholeVerifiedGroup(): Unit = runBlocking {
        for (fault in listOf("failure", "cancel", "source_change")) fixture { sync, source, target, _ ->
            val selected = Files.writeString(source.resolve("a.txt"), "source version")
            val second = Files.createDirectory(target.parent.resolve("second"))
            for (root in listOf(target, second)) Files.writeString(root.resolve("a.txt"), "old backup")
            var copies = 0
            var armed = true
            withSftpTargets(sync, listOf(target, second), copyFailure = {
                if (++copies == 2 && armed) when (fault) {
                    "cancel" -> CancellationException("injected backup cancellation")
                    "failure" -> IllegalStateException("injected second backup failure")
                    else -> { Files.writeString(selected, "CHANGED source version"); null }
                } else null
            }) { destinations ->
                val task = sync.createOrUpdateTask(destinations.first().applyTo(save(sync, source, target))
                    .withMode(SyncMode.BackupThenClean).copy(destinations = destinations, selectRecommended = false, retryCount = 0)).getOrThrow()
                val store = sync.localStore!!
                val fingerprint = syncDefinitionFingerprint(task)
                store.confirmPreview(task.id, fingerprint)
                val operation = sync.createOperationTask(task)
                val prepared = sync.prepareSyncRun(task, operation).getOrThrow()
                val failed = sync.executeSyncPreparedRun(prepared, operation, "manual")
                assertEquals(if (fault == "cancel") SyncRunStatus.Canceled else SyncRunStatus.Failure, failed.status, "$fault: $failed")
                val expected = if (fault == "source_change") "CHANGED source version" else "source version"
                assertEquals(expected, Files.readString(selected))
                for (root in listOf(target, second)) assertEquals("old backup", Files.readString(root.resolve("a.txt")))
                assertTrue(store.baselinePage(task.id, fingerprint).isEmpty())
                armed = false
                val retryOperation = sync.createOperationTask(task)
                val retry = sync.prepareSyncRun(task, retryOperation).getOrThrow()
                assertEquals(2, retry.preparation.plan.paths.single().backupPaths.size)
                val completed = sync.executeSyncPreparedRun(retry, retryOperation, "retry")
                assertEquals(SyncRunStatus.Success, completed.status, "$fault: $completed")
                assertFalse(Files.exists(selected))
                assertTrue(Files.isDirectory(source))
                val baseline = store.baselinePage(task.id, fingerprint).single()
                assertNull(baseline.participants["source"])
                assertEquals(2, baseline.backupCopies.size)
                for ((index, root) in listOf(target, second).withIndex()) {
                    assertEquals("old backup", Files.readString(root.resolve("a.txt")))
                    assertEquals(expected, Files.readString(root.resolve(baseline.backupCopies.getValue("target-$index").relativePath)))
                }
                assertTrue(store.recoveries(task.id).isEmpty())
            }
        }
    }

    @Test fun skippedBackupOnOneFixedTargetNeverCleansTheSource(): Unit = runBlocking {
        fixture { sync, source, target, _ ->
            val selected = Files.writeString(source.resolve("a.txt"), "source version")
            val second = Files.createDirectory(target.parent.resolve("second"))
            Files.writeString(second.resolve("a.txt"), "different existing backup")
            withSftpTargets(sync, listOf(target, second)) { destinations ->
                val task = sync.createOrUpdateTask(destinations.first().applyTo(save(sync, source, target))
                    .withMode(SyncMode.BackupThenClean).copy(destinations = destinations, selectRecommended = false,
                        conflictPolicy = SyncConflictPolicy.Skip)).getOrThrow()
                sync.localStore!!.confirmPreview(task.id, syncDefinitionFingerprint(task))
                val operation = sync.createOperationTask(task)
                val prepared = sync.prepareSyncRun(task, operation).getOrThrow()
                assertTrue(prepared.preparation.plan.operations.none { it.kind == SyncOperationKind.CleanSource })
                assertEquals(SyncRunStatus.Success, sync.executeSyncPreparedRun(prepared, operation, "manual").status)
                assertEquals("source version", Files.readString(selected))
                assertEquals("different existing backup", Files.readString(second.resolve("a.txt")))
            }
        }
    }

    @Test fun backupCleansSourceOnlyAfterEveryFixedTargetAndKeepsExistingVersions(): Unit = runBlocking {
        fixture { sync, source, target, _ ->
            val selected = Files.writeString(source.resolve("a.txt"), "new source version")
            Files.writeString(target.resolve("a.txt"), "keep old backup")
            val second = Files.createDirectory(target.parent.resolve("second"))
            val ordinary = save(sync, source, target)
            withSftpTargets(sync, listOf(target, second)) { destinations ->
                val task = sync.createOrUpdateTask(destinations.first().applyTo(ordinary).withMode(SyncMode.BackupThenClean)
                    .copy(destinations = destinations, selectRecommended = false)).getOrThrow()
                sync.localStore!!.confirmPreview(task.id, syncDefinitionFingerprint(task))
                val operation = sync.createOperationTask(task)
                val prepared = sync.prepareSyncRun(task, operation).getOrThrow()
                val path = prepared.preparation.plan.paths.single()
                assertEquals(2, path.backupPaths.size)
                assertTrue(path.operations.any { it.kind == SyncOperationKind.CleanSource })
                val completed = sync.executeSyncPathRecovery(prepared, path, operation, 11)
                assertFalse(Files.exists(selected))
                assertTrue(Files.isDirectory(source))
                assertEquals("keep old backup", Files.readString(target.resolve("a.txt")))
                assertEquals("new source version", Files.readString(target.resolve(path.backupPaths.getValue("target-0"))))
                assertEquals("new source version", Files.readString(second.resolve(path.backupPaths.getValue("target-1"))))
                assertEquals(1, completed.count { it.action == SyncRecoveryAction.Delete && it.participant == "source" })
                sync.recoverSyncTaskBeforeScan(task, operation)
                val baseline = sync.localStore!!.baselinePage(task.id, syncDefinitionFingerprint(task)).single()
                assertEquals(null, baseline.participants["source"])
                assertEquals(2, baseline.backupCopies.size)
                assertTrue(sync.localStore!!.recoveries(task.id).isEmpty())
            }
        }
    }

    @Test fun alreadyMatchingBackupCanBeVerifiedWithoutCopyingBeforeCleanup(): Unit = runBlocking {
        fixture { sync, source, target, _ ->
            val selected = Files.writeString(source.resolve("a"), "same")
            val copied = Files.writeString(target.resolve("a"), "same")
            Files.setLastModifiedTime(selected, FileTime.fromMillis(10_000))
            Files.setLastModifiedTime(copied, FileTime.fromMillis(10_000))
            val ordinary = save(sync, source, target)
            val task = sync.createOrUpdateTask(ordinary.withMode(SyncMode.BackupThenClean).copy(conflictPolicy = SyncConflictPolicy.Skip)).getOrThrow()
            sync.localStore!!.confirmPreview(task.id, syncDefinitionFingerprint(task))
            val operation = sync.createOperationTask(task)
            val prepared = sync.prepareSyncRun(task, operation).getOrThrow()
            assertEquals(SyncOperationKind.CleanSource, prepared.preparation.plan.operations.single().kind)
            sync.executeSyncPathRecovery(prepared, prepared.preparation.plan.paths.single(), operation, 12)
            assertFalse(Files.exists(selected))
            assertEquals("same", Files.readString(copied))
            sync.recoverSyncTaskBeforeScan(task, operation)
            assertEquals(1, sync.localStore!!.baselinePage(task.id, syncDefinitionFingerprint(task)).single().backupCopies.size)
        }
    }

    @Test fun selectedDirectoryRootTypeConflictIsProtectedAndMissingRootAbortsPreparation(): Unit = runBlocking {
        fixture { sync, source, target, _ ->
            val directory = Files.createDirectory(source.resolve("photos"))
            Files.writeString(target.resolve("photos"), "keep target file")
            val ordinary = save(sync, source, target)
            val task = sync.createOrUpdateTask(ordinary.copy(mode = SyncMode.Bidirectional,
                selectedSources = listOf(FileUtils.getFile(FileAccessPermission.Allowed, directory.toString()).getOrThrow()))).getOrThrow()
            val operation = sync.createOperationTask(task)
            val prepared = sync.prepareSyncRun(task, operation).getOrThrow()
            assertTrue(prepared.preparation.plan.paths.single().let { it.conflict && it.skipped })
            assertTrue(prepared.preparation.plan.operations.isEmpty())
            Files.delete(directory)
            assertTrue(sync.prepareSyncRun(task, operation).isFailure)
            assertEquals("keep target file", Files.readString(target.resolve("photos")))
        }
    }

    @Test fun mirrorPathUsesSharedCopyAndKeepsJournalUntilBaselineAcceptance(): Unit = runBlocking {
        fixture { sync, source, target, _ ->
            Files.writeString(source.resolve("a.txt"), "new source version")
            Files.writeString(target.resolve("a.txt"), "original target")
            val ordinary = save(sync, source, target)
            val task = sync.createOrUpdateTask(ordinary.copy(mode = SyncMode.Mirror)).getOrThrow()
            val store = checkNotNull(sync.localStore)
            store.confirmPreview(task.id, syncDefinitionFingerprint(task))
            val operation = sync.createOperationTask(task)
            val prepared = sync.prepareSyncRun(task, operation).getOrThrow()
            val result = sync.executeSyncPathRecovery(prepared, prepared.preparation.plan.paths.single(), operation, 7)
            assertEquals("new source version", Files.readString(target.resolve("a.txt")))
            assertEquals("new source version", Files.readString(source.resolve("a.txt")))
            assertEquals("original target", Files.readString(Path.of(result.single().oldPath)))
            assertEquals(SyncRecoveryPhase.Verified, store.recoveries(task.id).single().phase)
            assertTrue(store.baselinePage(task.id, syncDefinitionFingerprint(task)).isEmpty())
            assertTrue(sync.prepareSyncRun(task, operation).isFailure)
            sync.recoverSyncTaskBeforeScan(task, operation)
            assertTrue(store.recoveries(task.id).isEmpty())
            assertEquals(1, store.baselinePage(task.id, syncDefinitionFingerprint(task)).size)
            assertTrue(sync.prepareSyncRun(task, operation).getOrThrow().preparation.plan.operations.isEmpty())
        }
    }

    @Test fun selectedFileGroupKeepsThreeVersionsAndAvoidsConflictSiblingsWithoutWritingUnrelatedFiles(): Unit = runBlocking {
        fixture { sync, source, target, _ ->
            val second = Files.createDirectory(target.parent.resolve("second"))
            val roots = listOf(source, target, second)
            val versions = listOf("source version", "first different target version", "second entirely different target version")
            for ((index, root) in roots.withIndex()) {
                Files.writeString(root.resolve("a.txt"), versions[index])
                Files.setLastModifiedTime(root.resolve("a.txt"), FileTime.fromMillis(20_000))
                Files.writeString(root.resolve("unrelated.txt"), "unrelated $index")
            }
            val selected = source.resolve("a.txt")
            withSftpTargets(sync, listOf(target, second)) { destinations ->
                val task = sync.createOrUpdateTask(destinations.first().applyTo(save(sync, source, target)).withMode(SyncMode.Bidirectional).copy(
                    sourcePath = selected.toString(),
                    selectedSources = listOf(FileUtils.getFile(FileAccessPermission.Allowed, selected.toString()).getOrThrow()),
                    destinations = destinations, selectRecommended = false)).getOrThrow()
                val store = sync.localStore!!
                store.confirmPreview(task.id, syncDefinitionFingerprint(task))
                val operation = sync.createOperationTask(task)
                val initial = sync.prepareSyncRun(task, operation).getOrThrow()
                val collisions = initial.preparation.plan.operations.filter { it.kind == SyncOperationKind.ConflictCopy }
                    .map { it.relativePath }.toSet()
                assertEquals(2, collisions.size)
                for (root in roots) for (path in collisions) Files.writeString(root.resolve(path), "existing collision $path")
                val prepared = sync.prepareSyncRun(task, operation).getOrThrow()
                assertEquals(1, prepared.preparation.plan.paths.count { it.conflict })
                assertTrue(prepared.preparation.plan.operations.filter { it.kind == SyncOperationKind.ConflictCopy }
                    .all { it.relativePath !in collisions })
                assertEquals(SyncRunStatus.Success, sync.executeSyncPreparedRun(prepared, operation, "manual").status)
                for ((index, root) in roots.withIndex()) {
                    assertEquals(versions.first(), Files.readString(root.resolve("a.txt")))
                    for (path in collisions) assertEquals("existing collision $path", Files.readString(root.resolve(path)))
                    assertEquals("unrelated $index", Files.readString(root.resolve("unrelated.txt")))
                    val retained = Files.list(root).use { files -> files.filter {
                        Files.isRegularFile(it) && !syncReservedPath(it.fileName.toString())
                    }.map { Files.readString(it) }.toList().toSet() }
                    assertTrue(retained.containsAll(versions), "$index: $retained")
                }
                assertEquals(3, store.baselineEntry(task.id, syncDefinitionFingerprint(task), "a.txt")!!.participants.size)
                assertTrue(store.recoveries(task.id).isEmpty())
                assertTrue(sync.prepareSyncRun(task, sync.createOperationTask(task)).getOrThrow().preparation.plan.operations.isEmpty())
            }
        }
    }

    @Test fun selectedFileConflictsPreserveBothVersionsWithOnlyAuthorizedSiblingWrites(): Unit = runBlocking {
        fixture { sync, source, target, _ ->
            val selected = Files.writeString(source.resolve("a.txt"), "source version")
            Files.writeString(source.resolve("unrelated.txt"), "keep unrelated")
            val targetFile = Files.writeString(target.resolve("a.txt"), "target version")
            Files.setLastModifiedTime(selected, FileTime.fromMillis(10_000))
            Files.setLastModifiedTime(targetFile, FileTime.fromMillis(30_000))
            val ordinary = save(sync, source, target)
            val task = sync.createOrUpdateTask(ordinary.copy(mode = SyncMode.Bidirectional,
                sourcePath = selected.toString(), selectedSources = listOf(FileUtils.getFile(FileAccessPermission.Allowed, selected.toString()).getOrThrow())))
                .getOrThrow()
            sync.localStore!!.confirmPreview(task.id, syncDefinitionFingerprint(task))
            val operation = sync.createOperationTask(task)
            val prepared = sync.prepareSyncRun(task, operation).getOrThrow()
            val plan = prepared.preparation.plan.paths.single()
            assertTrue(plan.conflict)
            val result = sync.executeSyncPathRecovery(prepared, plan, operation, 8)
            assertEquals("target version", Files.readString(selected))
            assertEquals("target version", Files.readString(targetFile))
            for (copy in plan.operations.filter { it.kind == SyncOperationKind.ConflictCopy }) {
                val root = if (copy.participant == "source") source else target
                assertEquals("source version", Files.readString(root.resolve(copy.relativePath)))
            }
            assertEquals("keep unrelated", Files.readString(source.resolve("unrelated.txt")))
            assertTrue(result.all { it.phase == SyncRecoveryPhase.Verified })
            assertEquals("source version", Files.readString(Path.of(result.single { it.action == SyncRecoveryAction.Overwrite }.oldPath)))
            sync.recoverSyncTaskBeforeScan(task, operation)
            assertTrue(sync.localStore!!.recoveries(task.id).isEmpty())
            assertTrue(sync.prepareSyncRun(task, operation).getOrThrow().preparation.plan.operations.isEmpty())
        }
    }

    @Test fun sftpPathStagesVerifiesAndRecyclesThroughRealProtocol(): Unit = runBlocking {
        fixture { sync, source, target, _ ->
            val sourceFile = Files.writeString(source.resolve("a.txt"), "new source version")
            val targetFile = Files.writeString(target.resolve("a.txt"), "old remote version")
            Files.setLastModifiedTime(sourceFile, FileTime.fromMillis(30_000))
            Files.setLastModifiedTime(targetFile, FileTime.fromMillis(10_000))
            val key = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
            val server = SshServer.setUpDefaultServer().apply {
                host = "127.0.0.1"; port = 0
                keyPairProvider = KeyPairProvider.wrap(key)
                passwordAuthenticator = PasswordAuthenticator { _, _, _ -> true }
                subsystemFactories = listOf(SftpSubsystemFactory.Builder().build())
                fileSystemFactory = VirtualFileSystemFactory(target)
            }
            try {
                server.start()
                val network = Network("target", "/", "SFTP", "127.0.0.1:${server.port}", "test", "test",
                    extras = NetworkDriveExtras(sftp = SftpDriveExtras(knownHosts = "[127.0.0.1]:${server.port} ${PublicKeyEntry.toString(key.public)}")))
                val entry = NetworkEntry(1, network, true)
                sync.networkState.entries.add(entry)
                val ordinary = save(sync, source, target)
                val task = sync.createOrUpdateTask(entry.storageDestination("/").applyTo(ordinary).copy(mode = SyncMode.Mirror)).getOrThrow()
                sync.localStore!!.confirmPreview(task.id, syncDefinitionFingerprint(task))
                val operation = sync.createOperationTask(task)
                val prepared = sync.prepareSyncRun(task, operation).getOrThrow()
                assertEquals(SyncOperationKind.Overwrite, prepared.preparation.plan.operations.single().kind)
                val result = sync.executeSyncPathRecovery(prepared, prepared.preparation.plan.paths.single(), operation, 9)
                assertEquals("new source version", Files.readString(target.resolve("a.txt")))
                assertEquals("old remote version", Files.readString(target.resolve(result.single().oldPath.removePrefix("/"))))
                assertTrue(result.single().placedState!!.contentSha256.length == 64)
                assertEquals(SyncRecoveryPhase.Verified, sync.localStore!!.recoveries(task.id).single().phase)
                sync.recoverSyncTaskBeforeScan(task, operation)
                assertTrue(sync.localStore!!.recoveries(task.id).isEmpty())
                assertTrue(sync.prepareSyncRun(task, operation).getOrThrow().preparation.plan.operations.isEmpty())
            } finally { server.stop(true) }
        }
    }

    @Test fun changedOrDeletedDefinitionsOnlyRestoreMissingOriginalsAndRetainRecoveryFiles(): Unit = runBlocking {
        for (deleted in listOf(false, true)) fixture { sync, source, target, taskStore ->
            Files.writeString(source.resolve("a"), "new version")
            val final = Files.writeString(target.resolve("a"), "old version")
            val temporary = Files.writeString(target.resolve(".folderspan-tmp-fixture-1-new"), "new version")
            val oldPath = target.resolve("old")
            val ordinary = save(sync, source, target)
            val task = sync.createOrUpdateTask(ordinary.copy(mode = SyncMode.Mirror)).getOrThrow()
            val store = checkNotNull(sync.localStore)
            val endpoint = SyncRecoveryEndpoint(SyncEndpointType.Local, "", target.toString())
            val operation = sync.createOperationTask(task)
            val io = SyncRecoveryEndpointOperations(sync.endpointFiles, operation, mapOf(endpoint to Local()))
            val old = checkNotNull(io.verifiedState(endpoint, final.toString()))
            val fresh = checkNotNull(io.verifiedState(endpoint, temporary.toString()))
            val record = SyncFileRecovery("interrupted", task.id, syncDefinitionFingerprint(task), task.ownerDeviceId, "interrupted", "target-0",
                endpoint, "a", final.toString(), temporary.toString(), oldPath.toString(), old, fresh, true,
                stagedState = fresh, previewFingerprint = syncPreviewFingerprint(task))
            store.saveRecovery(record)
            io.move(endpoint, final.toString(), oldPath.toString())
            store.saveRecovery(record.copy(phase = SyncRecoveryPhase.OldPreserved, oldStoredState = io.verifiedState(endpoint, oldPath.toString())))
            val current = if (deleted) {
                taskStore.deleteTask(task.id)
                sync.reloadTasksFromStore()
                task
            } else {
                val newTarget = Files.createDirectory(target.parent.resolve("new-target"))
                sync.createOrUpdateTask(task.copy(targetPath = newTarget.toString())).getOrThrow()
            }
            assertFailsWith<IllegalStateException> { sync.recoverSyncTaskBeforeScan(current, operation) }
            assertEquals("old version", Files.readString(final))
            assertEquals("new version", Files.readString(temporary))
            assertFalse(Files.exists(oldPath))
            assertEquals(1, store.recoveries(task.id).single().attempt)
            assertEquals(SyncRecoveryPhase.Prepared, store.recoveries(task.id).single().phase)
            if (deleted) assertEquals(null, store.localState(task.id))
            else assertFalse(store.localState(task.id)!!.baselineEstablished)
        }
    }

    @Test fun changedParticipantsBeforeRecoveryAcceptanceKeepJournalAndBaselineUnchanged(): Unit = runBlocking {
        fixture { sync, source, target, _ ->
            val sourcePath = Files.writeString(source.resolve("a"), "source")
            Files.writeString(target.resolve("a"), "old")
            val ordinary = save(sync, source, target)
            val task = sync.createOrUpdateTask(ordinary.copy(mode = SyncMode.Mirror)).getOrThrow()
            sync.localStore!!.confirmPreview(task.id, syncDefinitionFingerprint(task))
            val operation = sync.createOperationTask(task)
            val prepared = sync.prepareSyncRun(task, operation).getOrThrow()
            sync.executeSyncPathRecovery(prepared, prepared.preparation.plan.paths.single(), operation, 10)
            Files.writeString(sourcePath, "new user edit")
            assertFailsWith<IllegalStateException> { sync.recoverSyncTaskBeforeScan(task, operation) }
            assertEquals("new user edit", Files.readString(sourcePath))
            assertEquals("source", Files.readString(target.resolve("a")))
            assertEquals(1, sync.localStore!!.recoveries(task.id).size)
            assertTrue(sync.localStore!!.baselinePage(task.id, syncDefinitionFingerprint(task)).isEmpty())
        }
    }

    @Test fun unfinishedRecoveryAlsoBlocksUpdateMode(): Unit = runBlocking {
        fixture { sync, source, target, _ ->
            val sourcePath = Files.writeString(source.resolve("a"), "new source")
            val targetPath = Files.writeString(target.resolve("a"), "keep target")
            val task = save(sync, source, target)
            val old = FileUtils.getFile(FileAccessPermission.Allowed, targetPath.toString()).getOrThrow().syncEntryState()
            val fresh = FileUtils.getFile(FileAccessPermission.Allowed, sourcePath.toString()).getOrThrow().syncEntryState()
            sync.localStore!!.saveRecovery(SyncFileRecovery("pending", task.id, syncDefinitionFingerprint(task), "fixture", "group", "target",
                SyncRecoveryEndpoint(SyncEndpointType.Local, "", target.toString()), "a", targetPath.toString(),
                target.resolve(".folderspan-tmp-pending").toString(), target.resolve("old").toString(), old, fresh, true))
            assertFailsWith<IllegalStateException> { sync.runTaskInternal(task, "manual") }
            assertTrue(sync.prepareSyncRun(task, sync.createOperationTask(task)).isFailure)
            assertEquals("keep target", Files.readString(targetPath))
            assertEquals("new source", Files.readString(sourcePath))
            assertEquals(1, sync.localStore!!.recoveries().size)
        }
    }

    @Test fun advancedPreparationComparesContentWithoutWritingOrConfirming(): Unit = runBlocking {
        fixture { sync, source, target, _ ->
            val originalSource = Files.writeString(source.resolve("a.txt"), "same data")
            val originalTarget = Files.writeString(target.resolve("a.txt"), "same data")
            Files.setLastModifiedTime(originalSource, FileTime.fromMillis(10_000))
            Files.setLastModifiedTime(originalTarget, FileTime.fromMillis(30_000))
            val ordinary = save(sync, source, target)
            val task = sync.createOrUpdateTask(ordinary.copy(mode = SyncMode.Bidirectional)).getOrThrow()
            val prepared = sync.prepareSyncRun(task, sync.createOperationTask(task)).getOrThrow()
            assertTrue(prepared.preparation.plan.operations.isEmpty())
            assertEquals(18L, prepared.preparation.comparisonReadBytes)
            assertEquals(10_000, Files.getLastModifiedTime(originalSource).toMillis())
            assertEquals(30_000, Files.getLastModifiedTime(originalTarget).toMillis())
            assertEquals("same data", Files.readString(originalSource))
            assertFalse(sync.localStore!!.localState(task.id)!!.previewConfirmed)
            assertFalse(sync.localStore!!.localState(task.id)!!.baselineEstablished)
            assertTrue(sync.localStore!!.recoveries().isEmpty())
            Files.writeString(originalTarget, "different")
            Files.setLastModifiedTime(originalTarget, FileTime.fromMillis(40_000))
            val conflict = sync.prepareSyncRun(task, sync.createOperationTask(task)).getOrThrow()
            assertTrue(conflict.preparation.plan.paths.single().conflict)
            assertEquals(2, conflict.preparation.plan.operations.count { it.kind == SyncOperationKind.ConflictCopy })
            assertEquals("same data", Files.readString(originalSource))
            assertEquals("different", Files.readString(originalTarget))
            Files.list(source).use { assertEquals(1L, it.count()) }
            Files.list(target).use { assertEquals(1L, it.count()) }
        }
    }

    @Test fun fourModesKeepSelectedDirectoryDepthAndIgnoreRulesWithTwoRealSftpTargets(): Unit = runBlocking {
        for (mode in SyncMode.entries) fixture { sync, source, target, _ ->
            val photos = Files.createDirectory(source.resolve("photos"))
            Files.writeString(photos.resolve("top.jpg"), "photo")
            Files.writeString(photos.resolve("ignored.jpg"), "keep ignored")
            Files.writeString(photos.resolve("private.txt"), "keep filtered")
            Files.writeString(photos.resolve(".gitignore"), "ignored.jpg\n")
            Files.createDirectory(photos.resolve("nested"))
            Files.writeString(photos.resolve("nested/deep.jpg"), "keep deep")
            val selectedFile = Files.writeString(source.resolve("current.txt"), "selected file")
            Files.writeString(source.resolve("unrelated.txt"), "source unrelated")
            val target2 = Files.createDirectory(target.parent.resolve("target2"))
            for (directory in listOf(target, target2)) Files.writeString(directory.resolve("unrelated.txt"), "target unrelated")
            withSftpTargets(sync, listOf(target, target2)) { destinations ->
                val ordinary = save(sync, photos, target, ignore = true)
                val task = sync.createOrUpdateTask(destinations.first().applyTo(ordinary).withMode(mode).copy(
                    selectedSources = listOf(photos, selectedFile).map { FileUtils.getFile(FileAccessPermission.Allowed, it.toString()).getOrThrow() },
                    destinations = destinations, selectRecommended = false, includeRaw = "*.jpg;*.txt",
                    filterRaw = "private.txt", includeSubdirectories = false)).getOrThrow()
                if (mode == SyncMode.Update) sync.runTaskInternal(task, "manual") else {
                    val preview = sync.createSyncPreview(task.id).getOrThrow()
                    sync.confirmSyncPreview(preview.token).getOrThrow()
                    awaitFinishedRun(sync, task.id)
                }
                val result = sync.recordsFor(task.id).first()
                assertEquals(SyncRunStatus.Success, result.status, "$mode: $result")
                for (directory in listOf(target, target2)) {
                    assertEquals("photo", Files.readString(directory.resolve("photos/top.jpg")), mode.toString())
                    assertEquals("selected file", Files.readString(directory.resolve("current.txt")))
                    assertFalse(Files.exists(directory.resolve("photos/ignored.jpg")))
                    assertFalse(Files.exists(directory.resolve("photos/private.txt")))
                    assertFalse(Files.exists(directory.resolve("photos/nested")))
                    assertFalse(Files.exists(directory.resolve("photos/.gitignore")))
                    assertEquals("target unrelated", Files.readString(directory.resolve("unrelated.txt")))
                }
                assertTrue(Files.isDirectory(photos))
                assertEquals(mode != SyncMode.BackupThenClean, Files.exists(photos.resolve("top.jpg")))
                assertEquals(mode != SyncMode.BackupThenClean, Files.exists(selectedFile))
                assertEquals("keep ignored", Files.readString(photos.resolve("ignored.jpg")))
                assertEquals("keep deep", Files.readString(photos.resolve("nested/deep.jpg")))
                assertEquals("source unrelated", Files.readString(source.resolve("unrelated.txt")))
            }
        }
    }

    @Test fun unreadableSftpSubtreeProtectsAllGroupTargetsInEveryMode(): Unit = runBlocking {
        for (mode in SyncMode.entries) for (previewUpdate in if (mode == SyncMode.Update) listOf(false, true) else listOf(true)) {
            fixture { sync, source, target, _ ->
                val photos = Files.createDirectory(source.resolve("photos"))
                Files.createDirectory(photos.resolve("blocked"))
                Files.writeString(photos.resolve("blocked/keep.txt"), "source keep")
                Files.writeString(photos.resolve("safe.txt"), "safe source")
                val target2 = Files.createDirectory(target.parent.resolve("target2"))
                for (directory in listOf(target, target2)) {
                    Files.createDirectories(directory.resolve("photos/blocked"))
                    Files.writeString(directory.resolve("photos/blocked/keep.txt"), "target keep")
                }
                withSftpTargets(sync, listOf(target, target2), listingFailure = { index, path ->
                    if (index == 0 && path == "/photos/blocked") IllegalStateException("injected unreadable subtree") else null
                }) { destinations ->
                    val task = sync.createOrUpdateTask(destinations.first().applyTo(save(sync, photos, target)).withMode(mode).copy(
                        selectedSources = listOf(FileUtils.getFile(FileAccessPermission.Allowed, photos.toString()).getOrThrow()),
                        destinations = destinations, selectRecommended = false)).getOrThrow()
                    if (previewUpdate) {
                        val preview = sync.createSyncPreview(task.id).getOrThrow()
                        sync.confirmSyncPreview(preview.token).getOrThrow()
                        awaitFinishedRun(sync, task.id)
                    } else sync.runTaskInternal(task, "manual")
                    val result = sync.recordsFor(task.id).first()
                    assertEquals(SyncRunStatus.Success, result.status, "$mode: $result")
                    assertEquals("source keep", Files.readString(photos.resolve("blocked/keep.txt")))
                    for (directory in listOf(target, target2)) {
                        assertEquals("target keep", Files.readString(directory.resolve("photos/blocked/keep.txt")))
                        assertEquals("safe source", Files.readString(directory.resolve("photos/safe.txt")))
                    }
                }
            }
        }
    }

    @Test fun updateGroupKeepsASelectedRootFileThatArrivesAfterPlanning(): Unit = runBlocking {
        fixture { sync, source, target, _ ->
            val photos = Files.createDirectory(source.resolve("photos"))
            Files.writeString(photos.resolve("a.txt"), "source child")
            val second = Files.createDirectory(target.parent.resolve("second"))
            var changed = false
            withSftpTargets(sync, listOf(target, second), copyFailure = {
                if (!changed) {
                    changed = true
                    Files.delete(second.resolve("photos"))
                    Files.writeString(second.resolve("photos"), "arrived root file")
                }
                null
            }) { destinations ->
                val task = sync.createOrUpdateTask(destinations.first().applyTo(save(sync, photos, target)).copy(
                    selectedSources = listOf(FileUtils.getFile(FileAccessPermission.Allowed, photos.toString()).getOrThrow()),
                    destinations = destinations, selectRecommended = false)).getOrThrow()
                sync.runTaskInternal(task, "manual")
                val result = sync.recordsFor(task.id).first()
                assertTrue(changed)
                assertEquals(SyncRunStatus.PartialSuccess, result.status, result.toString())
                assertEquals("source child", Files.readString(target.resolve("photos/a.txt")))
                assertEquals("arrived root file", Files.readString(second.resolve("photos")))
                assertEquals("source child", Files.readString(photos.resolve("a.txt")))
            }
        }
    }

    @Test fun groupDeletionThresholdCountsBothSftpTargetsAndApprovesOnlyTheirFrozenPaths(): Unit = runBlocking {
        fixture { sync, source, target, _ ->
            val photos = Files.createDirectory(source.resolve("photos"))
            Files.writeString(photos.resolve("keep.txt"), "source keep")
            val second = Files.createDirectory(target.parent.resolve("second"))
            for (root in listOf(target, second)) {
                Files.createDirectory(root.resolve("photos"))
                Files.writeString(root.resolve("photos/old.txt"), "keep until approved")
                Files.writeString(root.resolve("unrelated.txt"), "outside selection")
            }
            withSftpTargets(sync, listOf(target, second)) { destinations ->
                val task = sync.createOrUpdateTask(destinations.first().applyTo(save(sync, photos, target)).withMode(SyncMode.Mirror).copy(
                    selectedSources = listOf(FileUtils.getFile(FileAccessPermission.Allowed, photos.toString()).getOrThrow()),
                    destinations = destinations, selectRecommended = false, deleteThreshold = 1)).getOrThrow()
                val store = sync.localStore!!
                val fingerprint = syncDefinitionFingerprint(task)
                store.confirmPreview(task.id, fingerprint)
                val operation = sync.createOperationTask(task)
                val prepared = sync.prepareSyncRun(task, operation).getOrThrow()
                val protected = protectSyncRunPlan(prepared)
                assertEquals(2, protected.pendingDeletions.size)
                assertEquals(setOf("target-0", "target-1"), protected.pendingDeletions.map { it.participant }.toSet())
                assertEquals(SyncRunStatus.Success, sync.executeSyncPreparedRun(prepared, operation, "manual").status)
                assertEquals(2L, store.pendingDeletionCount(task.id))
                for (root in listOf(target, second)) assertEquals("keep until approved", Files.readString(root.resolve("photos/old.txt")))
                val approved = store.pendingDeletionPage(task.id, fingerprint).toSet()
                val refreshed = sync.prepareSyncRun(task, operation).getOrThrow()
                assertEquals(SyncRunStatus.Success, sync.executeSyncPreparedRun(refreshed, operation, "confirm", approvedDeletions = approved).status)
                assertEquals(0L, store.pendingDeletionCount(task.id))
                for (root in listOf(target, second)) {
                    assertFalse(Files.exists(root.resolve("photos/old.txt")))
                    assertEquals("source keep", Files.readString(root.resolve("photos/keep.txt")))
                    assertEquals("outside selection", Files.readString(root.resolve("unrelated.txt")))
                }
                assertEquals("source keep", Files.readString(photos.resolve("keep.txt")))
            }
        }
    }

    @Test fun updateGroupRootTypeConflictsProtectEverySelectedVersionUnderAllPolicies(): Unit = runBlocking {
        for (policy in SyncConflictPolicy.entries) fixture { sync, source, target, _ ->
            val photos = Files.createDirectory(source.resolve("photos"))
            Files.writeString(photos.resolve("keep.txt"), "source directory version")
            val selectedFile = Files.writeString(source.resolve("current.txt"), "source file version")
            Files.writeString(target.resolve("photos"), "target file version")
            Files.createDirectory(target.resolve("current.txt"))
            Files.writeString(target.resolve("current.txt/keep"), "target directory version")
            val target2 = Files.createDirectory(target.parent.resolve("target2"))
            withSftpTargets(sync, listOf(target, target2)) { destinations ->
                val task = sync.createOrUpdateTask(destinations.first().applyTo(save(sync, photos, target)).copy(
                    selectedSources = listOf(photos, selectedFile).map { FileUtils.getFile(FileAccessPermission.Allowed, it.toString()).getOrThrow() },
                    destinations = destinations, selectRecommended = false, conflictPolicy = policy)).getOrThrow()
                sync.runTaskInternal(task, "manual")
                val result = sync.recordsFor(task.id).first()
                assertEquals(SyncRunStatus.Success, result.status, result.toString())
                assertTrue(result.targets.all { it.items.isNotEmpty() && it.items.all { item -> item.message == AppStrings.sync_selected_root_type_conflict } })
                assertTrue(result.sourceDigests.isEmpty())
                assertEquals("source directory version", Files.readString(photos.resolve("keep.txt")))
                assertEquals("source file version", Files.readString(selectedFile))
                assertEquals("target file version", Files.readString(target.resolve("photos")))
                assertEquals("target directory version", Files.readString(target.resolve("current.txt/keep")))
                Files.list(target2).use { assertEquals(0L, it.count()) }
            }
        }
    }

    @Test fun recommendedPreviewRejectsAReplacedSourceAccountBeforeStartingAnyCopy(): Unit = runBlocking {
        fixture { sync, source, target, _ ->
            Files.writeString(source.resolve("a.txt"), "unapproved source account")
            val second = Files.createDirectory(target.parent.resolve("second"))
            withSftpTargets(sync, listOf(source, target, second)) { endpoints ->
                val task = sync.createOrUpdateTask(endpoints[1].applyTo(save(sync, source, target)).withMode(SyncMode.Mirror).copy(
                    sourceType = SyncEndpointType.Network, sourceRef = endpoints[0].ref, sourcePath = "/",
                    destinations = endpoints.drop(1), selectRecommended = true)).getOrThrow()
                val preview = sync.createSyncPreview(task.id).getOrThrow()
                val entry = sync.networkState.entries[0]
                val network = entry.network
                sync.networkState.entries[0] = entry.copy(network = Network(network.name, network.pathSeparator, network.protocol,
                    network.host, network.username, network.password, extras = network.extras.copy(
                        cloud = network.extras.cloud.copy(accountId = "new-source-account"))))
                assertTrue(sync.confirmSyncPreview(preview.token).isFailure)
                assertFalse(sync.localStore!!.localState(task.id)!!.previewConfirmed)
                assertTrue(sync.recordsFor(task.id).isEmpty())
                assertEquals("unapproved source account", Files.readString(source.resolve("a.txt")))
                assertFalse(Files.exists(target.resolve("a.txt")))
                assertFalse(Files.exists(second.resolve("a.txt")))
            }
        }
    }

    @Test fun advancedRecommendedTargetStaysFixedWhileOfflineAndAccountChangesRequireConsent(): Unit = runBlocking {
        for (mode in listOf(SyncMode.Mirror, SyncMode.BackupThenClean, SyncMode.Bidirectional)) fixture { sync, source, target, _ ->
            Files.writeString(source.resolve("a.txt"), "first version")
            val second = Files.createDirectory(target.parent.resolve("second"))
            var offline = false
            withSftpTargets(sync, listOf(target, second), listingFailure = { index, _ ->
                if (index == 1 && offline) IllegalStateException("chosen target offline") else null
            }) { destinations ->
                val task = sync.createOrUpdateTask(destinations.first().applyTo(save(sync, source, target)).withMode(mode).copy(
                    destinations = destinations, selectRecommended = true, preferredEntryIds = listOf(destinations[1].networkEntryId!!))).getOrThrow()
                val store = sync.localStore!!
                val preview = sync.createSyncPreview(task.id).getOrThrow()
                assertEquals(setOf("source", "target-0"), preview.participants.keys)
                assertTrue(preview.participants.getValue("target-0").startsWith("target-1"))
                assertTrue(store.localState(task.id)!!.fixedTargets.isEmpty())
                sync.confirmSyncPreview(preview.token).getOrThrow()
                val firstRun = awaitFinishedRun(sync, task.id)
                assertEquals(mode, firstRun.policySnapshot?.mode)
                assertEquals(listOf("source", "target-0"), firstRun.participants.map { it.id })
                assertTrue(firstRun.participants.last().label.startsWith("target-1"))
                assertEquals(listOf(destinations[1]), store.localState(task.id)!!.fixedTargets)
                assertEquals("first version", Files.readString(second.resolve("a.txt")))
                assertFalse(Files.exists(target.resolve("a.txt")))
                Files.writeString(source.resolve("b.txt"), "next version")
                offline = true
                val current = sync.getTask(task.id)!!
                assertTrue(sync.prepareSyncRun(current, sync.createOperationTask(current)).isFailure)
                assertEquals(listOf(destinations[1]), store.localState(task.id)!!.fixedTargets)
                assertFalse(Files.exists(target.resolve("b.txt")))
                assertEquals("next version", Files.readString(source.resolve("b.txt")))
                offline = false
                sync.runTaskInternal(current, "manual")
                assertEquals(SyncRunStatus.Success, sync.recordsFor(task.id).first().status)
                assertEquals("next version", Files.readString(second.resolve("b.txt")))
                assertFalse(Files.exists(target.resolve("b.txt")))
                Files.writeString(source.resolve("c.txt"), "unapproved version")
                val fresh = sync.getTask(task.id)!!
                val prepared = sync.prepareSyncRun(fresh, sync.createOperationTask(fresh)).getOrThrow()
                val oldEntry = sync.networkState.entries[1]
                val network = oldEntry.network
                sync.networkState.entries[1] = oldEntry.copy(network = Network(network.name, network.pathSeparator, network.protocol,
                    network.host, network.username, network.password, extras = network.extras.copy(
                        cloud = network.extras.cloud.copy(accountId = "changed-account"))))
                assertFailsWith<IllegalStateException> { sync.executeSyncPreparedRun(prepared, sync.createOperationTask(fresh), "manual") }
                assertTrue(sync.requiresSyncPreview(fresh))
                assertTrue(sync.currentTaskAttention(fresh.id)!!.fixedTargets.isEmpty())
                sync.refreshFixedSyncTargets(fresh)
                assertTrue(sync.requiresSyncPreview(fresh))
                assertTrue(store.localState(task.id)!!.fixedTargets.isEmpty())
                assertTrue(store.baselinePage(task.id, syncDefinitionFingerprint(fresh)).isEmpty())
                assertEquals("unapproved version", Files.readString(source.resolve("c.txt")))
                assertFalse(Files.exists(second.resolve("c.txt")))
                assertFalse(Files.exists(target.resolve("c.txt")))
            }
        }
    }

    @Test fun groupPreparationUsesEverySavedTargetAndFreshSelectedFileState(): Unit = runBlocking {
        fixture { sync, source, target, _ ->
            val photos = Files.createDirectory(source.resolve("photos"))
            Files.writeString(photos.resolve("a.jpg"), "photo")
            Files.writeString(photos.resolve("private.txt"), "keep source private")
            Files.createDirectory(photos.resolve("nested"))
            Files.writeString(photos.resolve("nested/deep.jpg"), "deep")
            val current = Files.writeString(source.resolve("current.txt"), "old")
            val selected = listOf(photos, current).map { FileUtils.getFile(FileAccessPermission.Allowed, it.toString()).getOrThrow() }
            val target2 = Files.createDirectory(target.parent.resolve("target2"))
            Files.writeString(target.resolve("unrelated.txt"), "keep target private")
            withSftpTargets(sync, listOf(target, target2)) { destinations ->
                val ordinary = save(sync, photos, target)
                val task = sync.createOrUpdateTask(destinations.first().applyTo(ordinary).copy(mode = SyncMode.Mirror,
                    selectedSources = selected, destinations = destinations, includeRaw = "*.jpg;*.txt",
                    filterRaw = "private.txt", includeSubdirectories = false, selectRecommended = false)).getOrThrow()
                Files.writeString(current, "fresh source data")
                val prepared = sync.prepareSyncRun(task, sync.createOperationTask(task)).getOrThrow()
                assertEquals(listOf("source", "target-0", "target-1"), prepared.endpoints.map { it.id })
                assertEquals(setOf("photos", "photos/a.jpg", "current.txt"), prepared.preparation.participants.first().scan.entries.keys)
                assertEquals(17L, prepared.preparation.participants.first().state("current.txt")!!.size)
                assertTrue(prepared.preparation.plan.operations.all { it.participant != "source" && it.relativePath != "unrelated.txt" })
                assertEquals(setOf("target-0", "target-1"), prepared.preparation.plan.operations.map { it.participant }.toSet())
                assertEquals("keep target private", Files.readString(target.resolve("unrelated.txt")))
                assertFalse(Files.exists(target.resolve("current.txt")))
                assertFalse(Files.exists(target2.resolve("photos")))
            }
        }
    }

    @Test fun unreadableBaselineAndMissingRootAbortPreparationWithoutChanges(): Unit = runBlocking {
        fixture { sync, source, target, _ ->
            val path = Files.writeString(source.resolve("a.txt"), "source")
            val ordinary = save(sync, source, target)
            val task = sync.createOrUpdateTask(ordinary.copy(mode = SyncMode.Bidirectional)).getOrThrow()
            val database = org.koin.mp.KoinPlatform.getKoin().get<FolderSpanDatabase>()
            database.syncBaselineEntryQueries.upsert(task.id, "a.txt", syncDefinitionFingerprint(task), "{broken").await()
            assertTrue(sync.prepareSyncRun(task, sync.createOperationTask(task)).isFailure)
            assertEquals("source", Files.readString(path))
            assertFalse(Files.exists(target.resolve("a.txt")))
            database.syncBaselineEntryQueries.deleteTask(task.id).await()
            Files.delete(target)
            assertTrue(sync.prepareSyncRun(task, sync.createOperationTask(task)).isFailure)
            assertEquals("source", Files.readString(path))
            assertFalse(Files.exists(target))
        }
    }

    private class MemoryIds(config: NetworkConnectionConfig, api: StorageHttpApi) : IdStorageProvider(config, api) {
        override val rootId = "R"
        val nodes = linkedMapOf("R" to CloudStorageNode("R", "/", true),
            "P" to CloudStorageNode("P", "parent", true, parents = listOf("R")),
            "old" to CloudStorageNode("old", "a.txt", false, 10, 10_000, listOf("P")))
        val contents = mutableMapOf("old" to "old source".encodeToByteArray())
        private var next = 0
        override suspend fun node(id: String) = nodes[id] ?: throw StorageEntryNotFoundException()
        override suspend fun children(directory: CloudStorageNode) = nodes.values.filter { directory.id in it.parents }
        override suspend fun read(node: CloudStorageNode, size: Long, onChunk: suspend (ByteArray, Long) -> Result<Unit>) {
            val bytes = contents.getValue(node.id)
            check(bytes.size.toLong() == size)
            onChunk(bytes.copyOf(), size).getOrThrow()
        }
        override suspend fun write(parent: CloudStorageNode, name: String, existing: CloudStorageNode?, size: Long,
            progress: (Long, Long) -> Unit, source: suspend () -> ByteArray?) {
            val bytes = ByteArrayOutputStream()
            while (true) {
                val chunk = source() ?: break
                bytes.write(chunk)
                progress(bytes.size().toLong(), size)
            }
            check(bytes.size().toLong() == size)
            val id = existing?.id ?: "created-${++next}"
            nodes[id] = CloudStorageNode(id, name, false, size, 100_000L + next, listOf(parent.id))
            contents[id] = bytes.toByteArray()
        }
        override suspend fun mkdir(parent: CloudStorageNode, name: String) {
            val id = "folder-${++next}"
            nodes[id] = CloudStorageNode(id, name, true, parents = listOf(parent.id))
        }
        override suspend fun remove(node: CloudStorageNode) {
            check(nodes.values.none { node.id in it.parents })
            nodes.remove(node.id)
            contents.remove(node.id)
        }
        override suspend fun move(node: CloudStorageNode, parent: CloudStorageNode, name: String) {
            nodes[node.id] = node.copy(name = name, parents = listOf(parent.id))
        }
    }

    private suspend fun withSftpTargets(sync: SyncState, directories: List<Path>, copyFailure: suspend (FileSimpleInfo) -> Exception? = { null },
        listingFailure: (Int, String) -> Exception? = { _, _ -> null },
        block: suspend (List<StorageDestination>) -> Unit) {
        val key = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
        val servers = directories.map { directory -> SshServer.setUpDefaultServer().apply {
            host = "127.0.0.1"; port = 0
            keyPairProvider = KeyPairProvider.wrap(key)
            passwordAuthenticator = PasswordAuthenticator { _, _, _ -> true }
            subsystemFactories = listOf(SftpSubsystemFactory.Builder().build())
            fileSystemFactory = VirtualFileSystemFactory(directory)
        } }
        try {
            servers.forEach { it.start() }
            block(servers.mapIndexed { index, server ->
                val network = object : Network("target-$index", "/", "SFTP", "127.0.0.1:${server.port}", "test", "test",
                    extras = NetworkDriveExtras(sftp = SftpDriveExtras(knownHosts = "[127.0.0.1]:${server.port} ${PublicKeyEntry.toString(key.public)}"))) {
                    override suspend fun getList(path: String, requestId: String?, batchId: String?): Result<List<FileSimpleInfo>> =
                        listingFailure(index, path)?.let { Result.failure(it) } ?: super.getList(path, requestId, batchId)
                    override suspend fun getList(directory: FileSimpleInfo, requestId: String?, batchId: String?): Result<List<FileSimpleInfo>> =
                        listingFailure(index, directory.path)?.let { Result.failure(it) } ?: super.getList(directory, requestId, batchId)
                    override suspend fun copyTo(task: Task, srcFileSimpleInfo: FileSimpleInfo, destFileSimpleInfo: FileSimpleInfo): Result<Boolean> =
                        copyFailure(srcFileSimpleInfo)?.let { Result.failure(it) } ?: super.copyTo(task, srcFileSimpleInfo, destFileSimpleInfo)
                }
                NetworkEntry(index + 1L, network, true).also { sync.networkState.entries.add(it) }.storageDestination("/")
            })
        } finally { servers.forEach { it.stop(true) } }
    }

    private class Store : SyncTaskStore {
        private val tasks = linkedMapOf<Long, SyncTask>()
        private val runs = linkedMapOf<Long, MutableList<SyncRunRecord>>()
        private val failures = mutableMapOf<Pair<Long, Long>, List<SyncFailureItem>>()
        override fun loadTasks() = tasks.values.toList()
        override fun saveTask(task: SyncTask) = task.copy(id = task.id.takeIf { it > 0 } ?: (tasks.size + 1L)).also { tasks[it.id] = it }
        override fun deleteTask(taskId: Long) { tasks.remove(taskId) }
        override fun appendRun(record: SyncRunRecord) { runs.getOrPut(record.taskId) { mutableListOf() }.add(0, record) }
        override fun loadRuns(taskId: Long) = runs[taskId].orEmpty()
        override fun deleteRuns(taskId: Long) { runs.remove(taskId) }
        override fun saveFailedItems(taskId: Long, runId: Long, items: List<SyncFailureItem>): Int {
            failures[taskId to runId] = items.take(10_000)
            return (items.size - 10_000).coerceAtLeast(0)
        }
        override fun loadFailedItems(taskId: Long, runId: Long) = failures[taskId to runId]
    }

    private suspend fun fixture(onBaselineWrite: (() -> Unit)? = null, onRecoveryWrite: (() -> Unit)? = null,
        platform: MutableStateFlow<SyncPlatformConditions>? = null,
        block: suspend (SyncState, Path, Path, Store) -> Unit) {
        val root = Files.createTempDirectory("sync-execution")
        val source = Files.createDirectory(root.resolve("source"))
        val target = Files.createDirectory(root.resolve("target"))
        val rawDriver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        val driver = object : SqlDriver by rawDriver {
            override fun execute(identifier: Int?, sql: String, parameters: Int, binders: (SqlPreparedStatement.() -> Unit)?): QueryResult<Long> {
                return rawDriver.execute(identifier, sql, parameters, binders).also {
                    if (sql.startsWith("INSERT OR REPLACE INTO SyncBaselineEntry")) onBaselineWrite?.invoke()
                    if (sql.startsWith("INSERT OR REPLACE INTO SyncFileRecovery")) onRecoveryWrite?.invoke()
                }
            }
        }
        val database = createMcpHttpTestDatabase(driver)
        val taskState = TaskState(TestTaskFailureResultStore(), TestTaskRuntimePersistenceStore(), TempTaskCleanupStore(root.resolve("cleanup").toString()))
        val store = Store()
        val sync = SyncState(store) { SyncDeviceIdentity("fixture", "Fixture") }
        val platformScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val monitor = platform?.let { SyncConditionsMonitor(platformScope, true, it) }
        stopKoin()
        startKoin { modules(module {
            single { database }
            if (monitor != null) single { monitor }
            single { SyncLocalStore(database, driver) }
            single { NetworkState(database) }
            single { taskState }
            single { DeviceState() }
            single { MainState() }
            single { FileRecentState() }
            single { FileState().also { it.mainScope.cancel() } }
        }) }
        try { block(sync, source, target, store) }
        finally {
            sync.stop()
            platformScope.coroutineContext[Job]!!.cancelAndJoin()
            taskState.stop()
            stopKoin()
            driver.close()
            root.toFile().deleteRecursively()
        }
    }

    private suspend fun save(sync: SyncState, source: Path, target: Path, ignore: Boolean = false): SyncTask =
        sync.createOrUpdateTask(SyncTask(0, "test", sourceType = SyncEndpointType.Local, sourcePath = source.toString(),
            targetType = SyncEndpointType.Local, targetPath = target.toString(), useIgnoreFiles = ignore,
            ignoreFileNames = if (ignore) listOf(".gitignore") else emptyList(), parallelism = 3)).getOrThrow()

    @Test fun advancedModeSaveRejectsSingleFilesAndReconcilesLocalDefinition() = runBlocking {
        fixture { sync, source, target, _ ->
            val original = save(sync, source, target)
            val file = Files.writeString(source.resolve("file.txt"), "data")
            assertTrue(sync.createOrUpdateTask(original.copy(mode = SyncMode.Mirror, sourcePath = file.toString())).isFailure)
            val targetFile = Files.writeString(target.resolve("file.txt"), "data")
            assertTrue(sync.createOrUpdateTask(original.copy(mode = SyncMode.Mirror, targetPath = targetFile.toString())).isFailure)
            val mirror = sync.createOrUpdateTask(original.copy(mode = SyncMode.Mirror)).getOrThrow()
            val local = org.koin.mp.KoinPlatform.getKoin().get<SyncLocalStore>()
            val fingerprint = syncDefinitionFingerprint(mirror)
            local.confirmPreview(mirror.id, fingerprint)
            local.commitVerified(mirror.id, fingerprint, emptyList())
            sync.reloadTasksFromStore()
            assertTrue(local.localState(mirror.id)!!.previewConfirmed)
            sync.createOrUpdateTask(mirror.copy(mode = SyncMode.Bidirectional)).getOrThrow()
            assertTrue(!local.localState(mirror.id)!!.previewConfirmed)
            assertTrue(!local.localState(mirror.id)!!.baselineEstablished)
        }
    }

    @Test fun advancedModeCannotWriteBeforePreviewConfirmation() = runBlocking {
        fixture { sync, source, target, _ ->
            Files.writeString(source.resolve("a"), "new source content")
            Files.writeString(target.resolve("a"), "keep target")
            val ordinary = save(sync, source, target)
            val mirror = sync.createOrUpdateTask(ordinary.copy(mode = SyncMode.Mirror)).getOrThrow()
            sync.runTaskInternal(mirror, "schedule")
            assertTrue(sync.localStore!!.localState(mirror.id)!!.confirmationNotified)
            assertTrue(sync.requiresSyncPreview(mirror))
            assertTrue(sync.recordsFor(mirror.id).isEmpty())
            sync.runTaskInternal(ordinary, "manual")
            assertEquals("keep target", Files.readString(target.resolve("a")))
            assertEquals("new source content", Files.readString(source.resolve("a")))
            assertEquals(SyncRunStatus.Idle, sync.getTask(mirror.id)!!.lastStatus)
        }
    }

    @Test
    fun includedFilesCanEnterTargetDirectoriesWithNoMatchingFiles() = runBlocking {
        fixture { sync, source, target, _ ->
            Files.createDirectory(source.resolve("photos"))
            Files.createDirectory(target.resolve("photos"))
            Files.writeString(source.resolve("photos/new.jpg"), "photo")
            Files.writeString(source.resolve("photos/private.txt"), "source private")
            Files.writeString(target.resolve("photos/private.txt"), "target private")
            val original = save(sync, source, target)
            val task = sync.createOrUpdateTask(original.copy(includeRaw = "*.jpg")).getOrThrow()
            sync.runTaskInternal(task, "manual")
            assertEquals("photo", Files.readString(target.resolve("photos/new.jpg")))
            assertEquals("target private", Files.readString(target.resolve("photos/private.txt")))
            assertEquals("source private", Files.readString(source.resolve("photos/private.txt")))
        }
    }

    @Test
    fun localCopiesPreserveTimeAndReverseRunHasNoChanges() = runBlocking {
        fixture { sync, source, target, _ ->
            Files.writeString(source.resolve("a.txt"), "original")
            Files.setLastModifiedTime(source.resolve("a.txt"), FileTime.fromMillis(1_700_000_000_000))
            val task = save(sync, source, target)
            sync.runTaskInternal(task, "manual")
            assertEquals(1, sync.recordsFor(task.id).first().copiedCount)
            assertEquals(1_700_000_000_000, Files.getLastModifiedTime(target.resolve("a.txt")).toMillis())
            val reverse = sync.createOrUpdateTask(task.copy(id = 0, sourcePath = target.toString(), targetPath = source.toString()),
                confirmReverseTasks = true).getOrThrow()
            sync.runTaskInternal(reverse, "manual")
            assertEquals(1, sync.recordsFor(reverse.id).first().unchangedCount)
            sync.runTaskInternal(task, "manual")
            assertEquals(0, sync.recordsFor(task.id).first().copiedCount)
            assertTrue(sync.recordsFor(task.id).first().items.isEmpty())
        }
    }

    @Test
    fun ignoreReloadsEachRunAndMissingRulesCauseNoWrites() = runBlocking {
        fixture { sync, source, target, _ ->
            Files.createDirectory(source.resolve("excluded"))
            Files.writeString(source.resolve("excluded/private.txt"), "private")
            Files.writeString(source.resolve("a.txt"), "a")
            Files.writeString(source.resolve("b.txt"), "b")
            Files.writeString(source.resolve(".gitignore"), "excluded/\nb.txt\n")
            val task = save(sync, source, target, ignore = true)
            sync.runTaskInternal(task, "manual")
            assertTrue(Files.exists(target.resolve("a.txt")))
            assertFalse(Files.exists(target.resolve("b.txt")))
            assertFalse(Files.exists(target.resolve("excluded")))
            assertFalse(Files.exists(target.resolve(".gitignore")))
            Files.writeString(source.resolve(".gitignore"), "excluded/\n")
            sync.runTaskInternal(task, "manual")
            assertTrue(Files.exists(target.resolve("b.txt")))
            Files.delete(source.resolve(".gitignore"))
            Files.writeString(source.resolve("c.txt"), "c")
            sync.runTaskInternal(task, "manual")
            assertEquals(SyncRunStatus.Failure, sync.recordsFor(task.id).first().status)
            assertFalse(Files.exists(target.resolve("c.txt")))
        }
    }

    @Test
    fun unchangedFilesDoNotDisplaceTheThreeActualChanges() = runBlocking {
        fixture { sync, source, target, _ ->
            repeat(5_000) { index ->
                Files.writeString(source.resolve("$index.txt"), "x")
                if (index < 4_997) Files.writeString(target.resolve("$index.txt"), "x")
            }
            val task = save(sync, source, target)
            sync.runTaskInternal(task, "manual")
            val record = sync.recordsFor(task.id).first()
            assertEquals(3, record.copiedCount)
            assertEquals(4_997, record.unchangedCount)
            assertEquals(3, record.items.size)
            assertEquals(3L, record.transferredBytes)
        }
    }

    @Test
    fun retrySkipsMissingFilesWithoutCopyingPreviouslySuccessfulFiles() = runBlocking {
        fixture { sync, source, target, _ ->
            Files.writeString(source.resolve("already-done.txt"), "do not copy")
            val task = save(sync, source, target)
            sync.runTaskInternal(task, "retry", retryFailures = listOf(SyncFailureItem("gone.txt", false)), retryOfRunId = 123)
            val record = sync.recordsFor(task.id).first()
            assertEquals(0, record.copiedCount)
            assertEquals(1, record.skippedCount)
            assertEquals(123L, record.retryOfRunId)
            assertTrue(record.items.single().success)
            assertEquals("gone.txt", record.items.single().path)
            assertFalse(Files.exists(target.resolve("already-done.txt")))
        }
    }

    @Test
    fun retrySkipsDeletedSingleFileSourceWithoutCreatingATarget() = runBlocking {
        fixture { sync, source, target, _ ->
            val file = Files.writeString(source.resolve("gone.txt"), "content")
            val task = save(sync, file, target)
            Files.delete(file)
            sync.runTaskInternal(task, "retry", retryFailures = listOf(SyncFailureItem("gone.txt", false)), retryOfRunId = 123)
            val record = sync.recordsFor(task.id).first()
            assertEquals(SyncRunStatus.Success, record.status)
            assertEquals(1, record.skippedCount)
            assertEquals(0, record.failureCount)
            assertEquals(123L, record.retryOfRunId)
            assertTrue(record.items.single().success)
            assertFalse(Files.exists(target.resolve("gone.txt")))
        }
    }

    @Test
    fun foreignTasksRequireExplicitTakeoverAndExistingSource() = runBlocking {
        fixture { sync, source, target, store ->
            val task = save(sync, source, target)
            assertEquals("fixture", task.ownerDeviceId)
            val foreign = task.copy(ownerDeviceId = "other", ownerDeviceName = "Other")
            store.saveTask(foreign)
            sync.tasks[0] = foreign
            assertEquals(null, sync.runNowIfIdle(task.id))
            assertFalse(sync.updateEnabled(task.id, false))
            assertTrue(sync.createOrUpdateTask(foreign).isFailure)
            assertTrue(sync.createOrUpdateTask(foreign.copy(sourcePath = source.resolve("missing").toString()), takeOwnership = true).isFailure)
            val taken = sync.createOrUpdateTask(foreign, takeOwnership = true).getOrThrow()
            assertEquals("fixture", taken.ownerDeviceId)
        }
    }

    @Test
    fun completedOldRunCannotResurrectRemovedOrTransferredTasks() = runBlocking {
        fixture { sync, source, target, store ->
            val task = save(sync, source, target)
            val record = SyncRunRecord(10, task.id, "manual", 1, 2, SyncRunStatus.Failure, 1, 0, 1)
            store.deleteTask(task.id)
            sync.onTaskFinished(task, record, listOf(SyncFailureItem("old.txt", false)))
            assertTrue(store.loadTasks().isEmpty())
            assertTrue(store.loadRuns(task.id).isEmpty())
            assertEquals(null, store.loadFailedItems(task.id, record.runId))
            store.saveTask(task.copy(ownerDeviceId = "other"))
            sync.onTaskFinished(task, record, listOf(SyncFailureItem("old.txt", false)))
            assertEquals("other", store.loadTasks().single().ownerDeviceId)
            assertTrue(store.loadRuns(task.id).isEmpty())
            assertEquals(null, store.loadFailedItems(task.id, record.runId))
        }
    }

    @Test
    fun cancellationWhileQueuedPersistsCancelledRun() = runBlocking {
        fixture { sync, source, target, store ->
            val task = save(sync, source, target)
            sync.tasks[0] = task.copy(lastStatus = SyncRunStatus.Queued)
            sync.cancelActiveRun(task.id)
            withTimeout(2_000) { while (sync.recordsFor(task.id).isEmpty()) delay(5) }
            assertEquals(SyncRunStatus.Canceled, sync.recordsFor(task.id).first().status)
            assertEquals(SyncRunStatus.Canceled, store.loadRuns(task.id).first().status)
        }
    }

    @Test
    fun delayedTaskPatchPreservesFreshDefinitionAndCannotResurrectTask() {
        val store = Store()
        val original = store.saveTask(SyncTask(0, "original", sourceType = SyncEndpointType.Local,
            sourcePath = "/a", targetType = SyncEndpointType.Local, targetPath = "/b", ownerDeviceId = "device"))
        store.saveTask(original.copy(name = "new snapshot", sourcePath = "/fresh"))
        store.updateOwnedSyncTask(original) { it.copy(enabled = false) }
        val changed = store.loadTasks().single()
        assertEquals("new snapshot", changed.name)
        assertEquals("/fresh", changed.sourcePath)
        assertFalse(changed.enabled)
        store.saveTask(changed.copy(ownerDeviceId = "other"))
        assertEquals(null, store.updateOwnedSyncTask(original) { it.copy(lastStatus = SyncRunStatus.Canceled) })
        assertEquals(SyncRunStatus.Idle, store.loadTasks().single().lastStatus)
        store.deleteTask(original.id)
        assertEquals(null, store.updateOwnedSyncTask(original) { it.copy(enabled = false) })
        assertTrue(store.loadTasks().isEmpty())
    }
}
