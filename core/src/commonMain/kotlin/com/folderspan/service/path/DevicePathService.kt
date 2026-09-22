package com.folderspan.service.path

import com.folderspan.createSettings
import com.folderspan.data.file.FileProtocol
import com.folderspan.data.file.FileSimpleInfo
import com.folderspan.data.file.PathInfo
import com.folderspan.exception.AuthorityException
import com.folderspan.exception.EmptyDataException
import com.folderspan.extensions.getFileAndFolder
import com.folderspan.service.data.CreateDirectoryRequest
import com.folderspan.service.data.DeleteDirectoryRequest
import com.folderspan.service.data.ListRequest
import com.folderspan.service.data.PathExistsRequest
import com.folderspan.ui.state.device.DeviceCertificateState
import com.folderspan.ui.state.device.DeviceSharePathScope
import com.folderspan.ui.state.device.shareListedFileName
import com.folderspan.utils.FileAccessPermission
import com.folderspan.utils.FileUtils
import com.folderspan.utils.LogKit
import com.folderspan.utils.PathUtils
import com.folderspan.utils.SensitiveFileAccessPolicy
import com.folderspan.utils.SettingsUtils
import com.folderspan.utils.isAndroidContentUriPath
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import strings.AppStrings

typealias DevicePathEntries = Map<Pair<FileProtocol, String>, MutableList<FileSimpleInfo>>

