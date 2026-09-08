package com.folderspan.ui.components.file

import com.folderspan.data.file.FileSimpleInfo
import com.folderspan.extensions.formatFileSize
import strings.AppStrings
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertSame
import kotlin.test.assertTrue

class FileSelectorConstraintsTest {
    @Test
    fun extensionsAreNormalizedAndMatchedFromFileNameWithoutCaseSensitivity() {
        val constraints = FileSelectorConstraints(
            allowedExtensions = setOf(" .JPG ", ".png", "jpg"),
        )

        assertEquals(setOf("jpg", "png"), constraints.allowedExtensions)
        assertSame(
            FileSelectorConstraintResult.Allowed,
            constraints.evaluate(file(name = "holiday.JPG")),
        )
    }

    @Test
    fun explicitKindRuleRejectsDirectoryWithTypedViolation() {
        val result = FileSelectorConstraints(
            allowedKinds = setOf(FileSelectorEntryKind.File),
        ).evaluate(file(name = "photos", isDirectory = true))

        val violation = assertIs<FileSelectorConstraintResult.Rejected>(result).violation
        assertEquals(
            FileSelectorConstraintViolation.KindNotAllowed(
                actualKind = FileSelectorEntryKind.Directory,
                allowedKinds = setOf(FileSelectorEntryKind.File),
            ),
            violation,
        )
    }

    @Test
    fun minimumAndMaximumSizeIncludeTheirBoundaryValues() {
        val constraints = FileSelectorConstraints(
            minSizeBytes = 10,
            maxSizeBytes = 20,
        )

        assertSame(FileSelectorConstraintResult.Allowed, constraints.evaluate(file(size = 10)))
        assertSame(FileSelectorConstraintResult.Allowed, constraints.evaluate(file(size = 20)))
        assertEquals(
            FileSelectorConstraintViolation.FileTooSmall(actualSizeBytes = 9, minSizeBytes = 10),
            assertIs<FileSelectorConstraintResult.Rejected>(constraints.evaluate(file(size = 9))).violation,
        )
        assertEquals(
            FileSelectorConstraintViolation.FileTooLarge(actualSizeBytes = 21, maxSizeBytes = 20),
            assertIs<FileSelectorConstraintResult.Rejected>(constraints.evaluate(file(size = 21))).violation,
        )
    }

    @Test
    fun unavailableSizeIsRejectedOnlyWhenSizeRuleIsConfigured() {
        val unavailableFile = file(size = -1)

        assertSame(
            FileSelectorConstraintResult.Allowed,
            FileSelectorConstraints.Unrestricted.evaluate(unavailableFile),
        )
        assertEquals(
            FileSelectorConstraintViolation.SizeUnavailable(
                minSizeBytes = null,
                maxSizeBytes = 20,
            ),
            assertIs<FileSelectorConstraintResult.Rejected>(
                FileSelectorConstraints(maxSizeBytes = 20).evaluate(unavailableFile),
            ).violation,
        )
    }

    @Test
    fun zeroByteFileIsAValidKnownSize() {
        assertSame(
            FileSelectorConstraintResult.Allowed,
            FileSelectorConstraints(maxSizeBytes = 0).evaluate(file(size = 0)),
        )
    }

    @Test
    fun invalidSizeConfigurationIsRejectedAtConstruction() {
        assertFailsWith<IllegalArgumentException> {
            FileSelectorConstraints(minSizeBytes = -1)
        }
        assertFailsWith<IllegalArgumentException> {
            FileSelectorConstraints(maxSizeBytes = -1)
        }
        assertFailsWith<IllegalArgumentException> {
            FileSelectorConstraints(minSizeBytes = 2, maxSizeBytes = 1)
        }
    }

