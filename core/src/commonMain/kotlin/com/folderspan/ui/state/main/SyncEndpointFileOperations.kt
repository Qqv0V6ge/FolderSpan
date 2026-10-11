package com.folderspan.ui.state.main

import com.folderspan.data.file.FileProtocol
import com.folderspan.data.file.FileSimpleInfo
import com.folderspan.data.main.DiskBase
import com.folderspan.data.main.device.Device
import com.folderspan.data.main.network.Network
import com.folderspan.data.main.network.NetworkAccess
import com.folderspan.exception.EmptyDataException
import com.folderspan.utils.FileAccessPermission
import com.folderspan.utils.FileUtils
import com.folderspan.utils.PathUtils
import strings.AppStrings

internal class SyncEndpointFileOperations {
    fun endpointSeparator(type: SyncEndpointType, desk: DiskBase): String {
        return when (type) {
            SyncEndpointType.Local -> PathUtils.getPathSeparator()
            SyncEndpointType.Device -> (desk as? Device)?.pathSeparator ?: PathUtils.getPathSeparator()
            SyncEndpointType.Network -> (desk as? Network)?.pathSeparator ?: "/"
        }
    }

    suspend fun readPathInfo(
        type: SyncEndpointType,
        ref: String,
        desk: DiskBase,
        path: String,
    ): Result<FileSimpleInfo?> {
        val separator = endpointSeparator(type, desk)
        val normalized = normalizePathForCompare(path, separator)

        return when (type) {
            SyncEndpointType.Local -> {
                wrapReadResult(
                    FileUtils.getFile(FileAccessPermission.Allowed, normalized),
                    FileProtocol.Local,
                    "",
                )
            }

            SyncEndpointType.Device -> {
                val device = desk as? Device
                    ?: return Result.failure(IllegalStateException(AppStrings.ui_device_endpoint_unavailable))
                wrapReadResult(device.files.get(normalized), FileProtocol.Device, ref)
            }

            SyncEndpointType.Network -> {
                val network = desk as? NetworkAccess
                    ?: return Result.failure(IllegalStateException(AppStrings.ui_network_endpoint_unavailable))
                readNetworkPathInfo(network, ref, normalized, separator)
            }
        }
    }

    suspend fun listChildren(
        type: SyncEndpointType,
        ref: String,
        desk: DiskBase,
        path: String,
    ): Result<List<FileSimpleInfo>> {
        val result = when (type) {
            SyncEndpointType.Local -> PathUtils.getFileAndFolder(FileAccessPermission.Allowed, path)
            SyncEndpointType.Device -> {
                val device = desk as? Device
                    ?: return Result.failure(IllegalStateException(AppStrings.ui_device_endpoint_unavailable))
                device.paths.getList(path)
            }

            SyncEndpointType.Network -> {
                val network = desk as? NetworkAccess
                    ?: return Result.failure(IllegalStateException(AppStrings.ui_network_endpoint_unavailable))
                network.getList(path)
            }
        }

        if (result.isFailure) {
            val error = result.exceptionOrNull() ?: IllegalStateException(AppStrings.ui_failed_read_directory)
            if (error is EmptyDataException) {
                return Result.success(emptyList())
            }
            return Result.failure(error)
        }

        val protocol = toProtocol(type)
        val list = result.getOrDefault(emptyList()).map { item ->
            item.withCopy(
                protocol = protocol,
                protocolId = ref,
            )
        }
        return Result.success(list)
    }

    suspend fun createDirectory(
        type: SyncEndpointType,
        desk: DiskBase,
        path: String,
    ): Result<Boolean> {
        val separator = endpointSeparator(type, desk)
        val normalized = normalizePathForCompare(path, separator)

        return when (type) {
            SyncEndpointType.Local -> FileUtils.createFolder(FileAccessPermission.Allowed, normalized)
            SyncEndpointType.Device -> {
                val device = desk as? Device
                    ?: return Result.failure(IllegalStateException(AppStrings.ui_device_endpoint_unavailable))
                device.paths.createDirectory(normalized)
            }

            SyncEndpointType.Network -> {
                val network = desk as? NetworkAccess
                    ?: return Result.failure(IllegalStateException(AppStrings.ui_network_endpoint_unavailable))
                if (isRootPath(normalized, separator)) {
                    Result.success(true)
                } else {
                    val parent = parentPathOf(normalized, separator)
                    val name = fileNameOf(normalized, separator)
                    if (name.isBlank()) {
                        Result.success(true)
                    } else {
                        network.createFolder(parent, name)
                    }
                }
            }
        }
    }

