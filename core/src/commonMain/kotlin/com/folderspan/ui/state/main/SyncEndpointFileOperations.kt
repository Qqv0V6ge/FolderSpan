package com.folderspan.ui.state.main

import com.folderspan.data.file.FileProtocol
import com.folderspan.data.file.FileSimpleInfo
import com.folderspan.data.main.DiskBase
import com.folderspan.data.main.device.Device
import com.folderspan.data.main.network.Network
import com.folderspan.data.main.network.NetworkAccess
import com.folderspan.exception.EmptyDataException
import com.folderspan.service.network.StorageEntryNotFoundException
import com.folderspan.service.network.StoragePath
import com.folderspan.service.network.StorageCapabilities
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import com.folderspan.utils.FileAccessPermission
import com.folderspan.utils.FileUtils
import com.folderspan.utils.PathUtils
import com.folderspan.ui.state.file.readEndpointIgnoreFileLines
import strings.AppStrings

internal class SyncEndpointFileOperations {
    suspend fun deleteEmptyDirectory(type: SyncEndpointType, ref: String, desk: DiskBase, path: String,
        location: FileSimpleInfo? = null): Result<Boolean> = try {
        check(capabilities(desk).getOrThrow().delete) { "Deleting directories is not permitted" }
        require(!isRootPath(path, endpointSeparator(type, desk))) { "Cannot delete endpoint root" }
        val current = readPathInfo(type, ref, desk, path, location).getOrThrow() ?: error("Directory disappeared")
        check(current.isDirectory && !current.isSymbolicLink) { "Not a directory" }
        when (type) {
            SyncEndpointType.Local -> FileUtils.deleteEmptyDirectory(FileAccessPermission.Allowed, path)
            SyncEndpointType.Network -> (desk as? NetworkAccess)?.deleteEmptyDirectory(current)
                ?: error(AppStrings.ui_network_endpoint_unavailable)
            SyncEndpointType.Device -> Result.failure(UnsupportedOperationException("The peer cannot safely remove only an empty directory"))
        }
    } catch (cancelled: CancellationException) { throw cancelled }
    catch (error: Exception) { Result.failure(error) }

    suspend fun capabilities(desk: DiskBase): Result<StorageCapabilities> = if (desk is Network) desk.syncCapabilities()
        else Result.success(desk.menuPermission.let { permission ->
            StorageCapabilities(read = permission?.copy == true, write = permission?.paste == true,
                delete = permission?.delete == true, rename = permission?.rename == true)
        })

    suspend fun move(type: SyncEndpointType, ref: String, desk: DiskBase, from: String, to: String,
        location: FileSimpleInfo? = null, destinationLocation: FileSimpleInfo? = null): Result<Boolean> = try {
        val permissions = capabilities(desk).getOrThrow()
        check(permissions.write && permissions.delete && (type != SyncEndpointType.Device || permissions.read)) { "Move is not permitted" }
        when (type) {
            SyncEndpointType.Local -> FileUtils.move(FileAccessPermission.Allowed, from, to)
            SyncEndpointType.Network -> {
                val network = desk as? NetworkAccess ?: error(AppStrings.ui_network_endpoint_unavailable)
                val original = readPathInfo(type, ref, desk, from, location).getOrThrow() ?: error("Move source is missing")
                network.move(original, StoragePath(to, destinationLocation?.withCopy(path = to)?.storageReference))
            }
            SyncEndpointType.Device -> {
                val device = desk as? Device ?: error(AppStrings.ui_device_endpoint_unavailable)
                val separator = endpointSeparator(type, desk)
                val source = normalizePathForCompare(from, separator)
                val target = normalizePathForCompare(to, separator)
                require(source != target && !target.startsWith(source.trimEnd('/', '\\') + separator)) { "Invalid move destination" }
                verifiedSyncDeviceMove(
                    readSource = { moveSnapshot(type, ref, desk, source, location) },
                    readTarget = { moveSnapshot(type, ref, desk, target, null) },
                    copy = {
                        val client = device.fileClient ?: device.host.values.firstOrNull()?.fileRouteClient
                            ?: error(AppStrings.ui_device_endpoint_unavailable)
                        client.copyPath(source, target).getOrThrow()
                    },
                    delete = {
                        val original = readPathInfo(type, ref, desk, source, location).getOrThrow() ?: error("Move source disappeared")
                        deletePath(type, desk, source, original.isDirectory, original).getOrThrow()
                    },
                )
            }
        }
    } catch (cancelled: CancellationException) { throw cancelled }
    catch (error: Exception) { Result.failure(error) }

