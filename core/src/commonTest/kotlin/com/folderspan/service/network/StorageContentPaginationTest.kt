package com.folderspan.service.network

import com.folderspan.test.runSuspendTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails

class StorageContentPaginationTest {
    @Test
    fun cursorCyclesAndFailedFollowingPagesCannotReturnASuccessfulPartialView() = runSuspendTest {
        var calls = 0
        assertFails {
            collectStoragePages {
                calls++
                emptyList<CloudStorageNode>() to "same-cursor"
            }
        }
        assertEquals(2, calls)
        calls = 0
        assertFails {
            collectStoragePages { cursor ->
                calls++
                if (cursor != null) error("injected missing next page")
                emptyList<CloudStorageNode>() to "next"
            }
        }
        assertEquals(2, calls)
        assertFails {
            collectStoragePages { emptyList<CloudStorageNode>() to "x".repeat(64 * 1024 + 1) }
        }
    }
}
