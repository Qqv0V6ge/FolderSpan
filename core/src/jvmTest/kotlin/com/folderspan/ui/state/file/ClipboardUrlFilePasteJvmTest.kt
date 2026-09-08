package com.folderspan.ui.state.file

import com.folderspan.data.file.FileProtocol
import com.folderspan.data.file.FileSimpleInfo
import com.folderspan.data.main.Local
import com.folderspan.service.http.clipboard.ClipboardUrlDownloadEngine
import com.folderspan.service.http.clipboard.ClipboardUrlDownloadState
import com.folderspan.service.http.clipboard.ClipboardUrlShareInspectionResult
import com.folderspan.service.http.clipboard.DefaultClipboardUrlShareInspector
import com.folderspan.service.http.clipboard.OkioClipboardDownloadStagingFactory
import com.folderspan.ui.state.main.TaskState
import com.folderspan.ui.state.main.TaskType
import com.folderspan.ui.state.main.TestTaskRuntimePersistenceStore
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.utils.io.ByteReadChannel
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okio.FileSystem
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class ClipboardUrlFilePasteJvmTest {
    @Test
    fun inspectedUrlCanBePastedToTheSelectedDirectoryAfterConfirmation(): Unit = runBlocking {
        val directory = Files.createTempDirectory("folderspan-url-paste-test-")
        val content = "文件内容".encodeToByteArray()
        val ranges = mutableListOf<String?>()
        val tasks = TaskState(runtimeStore = TestTaskRuntimePersistenceStore())
        val clientProvider = {
            HttpClient(MockEngine { request ->
                val range = request.headers[HttpHeaders.Range]
                ranges += range
                val probe = range == "bytes=0-0"
                respond(
                    content = ByteReadChannel(if (probe) content.copyOfRange(0, 1) else content),
                    status = if (probe) HttpStatusCode.PartialContent else HttpStatusCode.OK,
                    headers = headersOf(
                        HttpHeaders.ContentLength to listOf(if (probe) "1" else content.size.toString()),
                        HttpHeaders.ContentType to listOf("application/octet-stream"),
                        HttpHeaders.ContentDisposition to listOf("attachment; filename=fixture.bin"),
                        HttpHeaders.ContentRange to listOf("bytes 0-0/${content.size}"),
                    ),
                )
            }) { followRedirects = false }
        }
        val coordinator = ClipboardUrlDownloadCoordinator(
            engine = ClipboardUrlDownloadEngine(
                clientProvider = clientProvider,
                stagingFactory = OkioClipboardDownloadStagingFactory(FileSystem.SYSTEM) { directory.toString() },
            ),
            taskState = tasks,
            resultPresenter = ClipboardUrlDownloadResultPresenter { error("文件粘贴不应进入发送分享页面") },
            uiDispatcher = Dispatchers.Unconfined,
        )
        var stagedPath: Path? = null
        var refreshes = 0
        val executor = FilePasteTaskExecutor(
            taskState = tasks,
            webRtcBrowserZipDownloader = FileStateWebRtcBrowserZipDownloader(
                taskState = tasks,
                deviceForProtocolId = { null },
                collectDirectoryEntries = { _, _, _ -> emptyList() },
                ensureTaskRunning = {},
                finishSuccessfulTask = {},
            ),
            pasteOperationPlanner = FilePasteOperationPlanner { _, _ -> "/" },
            getFileAndFolder = { Result.success(emptyList()) },
            getFileAndFolderForDesk = { _, _ -> Result.success(emptyList()) },
            getFile = { Result.failure(UnsupportedOperationException()) },
            executeCopyTask = { task, source, destination ->
                assertEquals(TaskType.Download, task.taskType)
                assertEquals(FileProtocol.Local, source.protocol)
                stagedPath = Path.of(source.path)
                Files.copy(Path.of(source.path), Path.of(destination.path))
                Result.success(true)
            },
            executeMoveTask = { _, _, _ -> error("不应移动 URL") },
            finishSuccessfulTask = {},
            updateFileAndFolder = { refreshes++ },
            currentDesk = { Local() },
            resolveDevice = { _, _ -> null },
            resolveNetworkAccess = { _, _ -> null },
            downloadUrlForCopy = coordinator::downloadForCopy,
        )
        try {
            val url = "http://example.test/fixture.bin?token=private"
            val inspection = assertIs<ClipboardUrlShareInspectionResult.Success>(
                DefaultClipboardUrlShareInspector(clientProvider).inspect(url)
            )
            ClipboardUrlShareFiles.add(inspection.file, url, inspection.release)
            val source = readSystemShareFiles { Result.success(emptyList()) }.getOrThrow().single()
            assertEquals(content.size.toLong(), source.size)
            assertEquals(listOf<String?>("bytes=0-0"), ranges)
            assertTrue(tasks.tasks.isEmpty())

            val target = directory.resolve("fixture.bin")
            val paste = async(start = CoroutineStart.UNDISPATCHED) {
                executor.pasteCopyFile(
                    FileSimpleInfo.pathFileSimpleInfo(directory.toString()),
                    listOf(source),
                    FileOperationState(),
                )
            }
            val draft = assertIs<ClipboardUrlDownloadState.Configuring>(coordinator.state.value).draft
            assertEquals(listOf<String?>("bytes=0-0"), ranges)
            assertTrue(tasks.tasks.isEmpty())
            assertFalse(Files.exists(target))

            coordinator.confirm(draft)
            withTimeout(10_000L) { paste.await() }

            assertEquals(listOf("bytes=0-0", null), ranges)
            assertEquals("文件内容", Files.readString(target))
            assertTrue(tasks.tasks.isEmpty())
            assertEquals(1, refreshes)
            assertFalse(Files.exists(requireNotNull(stagedPath)))
        } finally {
            coordinator.teardown()
            directory.toFile().deleteRecursively()
        }
    }
}
