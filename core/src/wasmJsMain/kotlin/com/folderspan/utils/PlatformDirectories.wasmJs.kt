package com.folderspan.utils

import strings.AppStrings

actual fun getPlatformDirectories(): List<PlatformDirectoryItem> {
    val items = mutableListOf<PlatformDirectoryItem>()
    val seen = mutableSetOf<String>()

    fun add(title: String, description: String, directory: String?) {
        val value = directory?.trim().orEmpty()
        if (value.isEmpty()) return
        if (!seen.add(value)) return
        items.add(PlatformDirectoryItem(title = title, description = description, directory = value))
    }

    add(AppStrings.ui_application_catalog, AppStrings.ui_web_version_virtual_application_directory, PathUtils.getAppPath())
    add(AppStrings.ui_home_directory_label, AppStrings.ui_web_version_virtual_home_directory, PathUtils.getHomePath())
    add(AppStrings.ui_cache_directory_label, AppStrings.ui_web_version_virtual_cache_directory, PathUtils.getCachePath())

    PathUtils.getRootPaths(FileAccessPermission.Allowed)
        .getOrNull()
        .orEmpty()
        .forEach { root ->
            add(AppStrings.ui_root_directory, AppStrings.ui_web_version_virtual_root_directory, root.path)
        }

    return items
}
