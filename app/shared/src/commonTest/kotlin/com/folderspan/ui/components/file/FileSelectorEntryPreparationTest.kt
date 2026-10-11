package com.folderspan.ui.components.file

import com.folderspan.data.file.FileFilterType
import com.folderspan.data.file.FileSimpleInfo
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

class FileSelectorEntryPreparationTest {
    @Test
    fun displayConstraintHidesRejectedFilesButKeepsTraversalDirectories() {
        val entries = prepareFileSelectorEntries(
            files = listOf(directory("nested"), file("photo.jpg"), file("notes.txt")),
            checkedFiles = emptyList(),
            selectionFilterTypes = emptyList(),
            configuredFileFilters = emptyList(),
            displayConstraints = FileSelectorConstraints(allowedExtensions = setOf("jpg")),
            selectionConstraints = FileSelectorConstraints.Unrestricted,
        )

        assertEquals(listOf("nested", "photo.jpg"), entries.map { entry -> entry.file.name })
        entries.forEach { entry -> assertNull(entry.selectionRejection) }
    }

    @Test
    fun selectionConstraintKeepsRejectedFileVisibleWithTypedReason() {
        val entries = prepareFileSelectorEntries(
            files = listOf(file("photo.jpg"), file("notes.txt")),
            checkedFiles = emptyList(),
            selectionFilterTypes = emptyList(),
            configuredFileFilters = emptyList(),
            displayConstraints = FileSelectorConstraints.Unrestricted,
            selectionConstraints = FileSelectorConstraints(allowedExtensions = setOf("jpg")),
        )

        assertEquals(listOf("photo.jpg", "notes.txt"), entries.map { entry -> entry.file.name })
        assertNull(entries.first().selectionRejection)
        val rejection = assertIs<FileSelectorSelectionRejection.Constraint>(
            entries.last().selectionRejection,
        )
        assertIs<FileSelectorConstraintViolation.ExtensionNotAllowed>(rejection.violation)
    }

    @Test
    fun simultaneousConstraintsApplyDisplayBeforeSelection() {
        val entries = prepareFileSelectorEntries(
            files = listOf(file("large.jpg", size = 30), file("notes.txt", size = 1)),
            checkedFiles = emptyList(),
            selectionFilterTypes = emptyList(),
            configuredFileFilters = emptyList(),
            displayConstraints = FileSelectorConstraints(allowedExtensions = setOf("jpg")),
            selectionConstraints = FileSelectorConstraints(maxSizeBytes = 20),
        )

        assertEquals(listOf("large.jpg"), entries.map { entry -> entry.file.name })
        val rejection = assertIs<FileSelectorSelectionRejection.Constraint>(
            entries.single().selectionRejection,
        )
        assertEquals(
            FileSelectorConstraintViolation.FileTooLarge(
                actualSizeBytes = 30,
                maxSizeBytes = 20,
            ),
            rejection.violation,
        )
    }

    @Test
    fun existingSelectionCategoryAndNewConstraintAreIntersected() {
        val entries = prepareFileSelectorEntries(
            files = listOf(directory("nested"), file("photo.jpg"), file("notes.txt")),
            checkedFiles = emptyList(),
            selectionFilterTypes = listOf(FileFilterType.File),
            configuredFileFilters = emptyList(),
            displayConstraints = FileSelectorConstraints.Unrestricted,
            selectionConstraints = FileSelectorConstraints(allowedExtensions = setOf("jpg")),
        )

        assertIs<FileSelectorSelectionRejection.CategoryNotAllowed>(entries[0].selectionRejection)
        assertNull(entries[1].selectionRejection)
        assertIs<FileSelectorSelectionRejection.Constraint>(entries[2].selectionRejection)
    }

    @Test
    fun changingDisplayConstraintRecomputesVisibleEntries() {
        val files = listOf(directory("nested"), file("photo.jpg"), file("diagram.png"))

        val unrestricted = prepareFileSelectorEntries(
            files = files,
            checkedFiles = emptyList(),
            selectionFilterTypes = emptyList(),
            configuredFileFilters = emptyList(),
            displayConstraints = FileSelectorConstraints.Unrestricted,
            selectionConstraints = FileSelectorConstraints.Unrestricted,
        )
        val pngOnly = prepareFileSelectorEntries(
            files = files,
            checkedFiles = emptyList(),
            selectionFilterTypes = emptyList(),
            configuredFileFilters = emptyList(),
            displayConstraints = FileSelectorConstraints(allowedExtensions = setOf("png")),
            selectionConstraints = FileSelectorConstraints.Unrestricted,
        )

        assertEquals(listOf("nested", "photo.jpg", "diagram.png"), unrestricted.map { it.file.name })
        assertEquals(listOf("nested", "diagram.png"), pngOnly.map { it.file.name })
    }

    @Test
    fun omittedConstraintsPreserveVisibleAndSelectableEntries() {
        val files = listOf(directory("nested"), file("notes.txt"))

        val entries = prepareFileSelectorEntries(
            files = files,
            checkedFiles = emptyList(),
            selectionFilterTypes = emptyList(),
            configuredFileFilters = emptyList(),
        )

        assertEquals(files, entries.map { entry -> entry.file })
        entries.forEach { entry -> assertNull(entry.selectionRejection) }
    }

    @Test
    fun staleSelectionsAreRemovedWhenEitherConstraintChanges() {
        val selected = listOf(file("photo.jpg", size = 30), file("notes.txt", size = 1))

        val retained = retainValidFileSelectorSelections(
            selectedFiles = selected,
            selectionFilterTypes = listOf(FileFilterType.File),
            configuredFileFilters = emptyList(),
            displayConstraints = FileSelectorConstraints(allowedExtensions = setOf("jpg")),
            selectionConstraints = FileSelectorConstraints(maxSizeBytes = 20),
        )

        assertEquals(emptyList(), retained)
    }

