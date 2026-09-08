package com.folderspan.ui.state.file

import strings.AppStrings

import com.folderspan.data.file.FileProtocol
import com.folderspan.data.file.FileSimpleInfo
import com.folderspan.editor.FileEditorSourceChangedException
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import java.io.RandomAccessFile
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.absolutePathString
import kotlin.io.path.readBytes
import kotlin.io.path.writeBytes
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class FileEditorContentAccessJvmTest {
    @Test
    fun localSourceReportsCapabilitiesAndSupportsVersionCheckedRangeWrite() = withTempFile(
        initialContent = "abcdef".encodeToByteArray(),
    ) { path ->
        runBlocking {
            val source = createFileEditorContentSource(
                file = localFile(path, size = 6L),
                requestedCanWrite = true,
            ).getOrThrow()
            val snapshot = source.currentSnapshot().getOrThrow()
            val progress = mutableListOf<Pair<Long, Long>>()

            val result = source.writeRange(
                startOffset = 2L,
                data = "XY".encodeToByteArray(),
                expectedSnapshot = snapshot,
            ) { completed, total -> progress += completed to total }

            assertTrue(source.capabilities.supportsRangeRead)
            assertTrue(source.capabilities.supportsRangeWrite)
            assertTrue(source.capabilities.supportsStreamedReplace)
            assertTrue(source.capabilities.supportsAtomicReplace)
            assertTrue(source.capabilities.supportsSaveAs)
            assertTrue(result.isSuccess)
            assertContentEquals("abXYef".encodeToByteArray(), path.readBytes())
            assertEquals(listOf(2L to 2L), progress)
            source.close()
        }
    }

    @Test
    fun localSourceRejectsStaleSnapshotBeforeWriting() = withTempFile(
        initialContent = "before".encodeToByteArray(),
    ) { path ->
        runBlocking {
            val source = createFileEditorContentSource(
                file = localFile(path, size = 6L),
                requestedCanWrite = true,
            ).getOrThrow()
            val stale = source.currentSnapshot().getOrThrow()
            path.writeBytes("outside".encodeToByteArray())

            val result = source.replaceContent(
                newSize = 3L,
                content = flowOf("new".encodeToByteArray()),
                expectedSnapshot = stale,
            )

            assertIs<FileEditorSourceChangedException>(result.exceptionOrNull())
            assertContentEquals("outside".encodeToByteArray(), path.readBytes())
            source.close()
        }
    }

    @Test
    fun localSourceReadsRangeAndReplacesContentFromChunks() = withTempFile(
        initialContent = "0123456789".encodeToByteArray(),
    ) { path ->
        runBlocking {
            val source = createFileEditorContentSource(
                file = localFile(path, size = 10L),
                requestedCanWrite = true,
            ).getOrThrow()

            assertContentEquals(
                "2345".encodeToByteArray(),
                source.readRange(2L, 6L).getOrThrow(),
            )

            val progress = mutableListOf<Pair<Long, Long>>()
            val replacement = listOf("hello", "-", "world")
                .map(String::encodeToByteArray)
            val result = source.replaceContent(
                newSize = 11L,
                content = flowOf(*replacement.toTypedArray()),
            ) { completed, total ->
                progress += completed to total
            }

            assertTrue(result.isSuccess)
            assertEquals(11L, source.size)
            assertContentEquals("hello-world".encodeToByteArray(), path.readBytes())
            assertEquals(11L to 11L, progress.last())
            source.close()
        }
    }

    @Test
    fun failedStreamingReplacementKeepsOriginalFile() = withTempFile(
        initialContent = "original".encodeToByteArray(),
    ) { path ->
        runBlocking {
            val source = createFileEditorContentSource(
                file = localFile(path, size = 8L),
                requestedCanWrite = true,
            ).getOrThrow()

            val result = source.replaceContent(
                newSize = 8L,
                content = flow {
                    emit("changed".encodeToByteArray())
                    throw IllegalStateException(AppStrings.ui_test_file_editor_content_access_jvm_simulate_stream_writing_interruption)
                },
            )

            assertTrue(result.isFailure)
            assertEquals(8L, source.size)
            assertContentEquals("original".encodeToByteArray(), path.readBytes())
            source.close()
        }
    }

    @Test
    fun readOnlySourceRejectsWritesAndWritableSourceSupportsEmptyFile() = withTempFile(
        initialContent = "keep".encodeToByteArray(),
    ) { path ->
        runBlocking {
            val readOnlySource = createFileEditorContentSource(
                file = localFile(path, size = 4L),
                requestedCanWrite = false,
            ).getOrThrow()

            val denied = readOnlySource.replaceContent(
                newSize = 0L,
                content = emptyFlow(),
            )
            assertTrue(denied.isFailure)
            assertContentEquals("keep".encodeToByteArray(), path.readBytes())

            val writableSource = createFileEditorContentSource(
                file = localFile(path, size = 4L),
                requestedCanWrite = true,
            ).getOrThrow()
            val emptied = writableSource.replaceContent(
                newSize = 0L,
                content = emptyFlow(),
            )

            assertTrue(emptied.isSuccess)
            assertEquals(0L, writableSource.size)
            assertContentEquals(byteArrayOf(), writableSource.readRange(0L, 0L).getOrThrow())
            assertEquals(0L, Files.size(path))
            readOnlySource.close()
            writableSource.close()
        }
    }

    @Test
    fun sourceRejectsAnUnboundedSingleRange() {
        val directory = Files.createTempDirectory("folderspan-editor-range-")
        val path = directory.resolve("large.bin")
        try {
            RandomAccessFile(path.toFile(), "rw").use { file ->
                file.setLength(5L * 1024 * 1024)
            }
            runBlocking {
                val source = createFileEditorContentSource(
                    file = localFile(path, size = Files.size(path)),
                    requestedCanWrite = false,
                ).getOrThrow()

                assertTrue(source.readRange(0L, 4L * 1024 * 1024 + 1L).isFailure)
                source.close()
            }
        } finally {
            directory.toFile().deleteRecursively()
        }
    }

    private fun localFile(path: Path, size: Long): FileSimpleInfo = FileSimpleInfo(
        name = path.fileName.toString(),
        isDirectory = false,
        isHidden = false,
        path = path.absolutePathString(),
        mineType = "application/octet-stream",
        size = size,
        createdDate = 0L,
        updatedDate = 0L,
        protocol = FileProtocol.Local,
        protocolId = "",
    )

    private inline fun withTempFile(
        initialContent: ByteArray,
        block: (Path) -> Unit,
    ) {
        val directory = Files.createTempDirectory("folderspan-editor-content-")
        val path = directory.resolve("sample.bin")
        try {
            path.writeBytes(initialContent)
            block(path)
        } finally {
            directory.toFile().deleteRecursively()
        }
    }
}
