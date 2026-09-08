package com.folderspan.ui.state.file

import strings.AppStrings

import com.folderspan.data.file.FileInfo
import com.folderspan.data.file.FileProtocol
import com.folderspan.data.file.FileSimpleInfo
import com.folderspan.data.file.PathInfo
import com.folderspan.data.main.DiskBase
import com.folderspan.data.main.Local
import com.folderspan.data.main.device.Device
import com.folderspan.data.main.network.NetworkAccess
import com.folderspan.data.main.share.SYSTEM_SHARE_DESK_ID
import com.folderspan.data.main.share.Share
import com.folderspan.data.main.share.ShareProtocol
import com.folderspan.exception.AuthorityException
import com.folderspan.exception.EmptyDataException
import com.folderspan.extensions.*
import com.folderspan.service.operation.TraversalScanProgress
import com.folderspan.utils.FileAccessPermission
import com.folderspan.utils.FileUtils
import com.folderspan.utils.PathUtils
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext

internal suspend fun readSystemShareFiles(
    readPlatformFiles: suspend () -> Result<List<FileSimpleInfo>> = {
        "content://".getFileAndFolder(FileAccessPermission.Allowed)
    },
): Result<List<FileSimpleInfo>> {
    val urls = ClipboardUrlShareFiles.list()
    val platformFiles = readPlatformFiles()
    return if (urls.isEmpty()) platformFiles
    else Result.success(platformFiles.getOrDefault(emptyList()) + urls)
}