    @Test
    fun fileSelectionRequiresExplicitChoiceBeforeConfirm() {
        assertEquals(
            emptyList(),
            resolveFileSelectorConfirmSelection(
                selectedFiles = emptyList(),
                currentPath = "/home/webb",
                selectionFilterTypes = listOf(FileFilterType.File),
            ),
        )
        assertEquals(
            emptyList(),
            resolveFileSelectorConfirmSelection(
                selectedFiles = emptyList(),
                currentPath = "/home/webb",
                selectionFilterTypes = listOf(FileFilterType.Image),
            ),
        )
        assertEquals(
            emptyList(),
            resolveFileSelectorConfirmSelection(
                selectedFiles = emptyList(),
                currentPath = "/home/webb",
                selectionFilterTypes = emptyList(),
                selectionConstraints = FileSelectorConstraints(
                    allowedKinds = setOf(FileSelectorEntryKind.File),
                ),
            ),
        )
    }

    @Test
    fun directoryAndUnrestrictedModesUseCurrentPathWhenNothingIsChecked() {
        val currentDirectory = currentDirectoryAsSelection("/home/webb")

        assertEquals(
            listOf(currentDirectory),
            resolveFileSelectorConfirmSelection(
                selectedFiles = emptyList(),
                currentPath = "/home/webb",
                selectionFilterTypes = listOf(FileFilterType.Folder),
            ),
        )
        assertEquals(
            listOf(currentDirectory),
            resolveFileSelectorConfirmSelection(
                selectedFiles = emptyList(),
                currentPath = "/home/webb",
                selectionFilterTypes = emptyList(),
                selectionConstraints = FileSelectorConstraints(
                    allowedKinds = setOf(FileSelectorEntryKind.Directory),
                ),
            ),
        )
        assertEquals(
            listOf(currentDirectory),
            resolveFileSelectorConfirmSelection(
                selectedFiles = emptyList(),
                currentPath = "/home/webb",
                selectionFilterTypes = emptyList(),
            ),
        )
        assertEquals(
            emptyList(),
            resolveFileSelectorConfirmSelection(
                selectedFiles = emptyList(),
                currentPath = "   ",
                selectionFilterTypes = listOf(FileFilterType.Folder),
            ),
        )
        assertEquals("webb", currentDirectory.name)
        assertEquals("/home/webb", currentDirectory.path)
        assertEquals(true, currentDirectory.isDirectory)
    }

    @Test
    fun explicitSelectionWinsOverCurrentDirectory() {
        val selected = file("notes.txt")

        assertEquals(
            listOf(selected),
            resolveFileSelectorConfirmSelection(
                selectedFiles = listOf(selected),
                currentPath = "/home/webb",
                selectionFilterTypes = listOf(FileFilterType.File),
            ),
        )
        assertEquals(
            listOf(selected),
            resolveFileSelectorConfirmSelection(
                selectedFiles = listOf(selected),
                currentPath = "/home/webb",
                selectionFilterTypes = listOf(FileFilterType.Folder),
            ),
        )
    }

    @Test
    fun explicitDisplayKindCanHideDirectory() {
        val entries = prepareFileSelectorEntries(
            files = listOf(directory("nested"), file("notes.txt")),
            checkedFiles = emptyList(),
            selectionFilterTypes = emptyList(),
            configuredFileFilters = emptyList(),
            displayConstraints = FileSelectorConstraints(
                allowedKinds = setOf(FileSelectorEntryKind.File),
            ),
        )

        assertEquals(listOf("notes.txt"), entries.map { entry -> entry.file.name })
    }

    @Test
    fun displayDirectoryKindHidesFilesAndLeavesFoldersSelectable() {
        val entries = prepareFileSelectorEntries(
            files = listOf(directory("nested"), file("notes.txt")),
            checkedFiles = emptyList(),
            selectionFilterTypes = emptyList(),
            configuredFileFilters = emptyList(),
            displayConstraints = FileSelectorConstraints(
                allowedKinds = setOf(FileSelectorEntryKind.Directory),
            ),
            selectionConstraints = FileSelectorConstraints.Unrestricted,
        )

        assertEquals(listOf("nested"), entries.map { entry -> entry.file.name })
        assertNull(entries.single().selectionRejection)
    }

    @Test
    fun oppositeDisplayAndSelectionKindsLeaveVisibleFoldersUnselectable() {
        val entries = prepareFileSelectorEntries(
            files = listOf(directory("nested"), file("notes.txt")),
            checkedFiles = emptyList(),
            selectionFilterTypes = listOf(FileFilterType.File),
            configuredFileFilters = emptyList(),
            displayConstraints = FileSelectorConstraints(
                allowedKinds = setOf(FileSelectorEntryKind.Directory),
            ),
        )

        assertEquals(listOf("nested"), entries.map { entry -> entry.file.name })
        assertIs<FileSelectorSelectionRejection.CategoryNotAllowed>(
            entries.single().selectionRejection,
        )
    }

    private fun file(name: String, size: Long = 1) = entry(
        name = name,
        isDirectory = false,
        size = size,
    )

    private fun directory(name: String) = entry(
        name = name,
        isDirectory = true,
        size = -1,
    )

    private fun entry(name: String, isDirectory: Boolean, size: Long) = FileSimpleInfo(
        name = name,
        isDirectory = isDirectory,
        isHidden = false,
        path = "/$name",
        mineType = name.substringAfterLast('.', ""),
        size = size,
        createdDate = 0,
        updatedDate = 0,
    )
}
