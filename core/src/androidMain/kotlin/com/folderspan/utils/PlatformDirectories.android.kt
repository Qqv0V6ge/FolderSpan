package com.folderspan.utils

import strings.AppStrings

import android.os.Environment
import com.folderspan.androidContext

actual fun getPlatformDirectories(): List<PlatformDirectoryItem> {
    val ctx = androidContext()
    val items = mutableListOf<PlatformDirectoryItem>()
    val seen = mutableSetOf<String>()

    fun add(title: String, description: String, directory: String?) {
        val value = directory?.trim().orEmpty()
        if (value.isEmpty()) return
        if (!seen.add(value)) return
        items.add(PlatformDirectoryItem(title = title, description = description, directory = value))
    }

    add(AppStrings.ui_application_data_directory, AppStrings.ui_application_internal_file_directory, ctx.filesDir.absolutePath)
    add(AppStrings.ui_application_cache_directory, AppStrings.ui_application_internal_cache_directory, ctx.cacheDir.absolutePath)
    add(AppStrings.ui_apply_nobackup_directory, AppStrings.ui_application_file_directories_that_do_not_participate_system_backup, ctx.noBackupFilesDir.absolutePath)

    add(AppStrings.ui_external_file_directory, AppStrings.ui_application_external_file_directory, ctx.getExternalFilesDir(null)?.absolutePath)
    add(AppStrings.ui_external_cache_directory, AppStrings.ui_apply_external_cache_directory, ctx.externalCacheDir?.absolutePath)

    ctx.getExternalFilesDirs(null)
        .mapNotNull { item ->  item?.absolutePath }
        .filter { item ->  item != ctx.getExternalFilesDir(null)?.absolutePath }
        .forEachIndexed { index, path ->
            add(AppStrings.ui_external_file_directory_arg0.format(arg0 = (index + 2).toString()), AppStrings.ui_other_external_file_directories, path)
        }

    @Suppress("DEPRECATION")
    add(AppStrings.ui_shared_storage_root_directory, AppStrings.ui_main_external_storage_root_directory, Environment.getExternalStorageDirectory().absolutePath)
    @Suppress("DEPRECATION")
    add(
        AppStrings.ui_download_catalog,
        AppStrings.ui_system_public_download_directory,
        Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS).absolutePath
    )

    PathUtils.getRootPaths(FileAccessPermission.Allowed)
        .getOrNull()
        .orEmpty()
        .forEach { root ->
            add(AppStrings.ui_storage_root_directory, AppStrings.ui_accessible_storage_root_directory, root.path)
        }

    return items
}
