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

    add(AppStrings.ui_user_home_directory, AppStrings.ui_user_home_directory, System.getProperty("user.home"))
    add(AppStrings.ui_working_directory, AppStrings.ui_apply_current_working_directory, System.getProperty("user.dir"))
    add(AppStrings.ui_temporary_directory, AppStrings.ui_system_temporary_directory, System.getProperty("java.io.tmpdir"))

    add(AppStrings.ui_application_catalog, AppStrings.ui_application_directory, PathUtils.getAppPath())
    add(AppStrings.ui_home_directory_label, AppStrings.ui_user_home_directory, PathUtils.getHomePath())
    add(AppStrings.ui_cache_directory, AppStrings.ui_cache_directory_label, PathUtils.getCachePath())

    PathUtils.getRootPaths(FileAccessPermission.Allowed)
        .getOrNull()
        .orEmpty()
        .forEach { root ->
            add(AppStrings.ui_root_path, AppStrings.ui_file_system_root_directory, root.path)
        }

    return items
}
