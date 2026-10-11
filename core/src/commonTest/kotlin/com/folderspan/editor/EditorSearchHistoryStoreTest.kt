package com.folderspan.editor

import com.folderspan.test.createInMemorySettings
import com.folderspan.utils.SyncSnapshotChangeNotifier
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class EditorSearchHistoryStoreTest {
    @AfterTest
    fun tearDown() {
        SyncSnapshotChangeNotifier.setEditorSearchHistoryHandler(null)
    }

    @Test
    fun recordsNewestFirstRemovesDuplicatesAndKeepsAtMostFifty() {
        var now = 0L
        val store = EditorSearchHistoryStore(createInMemorySettings(), now = { ++now })
        var notifications = 0
        SyncSnapshotChangeNotifier.setEditorSearchHistoryHandler { notifications += 1 }

        repeat(55) { index -> store.record(request("query-$index")) }
        store.record(request("query-30"))

        val entries = store.list()
        assertEquals(EDITOR_SEARCH_HISTORY_LIMIT, entries.size)
        assertEquals(listOf("query-30"), entries.first().queries)
        assertEquals(1, entries.count { it.queries == listOf("query-30") })
        assertEquals(56, notifications)
        assertTrue(entries.none { it.queries == listOf("query-0") })
    }

    @Test
    fun deduplicatesExactQueryContentAndKeepsLatestOptions() {
        var now = 0L
        val store = EditorSearchHistoryStore(createInMemorySettings(), now = { ++now })
        store.record(request("repeated query"))
        store.record(
            request("repeated query", mode = EditorSearchMode.Regex).copy(
                encoding = EditorTextEncoding.GBK,
                caseSensitive = true,
                wholeWord = true,
                direction = EditorSearchDirection.Backward,
            ),
        )

        val entry = store.list().single()
        assertEquals(listOf("repeated query"), entry.queries)
        assertEquals(EditorSearchMode.Regex, entry.mode)
        assertEquals(EditorTextEncoding.GBK, entry.encoding)
        assertEquals(true, entry.caseSensitive)
        assertEquals(true, entry.wholeWord)
        assertEquals(EditorSearchDirection.Backward, entry.direction)
    }

    @Test
    fun newlyRecordedQueryStaysFirstWhenClockMovesBackward() {
        var now = 1_000L
        val store = EditorSearchHistoryStore(createInMemorySettings(), now = { now })
        store.record(request("older query"))

        now = 10L
        store.record(request("latest query"))

        val entries = store.list()
        assertEquals(listOf("latest query"), entries[0].queries)
        assertEquals(1_001L, entries[0].updatedAt)
        assertEquals(listOf("older query"), entries[1].queries)
        assertEquals(1_000L, entries[1].updatedAt)
    }

    @Test
    fun newlyRecordedDuplicateWinsOverFutureTimestampFromSync() {
        val settings = createInMemorySettings()
        val store = EditorSearchHistoryStore(settings, now = { 10L })
        store.replace(
            listOf(
                EditorSearchHistoryEntry(
                    mode = EditorSearchMode.Text,
                    queries = listOf("repeated query"),
                    encoding = EditorTextEncoding.UTF8,
                    caseSensitive = false,
                    wholeWord = false,
                    direction = EditorSearchDirection.Forward,
                    updatedAt = 5_000L,
                ),
            ),
        )

        store.record(
            request("repeated query", mode = EditorSearchMode.Regex).copy(
                encoding = EditorTextEncoding.GBK,
                caseSensitive = true,
                wholeWord = true,
                direction = EditorSearchDirection.Backward,
            ),
        )

        val entry = store.list().single()
        assertEquals(5_001L, entry.updatedAt)
        assertEquals(EditorSearchMode.Regex, entry.mode)
        assertEquals(EditorTextEncoding.GBK, entry.encoding)
        assertEquals(true, entry.caseSensitive)
        assertEquals(true, entry.wholeWord)
        assertEquals(EditorSearchDirection.Backward, entry.direction)
    }

    @Test
    fun preservesWhitespaceQueriesAsExactSearchContent() {
        var now = 0L
        val store = EditorSearchHistoryStore(createInMemorySettings(), now = { ++now })

        listOf(" ", "\n", "first\nsecond", " first\nsecond ").forEach { query ->
            store.record(request(query))
        }

        assertEquals(
            listOf(
                listOf(" first\nsecond "),
                listOf("first\nsecond"),
                listOf("\n"),
                listOf(" "),
            ),
            store.list().map(EditorSearchHistoryEntry::queries),
        )
    }

    @Test
    fun snapshotRoundTripPreservesQueryTypeAndOptionsWithoutRenotifying() {
        val source = EditorSearchHistoryStore(createInMemorySettings(), now = { 42L })
        source.record(
            request("[a-z]+", mode = EditorSearchMode.Regex).copy(
                caseSensitive = false,
                wholeWord = true,
                direction = EditorSearchDirection.Backward,
                encoding = EditorTextEncoding.GBK,
            ),
        )
        val target = EditorSearchHistoryStore(createInMemorySettings())
        var notifications = 0
        SyncSnapshotChangeNotifier.setEditorSearchHistoryHandler { notifications += 1 }

        target.applySnapshot(source.encodeSnapshot())

        assertEquals(source.list(), target.list())
        assertEquals(0, notifications)
    }

    @Test
    fun removesOneEntryAndClearsAllWithChangeNotificationsOnly() {
        var now = 0L
        val settings = createInMemorySettings()
        val store = EditorSearchHistoryStore(settings, now = { ++now })
        store.record(request("first"))
        store.record(request("second"))
        var notifications = 0
        SyncSnapshotChangeNotifier.setEditorSearchHistoryHandler { notifications += 1 }
        val first = store.list().first { it.queries == listOf("first") }

        assertEquals(listOf(listOf("second")), store.remove(first).map { it.queries })
        assertEquals(1, notifications)
        assertEquals(listOf(listOf("second")), EditorSearchHistoryStore(settings).list().map { it.queries })

        store.remove(first)
        assertEquals(1, notifications)

        assertTrue(store.clear().isEmpty())
        assertTrue(EditorSearchHistoryStore(settings).list().isEmpty())
        assertEquals(2, notifications)

        store.clear()
        assertEquals(2, notifications)
    }

    private fun request(
        query: String,
        mode: EditorSearchMode = EditorSearchMode.Text,
    ) = EditorSearchRequest(
        mode = mode,
        queries = listOf(query),
        range = EditorSearchRange(0L, 10L),
    )
}