internal class FileStateEntryReader(
    private val currentDesk: () -> DiskBase,
    private val directoryCollector: FileStateDirectoryCollector,
) {
    private fun buildListRequestBatchId(desk: DiskBase): String {
        return "file-list:${buildDiskPathKey(desk)}"
    }

    suspend fun cancelListRequestsForDesk(desk: DiskBase) {
        val batchId = buildListRequestBatchId(desk)
        when (desk) {
            is Device -> {
                desk.host.values.forEach { manager ->
                    runCatching { manager.cancelBatch(batchId, AppStrings.ui_cancel_list_request) }
                }
            }

            is NetworkAccess -> {
                runCatching { desk.cancelBatch(batchId, AppStrings.ui_cancel_list_request) }
            }
        }
    }

    suspend fun getFileAndFolder(path: String): Result<List<FileSimpleInfo>> =
        getFileAndFolder(currentDesk(), path)

    suspend fun getFileAndFolder(desk: DiskBase, path: String): Result<List<FileSimpleInfo>> {
        return withContext(Dispatchers.Default) {
            when (desk) {
                is Local -> path.getFileAndFolder(FileAccessPermission.Allowed)
                is Device -> desk.paths.getList(path, batchId = buildListRequestBatchId(desk))
                is Share -> {
                    if (desk.protocol == ShareProtocol.System) {
                        when {
                            path == "/" -> readSystemShareFiles()
                            path.startsWith("content://") -> path.getFileAndFolder(FileAccessPermission.Allowed)
                            else -> Result.failure(AuthorityException(AppStrings.ui_the_system_does_not_support_accessing_the_specified_path))
                        }
                    } else {
                        desk.getFileList(path)
                    }
                }

                is NetworkAccess -> desk.getList(path, batchId = buildListRequestBatchId(desk))
                else -> Result.failure(EmptyDataException())
            }
        }
    }

    suspend fun getRootPaths(): List<PathInfo> {
        return withContext(Dispatchers.Default) {
            when (val desk = currentDesk()) {
                is Local -> PathUtils.getRootPaths(FileAccessPermission.Allowed).getOrDefault(listOf())
                is Device -> desk.paths.getRootPaths().getOrDefault(listOf())
                is Share -> listOf(PathInfo("/", 0L, 0L))
                is NetworkAccess -> desk.getRootPaths()
                else -> listOf()
            }
        }
    }

    suspend fun getFileInfo(path: String): Result<FileInfo> {
        return withContext(Dispatchers.Default) {
            when (val desk = currentDesk()) {
                is Local -> FileUtils.getFileInfo(FileAccessPermission.Allowed, path)
                is Device -> desk.files.getInfo(path)
                is Share -> if (desk.protocol == ShareProtocol.System) {
                    ClipboardUrlShareFiles.find(path)?.let { file ->
                        return@withContext Result.success(
                            FileInfo.pathFileInfo(path).copy(
                                name = file.name,
                                isDirectory = file.isDirectory,
                                size = file.size,
                                mineType = file.mineType,
                                createdDate = file.createdDate,
                                updatedDate = file.updatedDate,
                                protocol = file.protocol,
                                protocolId = file.protocolId,
                            )
                        )
                    }
                    when {
                        path == "/" -> Result.success(
                            FileInfo.pathFileInfo("/").copy(
                                protocol = FileProtocol.Share,
                                protocolId = SYSTEM_SHARE_DESK_ID,
                            )
                        )

                        path.startsWith("content://") -> FileUtils.getFileInfo(FileAccessPermission.Allowed, path)
                        else -> Result.failure(AuthorityException(AppStrings.ui_the_system_does_not_support_accessing_the_specified_path))
                    }
                } else {
                    Result.failure(Exception(AppStrings.ui_failed_to_retrieve))
                }

                else -> Result.failure(Exception(AppStrings.ui_failed_to_retrieve))
            }
        }
    }

    fun traversePath(path: String): Flow<Result<List<FileSimpleInfo>>> {
        return flow {
            val root = when (val desk = currentDesk()) {
                is Local -> FileSimpleInfo.pathFileSimpleInfo(path).withCopy(
                    protocol = FileProtocol.Local,
                    protocolId = "",
                )

                is Device -> FileSimpleInfo.pathFileSimpleInfo(path).withCopy(
                    protocol = FileProtocol.Device,
                    protocolId = desk.id,
                )

                is Share -> FileSimpleInfo.pathFileSimpleInfo(path).withCopy(
                    protocol = FileProtocol.Share,
                    protocolId = desk.id,
                )

                is NetworkAccess -> FileSimpleInfo.pathFileSimpleInfo(path).withCopy(
                    protocol = FileProtocol.Network,
                    protocolId = desk.protocolId,
                )

                else -> {
                    emit(Result.failure(Exception(AppStrings.ui_failed_to_retrieve)))
                    return@flow
                }
            }

            var emitted = false
            collectDirectoryEntries(
                root = root,
                ensureRunning = { currentCoroutineContext().ensureActive() },
                onEntriesDiscovered = { entries ->
                    emitted = true
                    emit(Result.success(entries))
                },
            )
            if (!emitted) {
                emit(Result.success(emptyList()))
            }
        }.catch { error ->
            if (error is CancellationException) throw error
            emit(Result.failure(error))
        }.flowOn(Dispatchers.Default)
    }

    suspend fun getFile(path: String): Result<FileSimpleInfo> = getFile(currentDesk(), path)

    suspend fun getFile(desk: DiskBase, path: String): Result<FileSimpleInfo> {
        return withContext(Dispatchers.Default) {
            when (desk) {
                is Local -> FileUtils.getFile(FileAccessPermission.Allowed, path)
                is Device -> desk.files.get(path)
                is Share -> if (desk.protocol == ShareProtocol.System) {
                    ClipboardUrlShareFiles.find(path)?.let { return@withContext Result.success(it) }
                    when {
                        path == "/" -> Result.success(
                            FileSimpleInfo.pathFileSimpleInfo("/").withCopy(
                                protocol = FileProtocol.Share,
                                protocolId = SYSTEM_SHARE_DESK_ID,
                            )
                        )

                        path.startsWith("content://") -> FileUtils.getFile(FileAccessPermission.Allowed, path)
                        else -> Result.failure(AuthorityException(AppStrings.ui_the_system_does_not_support_accessing_the_specified_path))
                    }
                } else {
                    Result.failure(Exception(AppStrings.ui_failed_to_retrieve))
                }

                is NetworkAccess -> desk.getFile(path)
                else -> Result.failure(Exception(AppStrings.ui_failed_to_retrieve))
            }
        }
    }

    suspend fun collectDirectoryEntries(
        root: FileSimpleInfo,
        ensureRunning: suspend () -> Unit = {},
        requestBatchId: String? = null,
        onScanProgress: suspend (TraversalScanProgress) -> Unit = {},
        onEntriesDiscovered: suspend (List<FileSimpleInfo>) -> Unit = {},
        rejectSymbolicLinkEntries: Boolean = false,
        onRejectedEntry: suspend (FileSimpleInfo, Throwable) -> Unit = { _, _ -> },
    ): List<FileSimpleInfo> = directoryCollector.collectDirectoryEntries(
        root = root,
        ensureRunning = ensureRunning,
        requestBatchId = requestBatchId,
        onScanProgress = onScanProgress,
        onEntriesDiscovered = onEntriesDiscovered,
        rejectSymbolicLinkEntries = rejectSymbolicLinkEntries,
        onRejectedEntry = onRejectedEntry,
    )
}