    private suspend fun moveSnapshot(type: SyncEndpointType, ref: String, desk: DiskBase, root: String,
        location: FileSimpleInfo?): Map<String, SyncEntryState>? {
        val info = readPathInfo(type, ref, desk, root, location).getOrThrow() ?: return null
        val result = linkedMapOf<String, SyncEntryState>()
        val separator = endpointSeparator(type, desk)
        suspend fun collect(file: FileSimpleInfo, relative: String) {
            check(!file.isSymbolicLink) { "Cannot move symbolic links" }
            result[relative] = SyncEntryState(file.isDirectory, file.size, file.updatedDate, file.storageReference)
            if (file.isDirectory) for (child in listChildren(type, ref, desk, file.path, file).getOrThrow()) {
                require(child.name.isNotBlank() && child.name != "." && child.name != ".." && '/' !in child.name && '\\' !in child.name)
                collect(child, if (relative.isEmpty()) child.name else "$relative/${child.name}")
            }
        }
        collect(info.withCopy(path = normalizePathForCompare(root, separator)), "")
        return result
    }
    suspend fun setModifiedTime(type: SyncEndpointType, desk: DiskBase, path: String, millis: Long): Result<Boolean> =
        when (type) {
            SyncEndpointType.Local -> FileUtils.setLastModified(FileAccessPermission.Allowed, path, millis)
            SyncEndpointType.Network -> (desk as? NetworkAccess)?.setModifiedTime(path, millis) ?: Result.success(false)
            SyncEndpointType.Device -> Result.success(false)
        }

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
        location: FileSimpleInfo? = null,
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
                readNetworkPathInfo(network, ref, normalized, separator, location)
            }
        }
    }

    suspend fun listChildren(
        type: SyncEndpointType,
        ref: String,
        desk: DiskBase,
        path: String,
        location: FileSimpleInfo? = null,
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
                network.getList(location?.withCopy(path = path) ?: FileSimpleInfo.pathFileSimpleInfo(path))
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

    suspend fun readIgnoreLines(type: SyncEndpointType, desk: DiskBase, file: FileSimpleInfo): Result<List<String>> =
        readEndpointIgnoreFileLines(toProtocol(type), file, device = desk as? Device, networkAccess = desk as? NetworkAccess)

    suspend fun createDirectory(
        type: SyncEndpointType,
        desk: DiskBase,
        path: String,
        location: FileSimpleInfo? = null,
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
                    val name = fileNameOf(normalized, separator)
                    if (name.isBlank()) {
                        Result.success(true)
                    } else {
                        network.createFolder(location?.withCopy(path = normalized)?.storagePath ?: FileSimpleInfo.pathFileSimpleInfo(normalized).storagePath)
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
        location: FileSimpleInfo? = null,
    ): Result<Boolean> {
        val permissions = capabilities(desk).getOrElse { return Result.failure(it) }
        if (!permissions.delete) return Result.failure(IllegalStateException("Delete is not permitted"))
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
                network.delete((location ?: FileSimpleInfo.pathFileSimpleInfo(path)).withCopy(path = path, isDirectory = isDirectory))
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
        location: FileSimpleInfo? = null,
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

        val existsResult = readPathInfo(type, ref, desk, normalized, location)
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
            val deleteResult = deletePath(type, desk, normalized, false, existing)
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
                location = location,
            )
            if (parentResult.isFailure) {
                return parentResult
            }
        }

        val createResult = createDirectory(type, desk, normalized, location)
        if (createResult.isFailure || !createResult.getOrDefault(false)) {
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
        location: FileSimpleInfo?,
    ): Result<FileSimpleInfo?> {
        val selected = location?.withCopy(path = path)
        if (selected?.storageReference != null) {
            val result = access.getFile(selected)
            val error = result.exceptionOrNull()
            return if (error is StorageEntryNotFoundException) Result.success(null)
            else result.map { it.withCopy(protocol = FileProtocol.Network, protocolId = protocolId) }
        }
        if (isRootPath(path, separator)) {
            val root = (access as? Network)?.getFile(path)?.getOrNull()
            return Result.success(
                (root ?: FileSimpleInfo.pathFileSimpleInfo(path)).withCopy(
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
        val matches = list.filter { item ->
            normalizePathForCompare(item.path, separator) == path || item.name == name
        }

        if (matches.size > 1) return Result.failure(IllegalStateException("Storage path is ambiguous; select the item from the file list"))
        return Result.success(matches.singleOrNull()?.withCopy(protocol = FileProtocol.Network, protocolId = protocolId))
    }
}

internal suspend fun verifiedSyncDeviceMove(
    readSource: suspend () -> Map<String, SyncEntryState>?,
    readTarget: suspend () -> Map<String, SyncEntryState>?,
    copy: suspend () -> Boolean,
    delete: suspend () -> Boolean,
): Result<Boolean> = try {
    val before = checkNotNull(readSource()) { "Move source is missing" }
    check("" in before) { "Move source state is unknown" }
    check(before.values.all { it.isDirectory || it.modifiedAt > 0 && it.size >= 0 }) { "Move source state is unknown" }
    check(readTarget() == null) { "Move target already exists" }
    currentCoroutineContext().ensureActive()
    check(copy()) { "Move copy failed" }
    val target = checkNotNull(readTarget()) { "Move copy is missing" }
    check(before.keys == target.keys && before.all { (path, state) ->
        val copied = target.getValue(path)
        state.isDirectory == copied.isDirectory && (state.isDirectory || state.size == copied.size)
    }) { "Move copy verification failed" }
    val after = checkNotNull(readSource()) { "Move source disappeared" }
    check(before.keys == after.keys && before.all { (path, state) -> syncEntryMatchesSnapshot(state, after[path]) }) { "Move source changed" }
    currentCoroutineContext().ensureActive()
    check(delete()) { "Move source could not be deleted; the verified copy is retained" }
    Result.success(true)
} catch (cancelled: CancellationException) { throw cancelled }
catch (error: Exception) { Result.failure(error) }
