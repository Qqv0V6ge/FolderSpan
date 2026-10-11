package com.folderspan.service.file

import com.folderspan.exception.AuthorityException
import com.folderspan.extensions.parentPath
import com.folderspan.service.data.CopyPathControlAction
import com.folderspan.service.data.CopyPathControlRequest
import com.folderspan.service.data.CopyPathProgress
import com.folderspan.service.data.CopyPathRequest
import com.folderspan.ui.state.device.DeviceCertificateState
import com.folderspan.utils.FileAccessPermission
import com.folderspan.utils.FileUtils
import com.folderspan.utils.PathUtils
import com.folderspan.utils.SensitiveFileAccessPolicy
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.isActive
import strings.AppStrings

class DeviceFileCopyService(
    private val deviceCertificateState: DeviceCertificateState,
) {
    suspend fun copyPath(
        authToken: String,
        request: CopyPathRequest,
        onProgress: suspend (CopyPathProgress) -> Unit,
    ): Result<Boolean> {
        val authResult = ensureAuthorized(authToken)
        if (authResult.isFailure) return Result.failure(authResult.exceptionOrNull()!!)

        if (request.srcPath.isBlank() || request.destPath.isBlank()) {
            return Result.failure(IllegalArgumentException(AppStrings.ui_copy_path_cannot_be_empty))
        }
        SensitiveFileAccessPolicy.deniedException(request.srcPath)?.let { error ->
            return Result.failure(error)
        }
        SensitiveFileAccessPolicy.deniedException(request.destPath)?.let { error ->
            return Result.failure(error)
        }
        if (deviceCertificateState.checkPermission(FileAccessPermission.Allowed, authToken, request.srcPath, "read")) {
            return Result.failure(AuthorityException(AppStrings.ui_no_permission_to_read_the_path))
        }

        val separator = PathUtils.getPathSeparator()
        val destParentPath = request.destPath.parentPath(separator)
        if (deviceCertificateState.checkPermission(FileAccessPermission.Allowed, authToken, destParentPath, "write")) {
            return Result.failure(AuthorityException(AppStrings.ui_no_permission_to_write_to_that_path))
        }

        val sourceInfo = FileUtils.getFile(FileAccessPermission.Allowed, request.srcPath)
        if (sourceInfo.isFailure) {
            return Result.failure(sourceInfo.exceptionOrNull() ?: Exception(AppStrings.ui_the_file_does_not_exist))
        }
        ensureCopyTreeIsOrdinary(sourceInfo.getOrThrow(), request.destPath).exceptionOrNull()?.let { error ->
            return Result.failure(error)
        }

        var latestProgress = CopyPathProgress(path = request.destPath)

        suspend fun emitProgress(progress: CopyPathProgress) {
            latestProgress = progress
            onProgress(progress)
        }

        if (request.requestId.isBlank()) {
            return Result.failure(IllegalArgumentException(AppStrings.error_request_id_required))
        }
        if (!RemoteCopyControlRegistry.register(request.requestId, authToken)) {
            return Result.failure(IllegalArgumentException(AppStrings.error_copy_request_id_invalid_or_occupied))
        }

        val copyResult = try {
            sourceInfo.getOrThrow().copyTo(
                destPath = request.destPath,
                shouldContinue = {
                    currentCoroutineContext().isActive &&
                        RemoteCopyControlRegistry.awaitRunning(request.requestId) {
                            emitProgress(
                                CopyPathProgress(
                                    progressCur = latestProgress.progressCur,
                                    progressMax = latestProgress.progressMax,
                                    path = latestProgress.path.ifBlank { request.destPath },
                                    message = AppStrings.task_copy_paused_waiting
                                )
                            )
                        }
                },
                onProgress = { progress ->
                    emitProgress(progress)
                }
            ).fold(
                onSuccess = { copied ->
                    if (!copied) {
                        Result.failure(Exception(AppStrings.ui_copy_failed))
                    } else if (!currentCoroutineContext().isActive) {
                        Result.failure(Exception(AppStrings.message_task_cancelled))
                    } else {
                        emitProgress(
                            CopyPathProgress(
                                progressCur = latestProgress.progressCur,
                                progressMax = latestProgress.progressMax,
                                path = request.destPath,
                                message = AppStrings.task_copy_finalizing
                            )
                        )

                        Result.success(true)
                    }
                },
                onFailure = { error ->
                    Result.failure(error)
                }
            )
        } finally {
            RemoteCopyControlRegistry.remove(request.requestId)
        }

        emitProgress(
            CopyPathProgress(
                progressCur = latestProgress.progressCur,
                progressMax = latestProgress.progressMax,
                path = request.destPath,
                message = copyResult.exceptionOrNull()?.message
                    ?: if (copyResult.getOrDefault(false)) AppStrings.ui_copy_completed else AppStrings.ui_copy_failed,
                done = true,
                success = copyResult.isSuccess && copyResult.getOrDefault(false),
            )
        )

        return copyResult
    }

    suspend fun controlCopy(
        authToken: String,
        request: CopyPathControlRequest,
    ): Result<Boolean> {
        val authResult = ensureAuthorized(authToken)
        if (authResult.isFailure) return Result.failure(authResult.exceptionOrNull()!!)
        if (request.requestId.isBlank()) {
            return Result.failure(IllegalArgumentException(AppStrings.error_request_id_required))
        }
        val updated = when (request.action) {
            CopyPathControlAction.Pause -> RemoteCopyControlRegistry.update(
                request.requestId,
                RemoteCopyStatus.Paused,
                authToken,
            )
            CopyPathControlAction.Resume -> RemoteCopyControlRegistry.update(
                request.requestId,
                RemoteCopyStatus.Running,
                authToken,
            )
            CopyPathControlAction.Cancel -> RemoteCopyControlRegistry.update(
                request.requestId,
                RemoteCopyStatus.Cancelled,
                authToken,
            )
        }
        return Result.success(updated)
    }

    private fun ensureAuthorized(authToken: String): Result<Unit> {
        if (authToken.isBlank()) return Result.failure(AuthorityException(AppStrings.ui_auth_token_invalid))
        if (!deviceCertificateState.isTokenValid(authToken)) {
            return Result.failure(AuthorityException(AppStrings.ui_auth_token_invalid))
        }
        return Result.success(Unit)
    }

    private suspend fun ensureCopyTreeIsOrdinary(
        source: com.folderspan.data.file.FileSimpleInfo,
        destinationRoot: String,
    ): Result<Unit> = runCatching {
        if (!source.isDirectory) return@runCatching
        PathUtils.traverse(FileAccessPermission.Allowed, source.path).collect { batch ->
            batch.getOrThrow().forEach { entry ->
                if (!entry.isSymbolicLinkKnown || entry.isSymbolicLink ||
                    PathUtils.isSymbolicLink(FileAccessPermission.Allowed, entry.path)
                ) {
                    throw AuthorityException(AppStrings.error_symbolic_links_not_accessible)
                }
                SensitiveFileAccessPolicy.deniedException(entry.path)?.let { throw it }
                val destinationPath = entry.path.replaceFirst(source.path, destinationRoot)
                SensitiveFileAccessPolicy.deniedException(destinationPath)?.let { throw it }
            }
        }
    }
}
