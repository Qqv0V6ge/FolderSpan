package com.folderspan.ui.thumbnail

import androidx.compose.ui.graphics.ImageBitmap
import com.folderspan.data.file.FileProtocol
import com.folderspan.data.file.FileSimpleInfo
import com.folderspan.editor.FileEditorContentSource
import com.folderspan.editor.FileEditorSourceCapabilities
import com.folderspan.editor.FileEditorSourceSnapshot
import com.folderspan.utils.FileAccessPermission
import com.folderspan.utils.FileUtils
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertNotNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class FileThumbnailLoaderTest {
    @Test
    fun sharesOneDecodeAcrossConcurrentSubscribers() = runTest {
        var decodeCount = 0
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val loader = testLoader { _, _ ->
            decodeCount += 1
            started.complete(Unit)
            release.await()
            ImageBitmap(32, 24)
        }

        val results = coroutineScope {
            val first = async { loader.load(imageFile(), 40) }
            val second = async { loader.load(imageFile(), 40) }
            started.await()
            yield()
            release.complete(Unit)
            first.await() to second.await()
        }

        assertEquals(1, decodeCount)
        assertSame(results.first, results.second)
        loader.close()
    }

    @Test
    fun cancelsDecodeWhenLastSubscriberLeaves() = runTest {
        val started = CompletableDeferred<Unit>()
        val cancelled = CompletableDeferred<Unit>()
        val loader = testLoader { _, _ ->
            started.complete(Unit)
            try {
                awaitCancellation()
            } finally {
                cancelled.complete(Unit)
            }
        }

        val request = launch { loader.load(imageFile(), 40) }
        started.await()
        request.cancelAndJoin()
        cancelled.await()
        loader.close()
    }

    @Test
    fun decodeFailureFallsBackToNull() = runTest {
        val loader = testLoader { _, _ -> error("broken image") }

        assertNull(loader.load(imageFile(), 40))
        loader.close()
    }

    @Test
    fun acceptsAllFourProtocolIdentities() = runTest {
        var decodeCount = 0
        val loader = testLoader { _, _ ->
            decodeCount += 1
            ImageBitmap(16, 16)
        }

        FileProtocol.entries.forEach { protocol ->
            assertNotNull(loader.load(imageFile(protocol), 40))
        }

        assertEquals(4, decodeCount)
        loader.close()
    }

    @Test
    fun removesNativeStagingFileAfterDecode() = runTest {
        var stagedPath: String? = null
        val loader = testLoader { source, _ ->
            stagedPath = source.localPath
            assertTrue(FileUtils.getFile(FileAccessPermission.Allowed, source.localPath!!).isSuccess)
            ImageBitmap(16, 16)
        }

        assertNotNull(loader.load(imageFile(FileProtocol.Device), 40))
        assertTrue(FileUtils.getFile(FileAccessPermission.Allowed, stagedPath!!).isFailure)
        loader.close()
    }
}

private fun testLoader(
    decoder: suspend (FileThumbnailDecodeSource, Int) -> ImageBitmap,
): DefaultFileThumbnailLoader = DefaultFileThumbnailLoader(
    openContent = { Result.success(ByteArrayContentSource(byteArrayOf(1, 2, 3))) },
    decoder = decoder,
    remoteDebounceMillis = 0,
)

private class ByteArrayContentSource(
    private val data: ByteArray,
) : FileEditorContentSource {
    override val size: Long = data.size.toLong()
    override val canWrite: Boolean = false
    override val capabilities = FileEditorSourceCapabilities()

    override suspend fun currentSnapshot(): Result<FileEditorSourceSnapshot> =
        Result.success(FileEditorSourceSnapshot(size))

    override suspend fun readRange(startOffset: Long, endOffsetExclusive: Long): Result<ByteArray> =
        Result.success(data.copyOfRange(startOffset.toInt(), endOffsetExclusive.toInt()))

    override suspend fun replaceContent(
        newSize: Long,
        content: Flow<ByteArray>,
        expectedSnapshot: FileEditorSourceSnapshot?,
        onProgress: suspend (writtenBytes: Long, totalBytes: Long) -> Unit,
    ): Result<Unit> = Result.failure(UnsupportedOperationException())
}

private fun imageFile(protocol: FileProtocol = FileProtocol.Local): FileSimpleInfo = FileSimpleInfo(
    name = "photo.jpg",
    isDirectory = false,
    isHidden = false,
    path = "/photo.jpg",
    mineType = "image/jpeg",
    size = 3,
    createdDate = 1,
    updatedDate = 2,
    protocol = protocol,
    protocolId = protocol.name,
)
