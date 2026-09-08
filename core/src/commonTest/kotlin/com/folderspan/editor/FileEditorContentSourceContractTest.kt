package com.folderspan.editor

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class FileEditorContentSourceContractTest {
    @Test
    fun capabilitiesRejectUnsupportedWrites() = runTest {
        val source = RecordingFileEditorContentSource(
            initial = "read-only".encodeToByteArray(),
            canWrite = false,
        )

        assertTrue(source.capabilities.supportsRangeRead)
        assertTrue(source.capabilities.supportsSaveAs)
        assertTrue(source.writeRange(0L, byteArrayOf(1)).isFailure)
        assertTrue(source.replaceContent(0L, flowOf()).isFailure)
    }

    @Test
    fun rangeWriteChecksSnapshotAndReportsProgress() = runTest {
        val source = RecordingFileEditorContentSource("abcdef".encodeToByteArray())
        val snapshot = source.currentSnapshot().getOrThrow()
        val progress = mutableListOf<Pair<Long, Long>>()

        val result = source.writeRange(
            startOffset = 2L,
            data = "XY".encodeToByteArray(),
            expectedSnapshot = snapshot,
        ) { completed, total -> progress += completed to total }

        assertTrue(result.isSuccess)
        assertContentEquals("abXYef".encodeToByteArray(), source.bytes())
        assertEquals(listOf(2L to 2L), progress)
    }

    @Test
    fun externalChangeRejectsRangeAndStreamedWrites() = runTest {
        val source = RecordingFileEditorContentSource("before".encodeToByteArray())
        val stale = source.currentSnapshot().getOrThrow()
        source.simulateExternalChange("outside".encodeToByteArray())

        val range = source.writeRange(0L, byteArrayOf(1), stale)
        val replace = source.replaceContent(
            newSize = 3L,
            content = flowOf("new".encodeToByteArray()),
            expectedSnapshot = stale,
        )

        assertIs<FileEditorSourceChangedException>(range.exceptionOrNull())
        assertIs<FileEditorSourceChangedException>(replace.exceptionOrNull())
        assertContentEquals("outside".encodeToByteArray(), source.bytes())
    }

    @Test
    fun streamedReplacementFailureKeepsOriginalBytes() = runTest {
        val source = RecordingFileEditorContentSource("original".encodeToByteArray()).apply {
            failReplacementAfterChunks = 2
        }

        val result = source.replaceContent(
            newSize = 7L,
            content = flowOf("cha".encodeToByteArray(), "nged".encodeToByteArray()),
        )

        assertTrue(result.isFailure)
        assertContentEquals("original".encodeToByteArray(), source.bytes())
    }

    @Test
    fun cancelledStreamedReplacementKeepsOriginalBytes() = runTest {
        val source = RecordingFileEditorContentSource("original".encodeToByteArray()).apply {
            cancelReplacementAfterChunks = 1
        }

        val result = source.replaceContent(
            newSize = 7L,
            content = flowOf("changed".encodeToByteArray()),
        )

        assertIs<CancellationException>(result.exceptionOrNull())
        assertContentEquals("original".encodeToByteArray(), source.bytes())
    }

    @Test
    fun saveAsStreamsToIndependentDestination() = runTest {
        val source = RecordingFileEditorContentSource("source".encodeToByteArray(), canWrite = false)
        val destination = RecordingFileEditorContentSource(byteArrayOf())

        val result = source.saveAs(
            destination = destination,
            newSize = 5L,
            content = flowOf("copy".encodeToByteArray(), byteArrayOf('!'.code.toByte())),
        )

        assertTrue(result.isSuccess)
        assertContentEquals("copy!".encodeToByteArray(), destination.bytes())
    }

    @Test
    fun atomicReplacementIsExplicitCapability() {
        val atomic = RecordingFileEditorContentSource(byteArrayOf(), supportsAtomicReplace = true)
        val nonAtomic = RecordingFileEditorContentSource(byteArrayOf(), supportsAtomicReplace = false)

        assertTrue(atomic.capabilities.supportsAtomicReplace)
        assertTrue(!nonAtomic.capabilities.supportsAtomicReplace)
    }
}