class DevicePathService internal constructor(
    private val deviceCertificateState: DeviceCertificateState,
    private val listDirectory: suspend (String) -> Result<List<FileSimpleInfo>>,
    private val listLimiter: DirectoryListLimiter,
    private val localDeviceIdProvider: () -> String,
) {
    internal fun isAuthorizedWebRtcPeerToken(authToken: String, remoteDeviceId: String): Boolean {
        return remoteDeviceId.isNotBlank() &&
            deviceCertificateState.getDeviceIdByToken(authToken) == remoteDeviceId &&
            deviceCertificateState.hasTokenFingerprint(authToken) &&
            deviceCertificateState.isTokenValid(authToken)
    }

    constructor(
        deviceCertificateState: DeviceCertificateState,
        localDeviceIdProvider: () -> String = {
            createSettings().getString(SettingsUtils.KEY_DEVICE_ID, "")
        },
    ) : this(
        deviceCertificateState = deviceCertificateState,
        listDirectory = { path ->
            withContext(Dispatchers.Default) {
                path.getFileAndFolder(FileAccessPermission.Allowed)
            }
        },
        listLimiter = defaultDirectoryListLimiter(
            maxConcurrency = DEVICE_PATH_LIST_MAX_CONCURRENT_REQUESTS,
        ),
        localDeviceIdProvider = localDeviceIdProvider,
    )

    suspend fun list(authToken: String, request: ListRequest): Result<DevicePathEntries> {
        val authResult = ensureAuthorized(authToken)
        if (authResult.isFailure) {
            return Result.failure(authResult.exceptionOrNull() ?: AuthorityException(AppStrings.ui_auth_token_invalid))
        }
        val shareScope = deviceCertificateState.getDeviceSharePathScope(authToken)
        if (shareScope != null) {
            return listDeviceSharePath(authToken, request.path, shareScope)
        }
        SensitiveFileAccessPolicy.deniedException(request.path)?.let { error ->
            return Result.failure(error)
        }
        val denied = deviceCertificateState.checkPermission(
            FileAccessPermission.Allowed,
            authToken,
            request.path,
            "read",
        )
        if (denied) {
            return Result.failure(AuthorityException(AppStrings.error_path_access_denied))
        }
        if (request.path.isEmpty()) {
            return Result.failure(IllegalArgumentException(AppStrings.ui_path_empty))
        }

        val fileAndFolder = listLimiter.withPermit {
            listDirectory(request.path)
        }
        if (fileAndFolder.isFailure) {
            val error = fileAndFolder.exceptionOrNull() ?: Exception(AppStrings.ui_path_lookup_failed)
            if (error is EmptyDataException) {
                return Result.success(emptyMap())
            }
            return Result.failure(error)
        }

        val localDeviceId = localDeviceIdProvider()
        val visibleEntries = deviceCertificateState.filterDeviceShareListing(
            authToken,
            fileAndFolder.getOrDefault(emptyList()),
        )
        return Result.success(
            buildDevicePathEntries(
                requestPath = request.path,
                localDeviceId = localDeviceId,
                fileSimpleInfos = visibleEntries.map { item ->
                    val classification = SensitiveFileAccessPolicy.classify(item.path)
                    item.withCopy(
                        sensitivity = classification.sensitivity,
                        sensitivityCategory = classification.category,
                    )
                }
            )
        )
    }

    suspend fun rootPaths(authToken: String): Result<List<PathInfo>> {
        val authResult = ensureAuthorized(authToken)
        if (authResult.isFailure) {
            return Result.failure(authResult.exceptionOrNull() ?: AuthorityException(AppStrings.ui_auth_token_invalid))
        }
        val sharedRootPaths = deviceCertificateState.deviceShareRootPaths(authToken)
        if (sharedRootPaths != null) {
            return Result.success(listOf(PathInfo("/", 0L, 0L)))
        }
        val rootPaths = withContext(Dispatchers.Default) {
            PathUtils.getRootPaths(FileAccessPermission.Allowed)
        }
        return rootPaths.map { paths ->
            paths.filter { root ->
                !deviceCertificateState.checkPermission(
                    FileAccessPermission.Allowed,
                    authToken,
                    root.path,
                    "read",
                )
            }
        }
    }

    private suspend fun listDeviceSharePath(
        authToken: String,
        virtualPath: String,
        scope: DeviceSharePathScope,
    ): Result<DevicePathEntries> {
        if (virtualPath == "/") {
            return runCatching {
                val roots = scope.virtualRoots.map { root ->
                    val physicalPath = root.grant.path
                    SensitiveFileAccessPolicy.deniedException(physicalPath)?.let { throw it }
                    if (deviceCertificateState.checkPermission(
                            FileAccessPermission.Allowed,
                            authToken,
                            physicalPath,
                            "read",
                        )
                    ) {
                        throw AuthorityException(AppStrings.error_path_access_denied)
                    }
                    val entry = withContext(Dispatchers.Default) {
                        FileUtils.getFile(FileAccessPermission.Allowed, physicalPath)
                    }.getOrThrow()
                    if (
                        !isAndroidContentUriPath(physicalPath) &&
                        (entry.isSymbolicLink || PathUtils.isSymbolicLink(FileAccessPermission.Allowed, physicalPath))
                    ) {
                        throw AuthorityException(AppStrings.error_path_access_denied)
                    }
                    // 虚拟根名负责路由；展示/保存文件名用本机元数据，避免 content:// 最后一段变成文件名。
                    root.name to entry.withCopy(name = shareListedFileName(entry.name, root.name))
                }
                buildProtocolPathEntries(
                    localProtocol = FileProtocol.Share,
                    localDeviceId = localDeviceIdProvider(),
                    fileSimpleInfos = roots.map { item -> item.second },
                ) { entry ->
                    roots.first { item -> item.second === entry }.first
                }
            }
        }

        val physicalPath = scope.resolveVirtualContentPath(virtualPath)
            ?: return Result.failure(AuthorityException(AppStrings.error_path_access_denied))
        SensitiveFileAccessPolicy.deniedException(physicalPath)?.let { error ->
            return Result.failure(error)
        }
        if (deviceCertificateState.checkPermission(FileAccessPermission.Allowed, authToken, physicalPath, "read")) {
            return Result.failure(AuthorityException(AppStrings.error_path_access_denied))
        }
        val children = listLimiter.withPermit { listDirectory(physicalPath) }
        if (children.isFailure) {
            val error = children.exceptionOrNull() ?: Exception(AppStrings.ui_path_lookup_failed)
            if (error is EmptyDataException) return Result.success(emptyMap())
            return Result.failure(error)
        }
        val visibleEntries = deviceCertificateState.filterDeviceShareListing(
            authToken,
            children.getOrDefault(emptyList()),
        ).filterNot { entry ->
            entry.isSymbolicLink || PathUtils.isSymbolicLink(FileAccessPermission.Allowed, entry.path)
        }
        return Result.success(
            buildProtocolPathEntries(
                localProtocol = FileProtocol.Share,
                localDeviceId = localDeviceIdProvider(),
                fileSimpleInfos = visibleEntries.map { entry ->
                    val classification = SensitiveFileAccessPolicy.classify(entry.path)
                    entry.withCopy(
                        sensitivity = classification.sensitivity,
                        sensitivityCategory = classification.category,
                    )
                },
            ) { entry -> "/${entry.name}" }
        )
    }

    suspend fun exists(authToken: String, request: PathExistsRequest): Result<Boolean> {
        return executeBooleanPathOperation(
            authToken = authToken,
            path = request.path,
            permission = "read",
            emptyPathMessage = AppStrings.ui_path_empty,
        ) {
            PathUtils.exists(FileAccessPermission.Allowed, request.path)
        }
    }

    suspend fun createDirectory(authToken: String, request: CreateDirectoryRequest): Result<Boolean> {
        return executeBooleanPathOperation(
            authToken = authToken,
            path = request.path,
            permission = "write",
            emptyPathMessage = AppStrings.ui_path_empty,
        ) {
            PathUtils.createDirectoryIfNotExists(FileAccessPermission.Allowed, request.path)
            true
        }
    }

    suspend fun deleteDirectory(authToken: String, request: DeleteDirectoryRequest): Result<Boolean> {
        return executeBooleanPathOperation(
            authToken = authToken,
            path = request.path,
            permission = "remove",
            emptyPathMessage = AppStrings.ui_path_empty,
        ) {
            PathUtils.deleteDirectory(FileAccessPermission.Allowed, request.path)
            true
        }
    }

    private suspend fun executeBooleanPathOperation(
        authToken: String,
        path: String,
        permission: String,
        emptyPathMessage: String,
        block: () -> Boolean,
    ): Result<Boolean> {
        val authResult = ensureAuthorized(authToken)
        if (authResult.isFailure) {
            return Result.failure(authResult.exceptionOrNull() ?: AuthorityException(AppStrings.ui_auth_token_invalid))
        }
        SensitiveFileAccessPolicy.deniedException(path)?.let { error ->
            return Result.failure(error)
        }
        val denied = deviceCertificateState.checkPermission(
            FileAccessPermission.Allowed,
            authToken,
            path,
            permission,
        )
        if (denied) {
            return Result.failure(AuthorityException(AppStrings.error_path_access_denied))
        }
        if (path.isEmpty()) {
            return Result.failure(IllegalArgumentException(emptyPathMessage))
        }
        return withContext(Dispatchers.Default) {
            runCatching(block)
        }.onFailure { error ->
            LogKit.e(
                "${AppStrings.ui_device_path_operation_failed}: " +
                    "path=$path, permission=$permission, msg=${error.message}",
                error,
            )
        }
    }

    private fun ensureAuthorized(authToken: String): Result<Unit> {
        if (authToken.isBlank()) {
            return Result.failure(AuthorityException(AppStrings.ui_auth_token_invalid))
        }
        if (!deviceCertificateState.isTokenValid(authToken)) {
            return Result.failure(AuthorityException(AppStrings.ui_auth_token_invalid))
        }
        return Result.success(Unit)
    }

    companion object {
        private const val DEVICE_PATH_LIST_MAX_CONCURRENT_REQUESTS = 12
    }
}

