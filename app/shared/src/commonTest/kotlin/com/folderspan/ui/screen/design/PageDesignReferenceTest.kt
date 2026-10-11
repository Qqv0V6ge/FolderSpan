package com.folderspan.ui.screen.design

import androidx.compose.ui.unit.dp
import com.folderspan.ui.components.pagestate.PageErrorType
import com.folderspan.ui.components.pagestate.PageViewState
import com.folderspan.utils.WindowPaneMode
import com.folderspan.utils.WindowSizeClass
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class PageDesignReferenceTest {
    @Test
    fun compactWindowUsesSinglePaneAndFillsAvailableSpace() {
        val layout = referencePageLayout(
            ReferencePageWindowInfo(
                widthSizeClass = WindowSizeClass.Compact,
            ),
        )

        assertEquals(WindowPaneMode.SinglePane, layout.paneMode)
        assertEquals(0.dp, layout.paneSpacing)
    }

    @Test
    fun mediumAndExpandedWindowsUseProgressivelyWiderListPanes() {
        val medium = referencePageLayout(
            ReferencePageWindowInfo(
                widthSizeClass = WindowSizeClass.Medium,
            ),
        )
        val expanded = referencePageLayout(
            ReferencePageWindowInfo(
                widthSizeClass = WindowSizeClass.Expanded,
            ),
        )

        assertEquals(WindowPaneMode.TwoPane, medium.paneMode)
        assertEquals(240.dp, medium.listPaneWidth)
        assertEquals(0.dp, medium.paneSpacing)

        assertEquals(WindowPaneMode.TwoPane, expanded.paneMode)
        assertEquals(320.dp, expanded.listPaneWidth)
        assertEquals(0.dp, expanded.paneSpacing)
    }

    @Test
    fun largeWindowsUseWiderListPanesAndFullWidthDetailPanes() {
        val large = referencePageLayout(
            ReferencePageWindowInfo(
                widthSizeClass = WindowSizeClass.Large,
            ),
        )
        val extraLarge = referencePageLayout(
            ReferencePageWindowInfo(
                widthSizeClass = WindowSizeClass.ExtraLarge,
            ),
        )

        assertEquals(WindowPaneMode.TwoPane, large.paneMode)
        assertEquals(360.dp, large.listPaneWidth)
        assertEquals(0.dp, large.paneSpacing)

        assertEquals(WindowPaneMode.TwoPane, extraLarge.paneMode)
        assertEquals(400.dp, extraLarge.listPaneWidth)
        assertEquals(0.dp, extraLarge.paneSpacing)
    }

    @Test
    fun windowInfoUsesRepositoryWidthClassification() {
        val portrait = referencePageWindowInfo(maxWidth = 360.dp, maxHeight = 800.dp)
        val shortLandscape = referencePageWindowInfo(maxWidth = 640.dp, maxHeight = 360.dp)

        assertEquals(WindowSizeClass.Compact, portrait.widthSizeClass)
        assertEquals(WindowSizeClass.Medium, shortLandscape.widthSizeClass)
    }

    @Test
    fun separatingPaneGapIsExcludedFromTwoPaneContent() {
        val layout = referencePageLayout(
            ReferencePageWindowInfo(
                widthSizeClass = WindowSizeClass.Expanded,
                separatingPaneGap = 48.dp,
            ),
        )

        assertEquals(WindowPaneMode.TwoPane, layout.paneMode)
        assertEquals(48.dp, layout.paneSpacing)
    }

    @Test
    fun pageStateUsesSharedCoreStatePriority() {
        assertEquals(
            PageViewState.Loading,
            PageDesignReferenceUiState(
                isLoading = true,
                errorMessage = "ignored while loading",
            ).toPageViewState(),
        )

        val error = PageDesignReferenceUiState(errorMessage = "failed").toPageViewState()
        assertEquals(PageErrorType.General, (error as PageViewState.Error).error.type)
        assertEquals("failed", error.error.message)

        assertEquals(
            PageViewState.Empty(),
            PageDesignReferenceUiState().toPageViewState(),
        )
        assertEquals(
            PageViewState.Content,
            PageDesignReferenceUiState(
                files = listOf(referenceFile()),
            ).toPageViewState(),
        )
    }

    @Test
    fun selectedFileIsResolvedFromHoistedSelection() {
        val file = referenceFile()

        assertEquals(
            file,
            PageDesignReferenceUiState(
                files = listOf(file),
                selectedFileId = file.id,
            ).selectedFile,
        )
        assertNull(
            PageDesignReferenceUiState(
                files = listOf(file),
                selectedFileId = "missing",
            ).selectedFile,
        )
    }

    private fun referenceFile() = ReferenceFileUiModel(
        id = "document",
        name = "document.txt",
        path = "/document.txt",
        typeLabel = "Text",
        sizeLabel = "1 KB",
        modifiedLabel = "2026-08-09",
        isDirectory = false,
    )
}
