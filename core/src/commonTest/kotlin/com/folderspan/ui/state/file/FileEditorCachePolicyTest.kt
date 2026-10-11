package com.folderspan.ui.state.file

import com.folderspan.data.file.FileProtocol
import com.folderspan.data.file.FileSimpleInfo
import kotlin.test.Test
import kotlin.test.assertEquals

class FileEditorCachePolicyTest {
    @Test
    fun staleAndOverflowRemoteCachesAreRemovedWhileActiveCacheIsPreserved() {
        val now = 100_000L
        val entries = listOf(
            cache("active", now - 100L),
            cache("newest", now - 200L),
            cache("overflow", now - 300L),
            cache("stale", now - 20_000L),
        )

        val removed = selectStaleEditorCachePaths(
            entries = entries,
            activePaths = setOf("active"),
            nowMillis = now,
            maxAgeMillis = 10_000L,
            maxEntries = 1,
        )

        assertEquals(listOf("overflow", "stale"), removed)
    }

    private fun cache(path: String, updatedAt: Long) = FileSimpleInfo(
        name = path,
        isDirectory = false,
        isHidden = false,
        path = path,
        mineType = "application/octet-stream",
        size = 1L,
        createdDate = updatedAt,
        updatedDate = updatedAt,
        protocol = FileProtocol.Local,
        protocolId = "",
    )
}