    @Test
    fun violationOrderIsKindThenExtensionThenSize() {
        val kindViolation = FileSelectorConstraints(
            allowedKinds = setOf(FileSelectorEntryKind.Directory),
            allowedExtensions = setOf("jpg"),
            maxSizeBytes = 10,
        ).evaluate(file(name = "notes.txt", size = -1))
        assertIs<FileSelectorConstraintViolation.KindNotAllowed>(
            assertIs<FileSelectorConstraintResult.Rejected>(kindViolation).violation,
        )

        val extensionViolation = FileSelectorConstraints(
            allowedKinds = setOf(FileSelectorEntryKind.File),
            allowedExtensions = setOf("jpg"),
            maxSizeBytes = 10,
        ).evaluate(file(name = "notes.txt", size = -1))
        assertEquals(
            FileSelectorConstraintViolation.ExtensionNotAllowed(
                actualExtension = "txt",
                allowedExtensions = setOf("jpg"),
            ),
            assertIs<FileSelectorConstraintResult.Rejected>(extensionViolation).violation,
        )

        val sizeViolation = FileSelectorConstraints(
            allowedKinds = setOf(FileSelectorEntryKind.File),
            allowedExtensions = setOf("jpg"),
            maxSizeBytes = 10,
        ).evaluate(file(name = "photo.jpg", size = -1))
        assertIs<FileSelectorConstraintViolation.SizeUnavailable>(
            assertIs<FileSelectorConstraintResult.Rejected>(sizeViolation).violation,
        )
    }

    @Test
    fun fileOnlyExtensionAndSizeRulesDoNotBlockDirectoryTraversal() {
        val constraints = FileSelectorConstraints(
            allowedExtensions = setOf("jpg"),
            minSizeBytes = 1,
            maxSizeBytes = 10,
        )

        assertSame(
            FileSelectorConstraintResult.Allowed,
            constraints.evaluate(file(name = "nested", isDirectory = true, size = -1)),
        )
    }

    @Test
    fun extensionlessFileReportsNullActualExtension() {
        val result = FileSelectorConstraints(
            allowedExtensions = setOf("txt"),
        ).evaluate(file(name = "README"))

        assertEquals(
            FileSelectorConstraintViolation.ExtensionNotAllowed(
                actualExtension = null,
                allowedExtensions = setOf("txt"),
            ),
            assertIs<FileSelectorConstraintResult.Rejected>(result).violation,
        )
    }

    @Test
    fun localizedViolationsIncludeActualAndConfiguredValues() {
        val kindMessage = FileSelectorConstraintViolation.KindNotAllowed(
            actualKind = FileSelectorEntryKind.Directory,
            allowedKinds = setOf(FileSelectorEntryKind.File),
        ).localizedMessage()
        val extensionMessage = FileSelectorConstraintViolation.ExtensionNotAllowed(
            actualExtension = "log",
            allowedExtensions = setOf("jpg", "png"),
        ).localizedMessage()
        val unavailableMessage = FileSelectorConstraintViolation.SizeUnavailable(
            minSizeBytes = 1,
            maxSizeBytes = 20,
        ).localizedMessage()
        val tooSmallMessage = FileSelectorConstraintViolation.FileTooSmall(
            actualSizeBytes = 1,
            minSizeBytes = 2,
        ).localizedMessage()
        val tooLargeMessage = FileSelectorConstraintViolation.FileTooLarge(
            actualSizeBytes = 21,
            maxSizeBytes = 20,
        ).localizedMessage()

        assertTrue(kindMessage.contains(AppStrings.ui_folder))
        assertTrue(kindMessage.contains(AppStrings.ui_file))
        assertTrue(extensionMessage.contains(".log"))
        assertTrue(extensionMessage.contains(".jpg"))
        assertTrue(extensionMessage.contains(".png"))
        assertTrue(unavailableMessage.contains(1L.formatFileSize()))
        assertTrue(unavailableMessage.contains(20L.formatFileSize()))
        assertTrue(tooSmallMessage.contains(1L.formatFileSize()))
        assertTrue(tooSmallMessage.contains(2L.formatFileSize()))
        assertTrue(tooLargeMessage.contains(21L.formatFileSize()))
        assertTrue(tooLargeMessage.contains(20L.formatFileSize()))
    }

    private fun file(
        name: String = "file.txt",
        isDirectory: Boolean = false,
        size: Long = 1,
    ) = FileSimpleInfo(
        name = name,
        isDirectory = isDirectory,
        isHidden = false,
        path = "/$name",
        mineType = "ambiguous-value",
        size = size,
        createdDate = 0,
        updatedDate = 0,
    )
}
