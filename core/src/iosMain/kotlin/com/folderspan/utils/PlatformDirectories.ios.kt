package com.folderspan.utils

import strings.AppStrings

import platform.Foundation.NSApplicationSupportDirectory
import platform.Foundation.NSCachesDirectory
import platform.Foundation.NSDocumentDirectory
import platform.Foundation.NSHomeDirectory
import platform.Foundation.NSLibraryDirectory
import platform.Foundation.NSSearchPathForDirectoriesInDomains
import platform.Foundation.NSTemporaryDirectory
import platform.Foundation.NSUserDomainMask

actual fun getPlatformDirectories(): List<PlatformDirectoryItem> {
    val items = mutableListOf<PlatformDirectoryItem>()
    val seen = mutableSetOf<String>()

    fun add(title: String, description: String, directory: String?) {
        val value = directory?.trim().orEmpty()
        if (value.isEmpty()) return
        if (!seen.add(value)) return
        items.add(PlatformDirectoryItem(title = title, description = description, directory = value))
    }

    fun searchPath(dir: platform.Foundation.NSSearchPathDirectory): String? {
        val paths = NSSearchPathForDirectoriesInDomains(dir, NSUserDomainMask, true)
        return paths.firstOrNull()?.toString()
    }

    add(AppStrings.ui_application_sandbox_directory, AppStrings.ui_application_sandbox_root_directory, NSHomeDirectory())
    add(AppStrings.ui_document_directory, AppStrings.ui_application_documentation_directory, searchPath(NSDocumentDirectory))
    add(AppStrings.ui_cache_directory_label, AppStrings.ui_application_cache_directory, searchPath(NSCachesDirectory))
    add(AppStrings.ui_application_support_catalog, AppStrings.ui_application_support_catalog, searchPath(NSApplicationSupportDirectory))
    add(AppStrings.ui_library_directory, AppStrings.ui_application_library_directory, searchPath(NSLibraryDirectory))
    add(AppStrings.ui_temporary_directory, AppStrings.ui_system_temporary_directory, NSTemporaryDirectory())

    PathUtils.getRootPaths(FileAccessPermission.Allowed)
        .getOrNull()
        .orEmpty()
        .forEach { root ->
            add(AppStrings.ui_root_directory, AppStrings.ui_root_directory_accessible, root.path)
        }

    return items
}
