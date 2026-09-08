package com.folderspan.extensions

import strings.AppStrings

import com.folderspan.data.file.FileFilterSort
import com.folderspan.data.file.FileFilterType
import com.folderspan.data.file.FileSimpleInfo
import com.folderspan.db.FileFilter
import kotlin.test.Test
import kotlin.test.assertEquals

class FileListFilterTest {
    @Test
    fun extensionIndexMatchesLinearLookupAndKeepsFirstDuplicate() {
        val imageFilter = fileFilters().first()
        val fallbackFilter = FileFilter(
            id = 3L,
            name = AppStrings.ui_test_file_list_filter_backup,
            type = FileFilterType.Misc,
            extensions = listOf(".png", ".bin"),
            icon = null,
            sort = 3L,
        )
        val filters = fileFilters() + fallbackFilter

        val index = filters.indexByExtension()

        assertEquals(filters.getFilterByExtension(".png"), index[".png"])
        assertEquals(imageFilter, index[".png"])
        assertEquals(fallbackFilter, index[".bin"])
        assertEquals(null, index[".unknown"])
    }

    @Test
    fun assignsFileFilterTypesBeforeRendering() {
        val files = sampleFiles().withFileFilterTypes(fileFilters().indexByExtension())

        assertEquals(FileFilterType.Folder, files.first { it.name == "assets" }.fileFilterType)
        assertEquals(FileFilterType.Image, files.first { it.name == ".secret.png" }.fileFilterType)
        assertEquals(FileFilterType.Text, files.first { it.name == "readme.md" }.fileFilterType)
    }

    @Test
    fun hiddenFilterCombinesWithFolderFilter() {
        val result = sampleFiles().filter(
            isHidden = true,
            filterFileExtensions = listOf(FileFilterType.Hidden, FileFilterType.Folder),
            sortType = FileFilterSort.NameAsc,
            filterFileTypes = fileFilters()
        )

        assertEquals(listOf("/project/.config"), result.map { item -> item.path })
    }

    @Test
    fun hiddenFilterCombinesWithFileTypeFilter() {
        val result = sampleFiles().filter(
            isHidden = true,
            filterFileExtensions = listOf(FileFilterType.Hidden, FileFilterType.Image),
            sortType = FileFilterSort.NameAsc,
            filterFileTypes = fileFilters()
        )

        assertEquals(listOf("/project/.secret.png"), result.map { item -> item.path })
    }

    @Test
    fun hiddenFilterCombinesWithFileFilter() {
        val result = sampleFiles().filter(
            isHidden = true,
            filterFileExtensions = listOf(FileFilterType.Hidden, FileFilterType.File),
            sortType = FileFilterSort.NameAsc,
            filterFileTypes = fileFilters()
        )

        assertEquals(listOf("/project/.notes.md", "/project/.secret.png"), result.map { item -> item.path })
    }

    @Test
    fun hiddenFilterIsIgnoredWhenHiddenFilesAreNotShown() {
        val result = sampleFiles().filter(
            isHidden = false,
            filterFileExtensions = listOf(FileFilterType.Hidden),
            sortType = FileFilterSort.NameAsc,
            filterFileTypes = fileFilters()
        )

        assertEquals(listOf("/project/assets", "/project/readme.md"), result.map { item -> item.path })
    }

    @Test
    fun fileFilterMatchesAllVisibleFiles() {
        val result = sampleFiles().filter(
            isHidden = false,
            filterFileExtensions = listOf(FileFilterType.File),
            sortType = FileFilterSort.NameAsc,
            filterFileTypes = fileFilters()
        )

        assertEquals(listOf("/project/readme.md"), result.map { item -> item.path })
    }

    private fun sampleFiles(): List<FileSimpleInfo> = listOf(
        file(path = "/project/assets", name = "assets", isDirectory = true, isHidden = false),
        file(path = "/project/.config", name = ".config", isDirectory = true, isHidden = true),
        file(path = "/project/readme.md", name = "readme.md", mineType = ".md", isHidden = false),
        file(path = "/project/.secret.png", name = ".secret.png", mineType = ".png", isHidden = true),
        file(path = "/project/.notes.md", name = ".notes.md", mineType = ".md", isHidden = true),
    )

    private fun file(
        path: String,
        name: String,
        isDirectory: Boolean = false,
        isHidden: Boolean = false,
        mineType: String = "",
    ): FileSimpleInfo = FileSimpleInfo(
        name = name,
        isDirectory = isDirectory,
        isHidden = isHidden,
        path = path,
        mineType = mineType,
        size = 0,
        createdDate = 0,
        updatedDate = 0,
    )

    private fun fileFilters(): List<FileFilter> = listOf(
        FileFilter(
            id = 1L,
            name = AppStrings.ui_pictures,
            type = FileFilterType.Image,
            extensions = listOf(".png", ".jpg"),
            icon = null,
            sort = 1L
        ),
        FileFilter(
            id = 2L,
            name = AppStrings.ui_documentation,
            type = FileFilterType.Text,
            extensions = listOf(".md"),
            icon = null,
            sort = 2L
        )
    )
}
