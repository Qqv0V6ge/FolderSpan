package com.folderspan.ui.components.file

import com.folderspan.data.file.FileProtocol
import com.folderspan.data.file.FileSimpleInfo
import com.folderspan.data.main.DiskMenuPermission
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class FileCardMenuTest {
    @Test
    fun readPermissionCountsAsMenuPermission() {
        assertTrue(DiskMenuPermission(read = true).hasAnyPermission())
    }

    @Test
    fun fileCardMenuCompositionDoesNotRequireNavigator() {
        assertEquals(false, shouldRequireNavigatorForFileCardMenuComposition())
    }

    @Test
    fun settingPermissionDoesNotExposeFileCardMenu() {
        val basePermission = DiskMenuPermission(setting = true)
        val item = file(path = "/document.txt")

        val resolvedPermission = resolveFileCardMenuPermission(
            basePermission = basePermission,
            file = item,
            selectedFiles = emptyList(),
            isPasteCopyFile = false,
            isPasteMoveFile = false,
            hasNavigator = true,
        )

        assertFalse(resolvedPermission.setting)
        assertFalse(resolvedPermission.hasAnyPermission())
        assertFalse(
            hasAnyFileCardMenuPermission(
                basePermission = basePermission,
                file = item,
                isFileChecked = false,
                currentSelectionDisallowsPaste = false,
                isPasteCopyFile = false,
                isPasteMoveFile = false,
                hasNavigator = true,
            )
        )
    }

    @Test
    fun fileCardMenuPermissionOmitsPasteWhenCurrentSelectionContainsFolder() {
        val permission = resolveFileCardMenuPermission(
            basePermission = allMenuPermissions,
            file = file(path = "/target"),
            selectedFiles = listOf(file(path = "/folder", isDirectory = true)),
            isPasteCopyFile = true,
            isPasteMoveFile = false,
            hasNavigator = true
        )

        assertFalse(permission.paste)
    }

    @Test
    fun fileCardMenuPermissionOmitsPasteWhenCurrentSelectionHasMultipleItems() {
        val permission = resolveFileCardMenuPermission(
            basePermission = allMenuPermissions,
            file = file(path = "/target"),
            selectedFiles = listOf(file(path = "/one"), file(path = "/two")),
            isPasteCopyFile = true,
            isPasteMoveFile = false,
            hasNavigator = true
        )

        assertFalse(permission.paste)
    }

    @Test
    fun fileCardMenuPermissionKeepsPasteForSingleSelectedFileAndUncheckedTarget() {
        val permission = resolveFileCardMenuPermission(
            basePermission = allMenuPermissions,
            file = file(path = "/target"),
            selectedFiles = listOf(file(path = "/source")),
            isPasteCopyFile = true,
            isPasteMoveFile = false,
            hasNavigator = true
        )

        assertTrue(permission.paste)
    }

    @Test
    fun fileCardMenuPermissionAllowsWritableFileEditingWithNavigator() {
        val permission = resolveFileCardMenuPermission(
            basePermission = allMenuPermissions,
            file = file(path = "/document.txt"),
            selectedFiles = emptyList(),
            isPasteCopyFile = false,
            isPasteMoveFile = false,
            hasNavigator = true
        )

        assertTrue(permission.read)
        assertTrue(permission.write)
    }

    @Test
    fun fileCardMenuPermissionAllowsReadOnlyFileViewingWithNavigator() {
        val permission = resolveFileCardMenuPermission(
            basePermission = DiskMenuPermission(read = true, write = false),
            file = file(path = "/document.txt"),
            selectedFiles = emptyList(),
            isPasteCopyFile = false,
            isPasteMoveFile = false,
            hasNavigator = true
        )

        assertTrue(permission.read)
        assertFalse(permission.write)
    }

    @Test
    fun fileCardMenuPermissionOmitsContentEntryForDirectory() {
        val permission = resolveFileCardMenuPermission(
            basePermission = allMenuPermissions,
            file = file(path = "/folder", isDirectory = true),
            selectedFiles = emptyList(),
            isPasteCopyFile = false,
            isPasteMoveFile = false,
            hasNavigator = true
        )

        assertFalse(permission.read)
        assertFalse(permission.write)
    }

    @Test
    fun fileCardMenuPermissionOmitsContentEntryWithoutNavigator() {
        val permission = resolveFileCardMenuPermission(
            basePermission = allMenuPermissions,
            file = file(path = "/document.txt"),
            selectedFiles = emptyList(),
            isPasteCopyFile = false,
            isPasteMoveFile = false,
            hasNavigator = false
        )

        assertFalse(permission.read)
        assertFalse(permission.write)
    }

    @Test
    fun lightweightMenuAvailabilityMatchesResolvedPermission() {
        val basePermissions = listOf(
            DiskMenuPermission(),
            DiskMenuPermission(read = true),
            DiskMenuPermission(write = true),
            DiskMenuPermission(paste = true, copy = true, move = true),
            DiskMenuPermission(share = true),
            DiskMenuPermission(info = true),
            allMenuPermissions,
        )
        val files = listOf(
            file(path = "/file"),
            file(path = "/folder", isDirectory = true),
            file(path = "/shared-file", protocol = FileProtocol.Share),
        )

        for (basePermission in basePermissions) {
            for (item in files) {
                for (isFileChecked in listOf(false, true)) {
                    for (selectionDisallowsPaste in listOf(false, true)) {
                        for (isPasteCopyFile in listOf(false, true)) {
                            for (isPasteMoveFile in listOf(false, true)) {
                                for (hasNavigator in listOf(false, true)) {
                                    val expected = resolveFileCardMenuPermission(
                                        basePermission = basePermission,
                                        file = item,
                                        isFileChecked = isFileChecked,
                                        currentSelectionDisallowsPaste = selectionDisallowsPaste,
                                        isPasteCopyFile = isPasteCopyFile,
                                        isPasteMoveFile = isPasteMoveFile,
                                        hasNavigator = hasNavigator,
                                    ).hasAnyPermission()
                                    val actual = hasAnyFileCardMenuPermission(
                                        basePermission = basePermission,
                                        file = item,
                                        isFileChecked = isFileChecked,
                                        currentSelectionDisallowsPaste = selectionDisallowsPaste,
                                        isPasteCopyFile = isPasteCopyFile,
                                        isPasteMoveFile = isPasteMoveFile,
                                        hasNavigator = hasNavigator,
                                    )

                                    assertEquals(expected, actual)
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    private val allMenuPermissions = DiskMenuPermission(
        read = true,
        write = true,
        paste = true,
        copy = true,
        move = true,
        delete = true,
        rename = true,
        setting = true,
        favorite = true,
        share = true,
        info = true
    )

    private fun file(
        path: String,
        isDirectory: Boolean = false,
        protocol: FileProtocol = FileProtocol.Local,
    ) = FileSimpleInfo(
        name = path.substringAfterLast('/').ifEmpty { path },
        isDirectory = isDirectory,
        isHidden = false,
        path = path,
        mineType = "",
        size = 0,
        createdDate = 0,
        updatedDate = 0,
        protocol = protocol
    )
}
