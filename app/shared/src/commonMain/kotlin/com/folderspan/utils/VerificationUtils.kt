package com.folderspan.utils

import com.folderspan.data.file.FileSimpleInfo
import com.folderspan.data.file.displayName
import com.folderspan.db.FileFilter
import strings.AppStrings

object VerificationUtils {
    fun folder(text: String, fileInfos: List<FileSimpleInfo>, ignoreName: List<String> = emptyList()): Pair<Boolean, String> {
        val nameRegex = "[\\\\/:*?\"<>|]".toRegex()
        val filter = fileInfos.filter { item -> !ignoreName.contains(item.name) && item.name == text }
        return if (filter.isNotEmpty()) {
            Pair(true, AppStrings.ui_name_arg0_already_exists_try_another.format(arg0 = text))
        } else if (nameRegex.find(text) != null) {
            Pair(true, AppStrings.ui_name_cannot_contain_reserved_characters)
        } else {
            Pair(false, "")
        }
    }

    fun file(text: String, fileInfos: List<FileSimpleInfo>, ignoreName: List<String> = emptyList()): Pair<Boolean, String> {
        val nameRegex = "[\\\\/:*?\"<>|]".toRegex()
        val filter = fileInfos.filter { item -> !ignoreName.contains(item.name) && item.name == text }
        return if (text.isEmpty()) {
            Pair(true, AppStrings.ui_name_cannot_empty)
        } else if (filter.isNotEmpty()) {
            Pair(true, AppStrings.ui_name_arg0_already_exists_try_another.format(arg0 = text))
        } else if (nameRegex.find(text) != null) {
            Pair(true, AppStrings.ui_name_cannot_contain_reserved_characters)
        } else {
            Pair(false, "")
        }
    }

    fun filterType(text: String, filterFileTypes: List<FileFilter>): Pair<Boolean, String> {
        val filter = filterFileTypes.filter { item ->
            item.displayName() == text || item.name == text
        }
        return if (filter.isNotEmpty()) {
            Pair(true, AppStrings.ui_name_arg0_already_exists_try_another.format(arg0 = text))
        } else {
            Pair(false, "")
        }
    }

    fun filterExtensions(text: String, extensions: List<String>): Pair<Boolean, String> {
        val filter = extensions.filter { item ->  item.replaceFirst(".", "") == text.replaceFirst(".", "") }
        return if (filter.isNotEmpty()) {
            Pair(true, AppStrings.ui_name_arg0_already_exists_try_another.format(arg0 = text))
        } else {
            Pair(false, "")
        }
    }
}
