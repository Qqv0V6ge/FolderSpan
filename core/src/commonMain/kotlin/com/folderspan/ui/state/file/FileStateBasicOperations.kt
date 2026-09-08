package com.folderspan.ui.state.file

import com.folderspan.data.main.DiskBase
import com.folderspan.data.main.Local
import com.folderspan.data.main.device.Device
import com.folderspan.data.main.network.NetworkAccess
import com.folderspan.service.data.RenameInfo
import com.folderspan.utils.FileAccessPermission
import com.folderspan.utils.FileUtils
import com.folderspan.utils.PathUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import strings.AppStrings

internal class FileStateBasicOperations(
    private val currentDesk: () -> DiskBase,
    private val updateFileAndFolder: suspend () -> Unit,
) {
    suspend fun rename(path: String, oldName: String, newName: String): Result<Boolean> {
        return withContext(Dispatchers.Default) {
            val result = when (val desk = currentDesk()) {
                is Local -> FileUtils.rename(FileAccessPermission.Allowed, path, oldName, newName)
                is Device -> {
                    val renameResults = desk.files.rename(listOf(RenameInfo(path, oldName, newName)))
                    renameResults.fold(
                        onSuccess = { results ->
                            results.firstOrNull() ?: Result.failure(Exception(AppStrings.ui_rename_failed))
                        },
                        onFailure = { throwable -> Result.failure(throwable) },
                    )
                }

                is NetworkAccess -> desk.rename(path, oldName, newName)

                else -> Result.failure(Exception(AppStrings.ui_failed_to_modify))
            }
            if (result.getOrNull() == true) {
                updateFileAndFolder()
            }
            result
        }
    }

    suspend fun createFolder(path: String, name: String): Result<Boolean> {
        return withContext(Dispatchers.Default) {
            when (val desk = currentDesk()) {
                is Local -> FileUtils.createFolder(
                    FileAccessPermission.Allowed,
                    "$path${PathUtils.getPathSeparator()}$name",
                )
                is Device -> {
                    val createResults = desk.files.createFolders(listOf("$path${desk.pathSeparator}$name"))
                    createResults.fold(
                        onSuccess = { results ->
                            results.firstOrNull() ?: Result.failure(Exception(AppStrings.ui_create_folder_failed))
                        },
                        onFailure = { throwable -> Result.failure(throwable) },
                    )
                }

                is NetworkAccess -> desk.createFolder(path, name)

                else -> Result.failure(Exception(AppStrings.ui_create_folder_failed))
            }
        }
    }

    suspend fun createFile(path: String, name: String): Result<Boolean> {
        return withContext(Dispatchers.Default) {
            when (val desk = currentDesk()) {
                is Local -> FileUtils.createFile(
                    FileAccessPermission.Allowed,
                    "$path${PathUtils.getPathSeparator()}$name",
                )
                is Device -> {
                    val createResults = desk.files.createFiles(listOf("$path${desk.pathSeparator}$name"))
                    createResults.fold(
                        onSuccess = { results ->
                            results.firstOrNull() ?: Result.failure(Exception(AppStrings.ui_create_file_failure))
                        },
                        onFailure = { throwable -> Result.failure(throwable) },
                    )
                }

                is NetworkAccess -> desk.createFile(path, name)

                else -> Result.failure(Exception(AppStrings.ui_create_file_failure))
            }
        }
    }
}