    suspend fun deletePath(
        type: SyncEndpointType,
        desk: DiskBase,
        path: String,
        isDirectory: Boolean,
    ): Result<Boolean> {
        return when (type) {
            SyncEndpointType.Local -> FileUtils.deleteFile(FileAccessPermission.Allowed, path)
            SyncEndpointType.Device -> {
                val device = desk as? Device
                    ?: return Result.failure(IllegalStateException(AppStrings.ui_device_endpoint_unavailable))
                if (isDirectory) {
                    device.paths.deleteDirectory(path)
                } else {
                    val result = device.files.delete(listOf(path))
                    if (result.isFailure) {
                        Result.failure(result.exceptionOrNull() ?: IllegalStateException(AppStrings.ui_delete_failed))
                    } else {
                        val value = result.getOrDefault(emptyList()).firstOrNull()?.getOrDefault(false) ?: true
                        Result.success(value)
                    }
                }
            }

            SyncEndpointType.Network -> {
                val network = desk as? NetworkAccess
                    ?: return Result.failure(IllegalStateException(AppStrings.ui_network_endpoint_unavailable))
                network.delete(path, isDirectory)
            }
        }
    }

    suspend fun ensureDirectoryExists(
        type: SyncEndpointType,
        ref: String,
        desk: DiskBase,
        path: String,
        knownDirectories: MutableSet<String>,
        occupiedPaths: MutableSet<String>,
    ): Result<Boolean> {
        val separator = endpointSeparator(type, desk)
        val normalized = normalizePathForCompare(path, separator)

        if (normalized.isBlank()) return Result.success(true)
        if (isRootPath(normalized, separator)) {
            knownDirectories.add(normalized)
            occupiedPaths.add(normalized)
            return Result.success(true)
        }

        if (knownDirectories.contains(normalized)) {
            occupiedPaths.add(normalized)
            return Result.success(true)
        }

        val existsResult = readPathInfo(type, ref, desk, normalized)
        if (existsResult.isFailure) {
            return Result.failure(existsResult.exceptionOrNull() ?: IllegalStateException(AppStrings.ui_failed_read_directory))
        }

        val existing = existsResult.getOrNull()
        if (existing?.isDirectory == true) {
            knownDirectories.add(normalized)
            occupiedPaths.add(normalized)
            return Result.success(true)
        }

        if (existing != null && !existing.isDirectory) {
            val deleteResult = deletePath(type, desk, normalized, false)
            if (deleteResult.isFailure || !deleteResult.getOrDefault(false)) {
                val error = deleteResult.exceptionOrNull() ?: IllegalStateException(AppStrings.ui_target_path_and_directory_conflict)
                return Result.failure(error)
            }
        }

        val parent = parentPathOf(normalized, separator)
        if (parent != normalized) {
            val parentResult = ensureDirectoryExists(
                type = type,
                ref = ref,
                desk = desk,
                path = parent,
                knownDirectories = knownDirectories,
                occupiedPaths = occupiedPaths,
            )
            if (parentResult.isFailure) {
                return parentResult
            }
        }

        val createResult = createDirectory(type, desk, normalized)
        if (createResult.isFailure) {
            return Result.failure(createResult.exceptionOrNull() ?: IllegalStateException(AppStrings.ui_failed_create_directory))
        }

        knownDirectories.add(normalized)
        occupiedPaths.add(normalized)
        return Result.success(createResult.getOrDefault(true))
    }

    private fun wrapReadResult(
        result: Result<FileSimpleInfo>,
        protocol: FileProtocol,
        protocolId: String,
    ): Result<FileSimpleInfo?> {
        if (result.isSuccess) {
            val info = result.getOrThrow().withCopy(protocol = protocol, protocolId = protocolId)
            return Result.success(info)
        }

        val error = result.exceptionOrNull()
        if (isNotFoundMessage(error?.message)) {
            return Result.success(null)
        }

        return Result.failure(error ?: IllegalStateException(AppStrings.ui_read_path_failed))
    }

    private suspend fun readNetworkPathInfo(
        access: NetworkAccess,
        protocolId: String,
        path: String,
        separator: String,
    ): Result<FileSimpleInfo?> {
        if (isRootPath(path, separator)) {
            return Result.success(
                FileSimpleInfo.pathFileSimpleInfo(path).withCopy(
                    isDirectory = true,
                    protocol = FileProtocol.Network,
                    protocolId = protocolId,
                )
            )
        }

        val parent = parentPathOf(path, separator)
        val name = fileNameOf(path, separator)
        val listResult = access.getList(parent)
        if (listResult.isFailure) {
            val error = listResult.exceptionOrNull()
            if (isNotFoundMessage(error?.message)) {
                return Result.success(null)
            }
            return Result.failure(error ?: IllegalStateException(AppStrings.ui_read_the_network_path_failed))
        }

        val list = listResult.getOrDefault(emptyList())
        val match = list.firstOrNull { item ->
            normalizePathForCompare(item.path, separator) == path || item.name == name
        }

        return Result.success(match?.withCopy(protocol = FileProtocol.Network, protocolId = protocolId))
    }
}
