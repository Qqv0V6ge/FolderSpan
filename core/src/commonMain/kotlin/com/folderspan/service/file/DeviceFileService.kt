package com.folderspan.service.file

import com.folderspan.createSettings
import com.folderspan.data.file.FileInfo
import com.folderspan.data.file.FileProtocol
import com.folderspan.data.file.FileSimpleInfo
import com.folderspan.exception.AuthorityException
import com.folderspan.extensions.parentPath
import com.folderspan.service.data.*
import com.folderspan.service.http.client.HttpRouteClientManager.Companion.MAX_LENGTH
import com.folderspan.service.operation.*
import com.folderspan.ui.state.device.DeviceCertificateState
import com.folderspan.utils.FileAccessPermission
import com.folderspan.utils.FileUtils
import com.folderspan.utils.LogKit
import com.folderspan.utils.PathUtils
import com.folderspan.utils.SettingsUtils
import com.folderspan.utils.SensitiveFileAccessPolicy
import com.folderspan.utils.isWriteRangeWithinFile
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import strings.AppStrings

data class PreparedWriteBytesRequest(
    val path: String,
    val fileSize: Long,
    val blockOffset: Long,
    val blockLength: Long,
)

data class PreparedReadBytesRequest(
    val path: String,
    val startOffset: Long,
    val endOffset: Long,
    val requestSize: Long,
)

