package com.folderspan.ui.state.file

import com.folderspan.data.StatusEnum
import com.folderspan.data.file.FileProtocol
import com.folderspan.data.file.FileSimpleInfo
import com.folderspan.data.main.Local
import com.folderspan.data.main.network.Network
import com.folderspan.db.FolderSpanDatabase
import com.folderspan.ui.state.main.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import org.koin.dsl.module
import java.lang.reflect.Proxy
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class FileStateCopyCancellationTest {
    @Test
    fun cancellingOneCopyAttemptDoesNotFailTheSharedTask() = verifyCancellation(userCancel = false)

    @Test
    fun explicitTaskCancellationStillRecordsFailure() = verifyCancellation(userCancel = true)

    private fun verifyCancellation(userCancel: Boolean) = runBlocking {
        for (acrossEndpoints in listOf(false, true)) {
            val root = Files.createTempDirectory("copy-cancellation")
            val state = TaskState(TestTaskFailureResultStore(), TestTaskRuntimePersistenceStore(), TempTaskCleanupStore(root.resolve("cleanup").toString()))
            val database = Proxy.newProxyInstance(
                FolderSpanDatabase::class.java.classLoader, arrayOf(FolderSpanDatabase::class.java),
            ) { _, _, _ -> error("Cancellation must not read the database") } as FolderSpanDatabase
            stopKoin()
            startKoin { modules(module {
                single { database }
                single { state }
                single { MainState() }
            }) }
            val entered = CompletableDeferred<Unit>()
            val network = object : Network("Fake", "/", "SFTP", "example.invalid", "", "") {
                override suspend fun copyTo(task: Task, srcFileSimpleInfo: FileSimpleInfo, destFileSimpleInfo: FileSimpleInfo): Result<Boolean> {
                    entered.complete(Unit)
                    awaitCancellation()
                }
            }
            val coordinator = FileStateCopyCoordinator(state, DeviceState(), NetworkState(database), { network }, FileRecentState())
            val task = Task(taskType = TaskType.Copy, status = StatusEnum.LOADING)
            state.registerSessionTask(task, onCancel = {}, onRetry = {})
            val source = FileSimpleInfo.nullFileSimpleInfo().copy(
                name = "input.txt", path = "/input.txt", protocol = FileProtocol.Network, protocolId = network.protocolId,
            )
            val destination = source.copy(path = root.resolve("output.txt").toString(), protocol = FileProtocol.Local, protocolId = "")
            var thrown = false
            var result: Result<Boolean>? = null
            try {
                val attempt = launch {
                    try {
                        result = if (acrossEndpoints) coordinator.runCopyAcrossEndpoints(task, source, destination, network, Local())
                            else coordinator.copyTo(task, source, destination)
                    } catch (cancelled: CancellationException) {
                        thrown = true
                        throw cancelled
                    }
                }
                withTimeout(5_000) { entered.await() }
                if (userCancel) state.requestCancel(task)
                attempt.cancelAndJoin()
                if (userCancel) {
                    assertFalse(thrown)
                    assertIs<CancellationException>(result?.exceptionOrNull())
                    assertEquals(StatusEnum.FAILURE, state.getTask(task.key)?.status)
                } else {
                    assertTrue(thrown)
                    assertEquals(null, result)
                    assertEquals(StatusEnum.LOADING, state.getTask(task.key)?.status)
                    assertTrue(state.getTask(task.key)?.result.isNullOrEmpty())
                }
                assertFalse(Files.exists(root.resolve("output.txt")))
            } finally {
                state.stop()
                stopKoin()
                root.toFile().deleteRecursively()
            }
        }
    }
}
