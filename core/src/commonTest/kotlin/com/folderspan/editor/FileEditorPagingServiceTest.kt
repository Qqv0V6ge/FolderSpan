package com.folderspan.editor

import com.folderspan.test.runSuspendTest
import kotlinx.coroutines.joinAll
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class FileEditorPagingServiceTest {
    @Test
    fun tenGiBSourceReadsOneBoundedPageAndLruNeverExceedsLimit() = runSuspendTest {
        val size = 10L * 1024 * 1024 * 1024
        val source = VirtualFileEditorContentSource(size) { offset -> (offset % 251).toByte() }
        val paging = FileEditorPagingService(source, pageSize = 32 * 1024, maxCachedPages = 3)

        val first = paging.readPage(0L, size).getOrThrow()

        assertEquals(1, source.reads.size)
        assertEquals(32L * 1024, source.reads.single().last - source.reads.single().first + 1L)
        assertEquals(32 * 1024, first.bytes.size)
        assertEquals(1, paging.cachedPageCount())

        paging.readPage(1L, size).getOrThrow()
        paging.readPage(2L, size).getOrThrow()
        paging.readPage(3L, size).getOrThrow()
        assertEquals(3, paging.cachedPageCount())

        paging.readPage(0L, size).getOrThrow()
        assertEquals(5, source.reads.size)
        assertEquals(3, paging.cachedPageCount())
        assertTrue(source.reads.all { it.last - it.first + 1L <= 32L * 1024 })
    }

    @Test
    fun cacheReturnsDefensiveCopiesAndPrefetchesNeighborPages() = runSuspendTest {
        val size = 4L * 32 * 1024
        val source = VirtualFileEditorContentSource(size) { offset -> offset.toByte() }
        val paging = FileEditorPagingService(source, maxCachedPages = 3)
        val page = paging.readPage(1L, size).getOrThrow()
        val expected = page.bytes.copyOf()

        page.bytes.fill(0)
        assertContentEquals(expected, paging.readPage(1L, size).getOrThrow().bytes)

        paging.prefetchAround(1L, size).joinAll()
        assertEquals(3, paging.cachedPageCount())
        assertTrue(source.reads.size >= 2)
    }
}