class DeviceFileService(
    private val deviceCertificateState: DeviceCertificateState,
    private val maxByteRangeLength: Int = MAX_LENGTH,
    private val localDeviceIdProvider: () -> String = {
        createSettings().getString(SettingsUtils.KEY_DEVICE_ID, "")
    },
) {
    internal constructor(
        deviceCertificateState: DeviceCertificateState,
        maxByteRangeLength: Int = MAX_LENGTH,
        folderCreateParallelismProvider: () -> OperationParallelismConfig,
        folderCreateRuntimeMaxParallelismProvider: () -> Int,
        createFolder: suspend (String) -> Result<Boolean>,
        localDeviceIdProvider: () -> String = {
            createSettings().getString(SettingsUtils.KEY_DEVICE_ID, "")
        },
    ) : this(
        deviceCertificateState = deviceCertificateState,
        maxByteRangeLength = maxByteRangeLength,
        localDeviceIdProvider = localDeviceIdProvider,
    ) {
        this.folderCreateParallelismProvider = folderCreateParallelismProvider
        this.folderCreateRuntimeMaxParallelismProvider = folderCreateRuntimeMaxParallelismProvider
        this.createFolder = createFolder
    }

    private var folderCreateParallelismProvider: () -> OperationParallelismConfig = {
        resolveOperationParallelism(TraversalEndpointKind.Local)
    }
    private var folderCreateRuntimeMaxParallelismProvider: () -> Int = {
        resolveOperationRuntimeMaxParallelism(TraversalEndpointKind.Local)
    }
    private var createFolder: suspend (String) -> Result<Boolean> = { path ->
        FileUtils.createFolder(FileAccessPermission.Allowed, path)
    }

    suspend fun renames(authToken: String, request: RenameRequest): Result<List<Result<Boolean>>> {
        val authResult = ensureAuthorized(authToken)
        if (authResult.isFailure) return Result.failure(authResult.exceptionOrNull()!!)
        if (request.renameInfos.isEmpty()) {
            return Result.failure(IllegalArgumentException(AppStrings.ui_rename_information_cannot_be_empty))
        }

        val results = request.renameInfos.map { renameInfo ->
            if (renameInfo.hasEmptyField()) {
                return@map Result.failure(IllegalArgumentException(AppStrings.ui_parameter_renaming_is_invalid))
            }
            if (!renameInfo.oldName.isSafeRenameFileName() || !renameInfo.newName.isSafeRenameFileName()) {
                return@map Result.failure(IllegalArgumentException(AppStrings.ui_rename_file_name_invalid))
            }
            val separator = PathUtils.getPathSeparator()
            val sourcePath = "${renameInfo.path}$separator${renameInfo.oldName}"
            val targetPath = "${renameInfo.path}$separator${renameInfo.newName}"
            SensitiveFileAccessPolicy.deniedException(sourcePath)?.let { error ->
                return@map Result.failure(error)
            }
            SensitiveFileAccessPolicy.deniedException(targetPath)?.let { error ->
                return@map Result.failure(error)
            }
            if (deviceCertificateState.checkPermission(
                    FileAccessPermission.Allowed,
                    authToken,
                    sourcePath,
                    "rename"
                )
            ) {
                return@map Result.failure(AuthorityException(AppStrings.message_task_permission_denied))
            }
            withContext(Dispatchers.Default) {
                FileUtils.rename(FileAccessPermission.Allowed, renameInfo.path, renameInfo.oldName, renameInfo.newName)
            }
        }
        return Result.success(results)
    }

    suspend fun createFolders(authToken: String, request: CreateFolderRequest): Result<List<Result<Boolean>>> {
        val authResult = ensureAuthorized(authToken)
        if (authResult.isFailure) return Result.failure(authResult.exceptionOrNull()!!)
        if (request.paths.isEmpty()) {
            return Result.failure(IllegalArgumentException(AppStrings.ui_folder_name_cannot_be_empty))
        }

        val separator = PathUtils.getPathSeparator()
        val results = MutableList<Result<Boolean>?>(request.paths.size) { null }
        val createRequests = mutableListOf<IndexedFolderCreateRequest>()
        request.paths.forEachIndexed { index, path ->
            val parentPath = path.parentPath(separator)
            val protectedError = SensitiveFileAccessPolicy.deniedException(path)
            if (protectedError != null) {
                results[index] = Result.failure(protectedError)
            } else if (deviceCertificateState.checkPermission(FileAccessPermission.Allowed, authToken, parentPath, "write")) {
                results[index] = Result.failure(AuthorityException(AppStrings.message_task_permission_denied))
            } else {
                createRequests += IndexedFolderCreateRequest(index, path)
            }
        }
        val resultMutex = Mutex()
        processItemsAdaptive(
            items = createRequests,
            config = folderCreateParallelismProvider(),
            dynamicMaxParallelismProvider = folderCreateRuntimeMaxParallelismProvider,
        ) { requestItem ->
            val result = try {
                createFolder(requestItem.path)
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (error: Throwable) {
                Result.failure(error)
            }
            resultMutex.withLock {
                results[requestItem.index] = result
            }
        }
        return Result.success(
            results.map { result ->
                result ?: Result.failure(IllegalStateException(AppStrings.ui_create_folder_not_executed))
            }
        )
    }

    suspend fun createFiles(authToken: String, request: CreateFileRequest): Result<List<Result<Boolean>>> {
        val authResult = ensureAuthorized(authToken)
        if (authResult.isFailure) return Result.failure(authResult.exceptionOrNull()!!)
        if (request.paths.isEmpty()) {
            return Result.failure(IllegalArgumentException(AppStrings.error_device_file_name_empty))
        }

        val separator = PathUtils.getPathSeparator()
        val results = request.paths.map { path ->
            val parentPath = path.parentPath(separator)
            SensitiveFileAccessPolicy.deniedException(path)?.let { error ->
                return@map Result.failure(error)
            }
            if (deviceCertificateState.checkPermission(FileAccessPermission.Allowed, authToken, parentPath, "write")) {
                return@map Result.failure(AuthorityException(AppStrings.message_task_permission_denied))
            }
            withContext(Dispatchers.Default) {
                FileUtils.createFile(FileAccessPermission.Allowed, path)
            }
        }
        return Result.success(results)
    }

    suspend fun deletes(authToken: String, request: DeleteRequest): Result<List<Result<Boolean>>> {
        val authResult = ensureAuthorized(authToken)
        if (authResult.isFailure) return Result.failure(authResult.exceptionOrNull()!!)
        if (request.paths.isEmpty()) {
            return Result.failure(IllegalArgumentException(AppStrings.error_delete_path_empty))
        }

        val results = request.paths.map { path ->
            SensitiveFileAccessPolicy.deniedException(path)?.let { error ->
                return@map Result.failure(error)
            }
            if (deviceCertificateState.checkPermission(FileAccessPermission.Allowed, authToken, path, "remove")) {
                return@map Result.failure(AuthorityException(AppStrings.message_task_permission_denied))
            }
            withContext(Dispatchers.Default) {
                FileUtils.deleteFile(FileAccessPermission.Allowed, path)
            }
        }
        return Result.success(results)
    }

    suspend fun writeBytes(authToken: String, request: WriteBytesRequest): Result<Boolean> {
        val prepared = prepareWriteBytes(authToken, request.toStreamRequest()).getOrElse {
            return Result.failure(it)
        }
        if (request.byteArray.size.toLong() != prepared.blockLength) {
            return Result.failure(IllegalArgumentException(AppStrings.error_data_size_declared_block_length_mismatch))
        }
        return FileUtils.writeBytes(FileAccessPermission.Allowed,
            prepared.path,
            prepared.fileSize,
            request.byteArray,
            prepared.blockOffset
        )
    }

    suspend fun prepareWriteBytes(
        authToken: String,
        request: WriteBytesStreamRequest,
        maxBlockLength: Long = maxByteRangeLength.toLong(),
    ): Result<PreparedWriteBytesRequest> {
        val authResult = ensureAuthorized(authToken)
        if (authResult.isFailure) return Result.failure(authResult.exceptionOrNull()!!)

        val resolvedFileSize = request.resolveFileSize().getOrElse { return Result.failure(it) }
        SensitiveFileAccessPolicy.deniedException(request.path)?.let { error ->
            return Result.failure(error)
        }
        if (deviceCertificateState.checkPermission(FileAccessPermission.Allowed, authToken, request.path, "write")) {
            return Result.failure(AuthorityException(AppStrings.ui_no_permission_to_write_to_that_path))
        }
        if (request.blockIndex < 0L || request.blockLength < 0L || request.path.isEmpty()) {
            return Result.failure(IllegalArgumentException(AppStrings.ui_write_the_parameter_invalid))
        }
        val maxBlockSize = maxBlockLength.coerceAtLeast(0L)
        if (request.blockLength > maxBlockSize) {
            return Result.failure(IllegalArgumentException(AppStrings.ui_single_write_operation_for_large_data))
        }
        val blockOffset = request.resolveBlockStartOffset().getOrElse {
            return Result.failure(it)
        }
        if (!isWriteRangeWithinFile(resolvedFileSize, blockOffset, request.blockLength)) {
            return Result.failure(IllegalArgumentException(AppStrings.error_write_block_range_exceeds_file))
        }
        return Result.success(
            PreparedWriteBytesRequest(
                path = request.path,
                fileSize = resolvedFileSize,
                blockOffset = blockOffset,
                blockLength = request.blockLength,
            )
        )
    }

    suspend fun readBytes(authToken: String, request: ReadBytesRequest): Result<ByteArray> {
        val prepared = prepareReadBytes(authToken, request).getOrElse {
            return Result.failure(it)
        }
        return withContext(Dispatchers.Default) {
            FileUtils.readFileRange(FileAccessPermission.Allowed, prepared.path, prepared.startOffset, prepared.endOffset)
        }.mapCatching { data ->
            if (data.size.toLong() != prepared.requestSize) {
                throw IllegalStateException(AppStrings.ui_length_of_the_read_data_does_not_match_the_request_range)
            }
            data
        }
    }

    suspend fun prepareReadBytes(
        authToken: String,
        request: ReadBytesRequest,
        maxRangeLength: Long = maxByteRangeLength.toLong(),
    ): Result<PreparedReadBytesRequest> {
        val authResult = ensureAuthorized(authToken)
        if (authResult.isFailure) return Result.failure(authResult.exceptionOrNull()!!)

        SensitiveFileAccessPolicy.deniedException(request.path)?.let { error ->
            return Result.failure(error)
        }
        if (deviceCertificateState.checkPermission(FileAccessPermission.Allowed, authToken, request.path, "read")) {
            return Result.failure(AuthorityException(AppStrings.ui_no_permission_to_read_the_path))
        }
        if (request.startOffset < 0L || request.endOffset < 0L || request.path.isEmpty() || request.startOffset > request.endOffset) {
            return Result.failure(IllegalArgumentException(AppStrings.ui_read_the_parameter_invalid))
        }
        val maxBlockSize = maxRangeLength.coerceAtLeast(0L)
        val requestSize = request.endOffset - request.startOffset
        if (requestSize > maxBlockSize) {
            return Result.failure(IllegalArgumentException(AppStrings.ui_single_read_of_large_data))
        }
        val fileInfo = withContext(Dispatchers.Default) {
            FileUtils.getFile(FileAccessPermission.Allowed, request.path)
        }.getOrElse { error ->
            return Result.failure(error)
        }
        if (fileInfo.isDirectory) {
            return Result.failure(IllegalArgumentException(AppStrings.ui_do_not_support_directory_reading))
        }
        if (request.endOffset > fileInfo.size) {
            return Result.failure(IllegalArgumentException(AppStrings.ui_read_the_range_beyond_the_file_size))
        }
        return Result.success(
            PreparedReadBytesRequest(
                path = request.path,
                startOffset = request.startOffset,
                endOffset = request.endOffset,
                requestSize = requestSize,
            )
        )
    }

    suspend fun prepareStreamFile(
        authToken: String,
        request: DeviceStreamFileRequest,
        maxRangeLength: Long,
    ): Result<PreparedReadBytesRequest> = prepareReadBytes(
        authToken = authToken,
        request = ReadBytesRequest(
            path = request.path,
            startOffset = request.startOffset,
            endOffset = request.endOffset,
        ),
        maxRangeLength = maxRangeLength,
    )

    suspend fun getFileByPath(authToken: String, request: GetFileByPathRequest): Result<FileSimpleInfo> {
        return getFileSimpleInfo(
            authToken = authToken,
            path = request.path,
            load = { FileUtils.getFile(FileAccessPermission.Allowed, request.path) }
        )
    }

    suspend fun getFileByPathAndName(authToken: String, request: GetFileByPathAndNameRequest): Result<FileSimpleInfo> {
        val fullPath = "${request.path}${PathUtils.getPathSeparator()}${request.name}"
        return getFileSimpleInfo(
            authToken = authToken,
            path = fullPath,
            validate = {
                if (request.path.isEmpty() || request.name.isEmpty()) {
                    Result.failure(IllegalArgumentException(AppStrings.ui_paths_and_file_names_cannot_be_empty))
                } else {
                    Result.success(Unit)
                }
            },
            load = { FileUtils.getFile(FileAccessPermission.Allowed, request.path, request.name) }
        )
    }

    suspend fun getFileInfoByPath(authToken: String, request: GetFileByPathRequest): Result<FileInfo> {
        return getFileInfo(
            authToken = authToken,
            path = request.path,
            load = { FileUtils.getFileInfo(FileAccessPermission.Allowed, request.path) }
        )
    }

    suspend fun getFileInfoByPathAndName(authToken: String, request: GetFileByPathAndNameRequest): Result<FileInfo> {
        val fullPath = "${request.path}${PathUtils.getPathSeparator()}${request.name}"
        return getFileInfo(
            authToken = authToken,
            path = fullPath,
            validate = {
                if (request.path.isEmpty() || request.name.isEmpty()) {
                    Result.failure(IllegalArgumentException(AppStrings.ui_paths_and_file_names_cannot_be_empty))
                } else {
                    Result.success(Unit)
                }
            },
            load = { FileUtils.getFileInfo(FileAccessPermission.Allowed, fullPath) }
        )
    }

    suspend fun readFileLines(authToken: String, request: ReadFileLinesRequest): Result<List<String>> {
        val authResult = ensureAuthorized(authToken)
        if (authResult.isFailure) return Result.failure(authResult.exceptionOrNull()!!)
        SensitiveFileAccessPolicy.deniedException(request.path)?.let { error ->
            return Result.failure(error)
        }
        if (deviceCertificateState.checkPermission(FileAccessPermission.Allowed, authToken, request.path, "read")) {
            return Result.failure(AuthorityException(AppStrings.ui_no_permission_to_read_the_file))
        }
        if (request.path.isEmpty()) {
            return Result.failure(IllegalArgumentException(AppStrings.ui_path_empty))
        }
        return withContext(Dispatchers.Default) {
            runCatching { FileUtils.readFileLines(FileAccessPermission.Allowed, request.path) }
        }.onFailure { error ->
            LogKit.e(AppStrings.ui_reading_file_line_failed_arg0.format(arg0 = (error.message).toString()), error)
        }
    }

    suspend fun appendToFile(authToken: String, request: AppendToFileRequest): Result<Boolean> {
        val authResult = ensureAuthorized(authToken)
        if (authResult.isFailure) return Result.failure(authResult.exceptionOrNull()!!)
        SensitiveFileAccessPolicy.deniedException(request.path)?.let { error ->
            return Result.failure(error)
        }
        if (deviceCertificateState.checkPermission(FileAccessPermission.Allowed, authToken, request.path, "write")) {
            return Result.failure(AuthorityException(AppStrings.error_device_file_write_permission_denied))
        }
        if (request.path.isEmpty()) {
            return Result.failure(IllegalArgumentException(AppStrings.ui_path_empty))
        }
        return withContext(Dispatchers.Default) {
            runCatching {
                FileUtils.appendToFile(FileAccessPermission.Allowed, request.path, request.content)
                true
            }
        }.onFailure { error ->
            LogKit.e(AppStrings.ui_failed_to_add_file_content_arg0.format(arg0 = (error.message).toString()), error)
        }
    }

    suspend fun prepareStreamRead(authToken: String, path: String): Result<FileSimpleInfo> {
        return getFileSimpleInfo(
            authToken = authToken,
            path = path,
            load = { FileUtils.getFile(FileAccessPermission.Allowed, path) }
        ).mapCatching { info ->
            if (info.isDirectory) {
                throw IllegalArgumentException(AppStrings.webrtc_directory_stream_not_supported)
            }
            info
        }
    }

    suspend fun prepareStreamWrite(authToken: String, path: String, fileSize: Long): Result<Boolean> {
        val authResult = ensureAuthorized(authToken)
        if (authResult.isFailure) return Result.failure(authResult.exceptionOrNull()!!)
        if (path.isEmpty()) {
            return Result.failure(IllegalArgumentException(AppStrings.ui_path_empty))
        }
        if (fileSize < 0L) {
            return Result.failure(IllegalArgumentException(AppStrings.ui_file_size_invalid))
        }
        SensitiveFileAccessPolicy.deniedException(path)?.let { error ->
            return Result.failure(error)
        }
        if (deviceCertificateState.checkPermission(FileAccessPermission.Allowed, authToken, path, "write")) {
            return Result.failure(AuthorityException(AppStrings.ui_no_permission_to_write_to_that_path))
        }
        return Result.success(true)
    }

    private suspend fun getFileSimpleInfo(
        authToken: String,
        path: String,
        validate: () -> Result<Unit> = {
            if (path.isEmpty()) Result.failure(IllegalArgumentException(AppStrings.ui_path_empty))
            else Result.success(Unit)
        },
        load: () -> Result<FileSimpleInfo>,
    ): Result<FileSimpleInfo> {
        val authResult = ensureAuthorized(authToken)
        if (authResult.isFailure) return Result.failure(authResult.exceptionOrNull()!!)
        SensitiveFileAccessPolicy.deniedException(path)?.let { error ->
            return Result.failure(error)
        }
        if (deviceCertificateState.checkPermission(FileAccessPermission.Allowed, authToken, path, "read")) {
            return Result.failure(AuthorityException(AppStrings.error_path_access_denied))
        }
        val validateResult = validate()
        if (validateResult.isFailure) return Result.failure(validateResult.exceptionOrNull()!!)
        val info = withContext(Dispatchers.Default) {
            load()
        }.getOrElse { error ->
            return Result.failure(error)
        }
        return Result.success(info.withCopy(protocol = FileProtocol.Device, protocolId = localDeviceIdProvider()))
    }

    private suspend fun getFileInfo(
        authToken: String,
        path: String,
        validate: () -> Result<Unit> = {
            if (path.isEmpty()) Result.failure(IllegalArgumentException(AppStrings.ui_path_empty))
            else Result.success(Unit)
        },
        load: () -> Result<FileInfo>,
    ): Result<FileInfo> {
        val authResult = ensureAuthorized(authToken)
        if (authResult.isFailure) return Result.failure(authResult.exceptionOrNull()!!)
        SensitiveFileAccessPolicy.deniedException(path)?.let { error ->
            return Result.failure(error)
        }
        if (deviceCertificateState.checkPermission(FileAccessPermission.Allowed, authToken, path, "read")) {
            return Result.failure(AuthorityException(AppStrings.error_path_access_denied))
        }
        val validateResult = validate()
        if (validateResult.isFailure) return Result.failure(validateResult.exceptionOrNull()!!)
        val info = withContext(Dispatchers.Default) {
            load()
        }.getOrElse { error ->
            return Result.failure(error)
        }
        return Result.success(info.copy(protocol = FileProtocol.Device, protocolId = localDeviceIdProvider()))
    }

    private fun ensureAuthorized(authToken: String): Result<Unit> {
        if (authToken.isBlank()) return Result.failure(AuthorityException(AppStrings.ui_auth_token_invalid))
        if (!deviceCertificateState.isTokenValid(authToken)) {
            return Result.failure(AuthorityException(AppStrings.ui_auth_token_invalid))
        }
        return Result.success(Unit)
    }

    private fun String.isSafeRenameFileName(): Boolean {
        return isNotBlank() &&
            this != "." &&
            this != ".." &&
            !contains('\u0000') &&
            !contains('/') &&
            !contains('\\')
    }

    private data class IndexedFolderCreateRequest(
        val index: Int,
        val path: String,
    )
}