fun buildDevicePathEntries(
    requestPath: String,
    localDeviceId: String,
    fileSimpleInfos: List<FileSimpleInfo>,
): DevicePathEntries {
    return buildProtocolPathEntries(
        localProtocol = FileProtocol.Device,
        localDeviceId = localDeviceId,
        fileSimpleInfos = fileSimpleInfos,
    ) { fileSimpleInfo ->
        fileSimpleInfo.path.replace(requestPath, "")
    }
}

fun buildProtocolPathEntries(
    localProtocol: FileProtocol,
    localDeviceId: String,
    fileSimpleInfos: List<FileSimpleInfo>,
    relativePath: (FileSimpleInfo) -> String,
): DevicePathEntries {
    return mutableMapOf<Pair<FileProtocol, String>, MutableList<FileSimpleInfo>>().apply {
        fileSimpleInfos.forEach { fileSimpleInfo ->
            val key = if (fileSimpleInfo.protocol == FileProtocol.Local) {
                Pair(localProtocol, localDeviceId)
            } else {
                Pair(fileSimpleInfo.protocol, fileSimpleInfo.protocolId)
            }

            val normalizedInfo = fileSimpleInfo.withCopy(
                path = relativePath(fileSimpleInfo),
                protocol = FileProtocol.Local,
                protocolId = ""
            )
            getOrPut(key) { mutableListOf() }.add(normalizedInfo)
        }
    }
}

fun restoreDevicePathEntries(
    requestPath: String,
    entries: DevicePathEntries,
): List<FileSimpleInfo> {
    return buildList {
        entries.forEach { (protocol, fileInfos) ->
            fileInfos.forEach { info ->
                add(
                    info.withCopy(
                        protocol = protocol.first,
                        protocolId = protocol.second,
                        path = requestPath + info.path
                    )
                )
            }
        }
    }
}
