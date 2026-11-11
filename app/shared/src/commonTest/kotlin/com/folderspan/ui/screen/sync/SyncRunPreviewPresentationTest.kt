package com.folderspan.ui.screen.sync

import androidx.compose.ui.unit.dp
import com.folderspan.ui.state.main.*
import com.folderspan.utils.WindowSizeClass
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class SyncRunPreviewPresentationTest {
    @Test fun allFiveWidthsHaveUsableOverviewAndKeepCompactSinglePane() {
        assertNull(syncPreviewSummaryWidth(WindowSizeClass.Compact))
        assertEquals(listOf(260.dp, 320.dp, 360.dp, 400.dp), WindowSizeClass.entries.drop(1).map(::syncPreviewSummaryWidth))
    }

    @Test fun conflictRowsAndRecycleRowsHaveTheirOwnFilteredTotals() {
        val preview = SyncRunPreview("token", 1, "task", SyncMode.Mirror, emptyMap(),
            mapOf(SyncPreviewAction.Overwrite to SyncPreviewCount(5, 50), SyncPreviewAction.Recycle to SyncPreviewCount(5, 30)),
            10, 0, false, emptyList(), false, conflictCount = 2, conflictItemCount = 4)
        assertEquals(10, preview.filteredCount(null, false))
        assertEquals(5, preview.filteredCount(SyncPreviewAction.Recycle, false))
        assertEquals(4, preview.filteredCount(null, true))
        assertEquals(0, preview.filteredCount(SyncPreviewAction.Delete, false))
    }
}
