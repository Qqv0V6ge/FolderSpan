package com.folderspan.ui.state.file

import com.folderspan.data.file.PathInfo
import com.folderspan.data.file.FileProtocol
import com.folderspan.data.file.FileSimpleInfo
import com.folderspan.data.main.DiskBase
import com.folderspan.data.main.Local
import com.folderspan.data.main.device.Device
import com.folderspan.data.main.network.Network
import com.folderspan.data.main.share.Share
import com.folderspan.data.main.share.ShareProtocol
import com.folderspan.data.main.share.SYSTEM_SHARE_DESK_ID
import com.folderspan.service.network.storageConnectionBinding
import com.folderspan.service.mcp.file.FileEndpointRef
import com.folderspan.ui.state.main.DeviceState
import com.folderspan.ui.state.main.NetworkState
import com.folderspan.utils.FileAccessPermission
import com.folderspan.utils.FileUtils
import com.folderspan.utils.PathUtils
import com.folderspan.utils.isDropDirectoryWritable
import androidx.compose.runtime.snapshotFlow
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import strings.AppStrings

internal class CopyDestinationSource(
    val endpoint: CopyEndpointIdentity,
    val name: String,
    val desk: DiskBase? = null,
    val isCurrent: () -> Boolean = { true },
    val load: suspend () -> List<CopyDestination>,
)

data class CopyDestinationLoad(
    val endpoint: CopyEndpointIdentity,
    val connectionName: String,
    val destinations: List<CopyDestination> = emptyList(),
    val loading: Boolean = true,
    val error: String? = null,
)

/** Discovery owns only its child jobs; closing it cannot cancel the task runtime. */
class CopyDestinationDiscovery(val runtimeId: String, parentScope: CoroutineScope) {
    private val scope = CoroutineScope(parentScope.coroutineContext + SupervisorJob(parentScope.coroutineContext[Job]))
    private val permits = Semaphore(4)
    private val mutableLoads = MutableStateFlow<List<CopyDestinationLoad>>(emptyList())
    val loads = mutableLoads.asStateFlow()
    private val sources = mutableMapOf<CopyEndpointIdentity, CopyDestinationSource>()
    private val attempts = mutableMapOf<CopyEndpointIdentity, Long>()
    private val jobs = mutableMapOf<CopyEndpointIdentity, Job>()
    private val bindings = mutableListOf<Pair<Any, String>>()
    private var generation = 0L
    private var closed = false

    fun refresh(deviceState: DeviceState, networkState: NetworkState) {
        refresh(captureSources(deviceState, networkState))
    }

    internal fun observe(deviceState: DeviceState, networkState: NetworkState) {
        refresh(deviceState, networkState)
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            snapshotFlow {
                Triple(
                    deviceState.devices.map { it to it.host.toMap() },
                    deviceState.shares.map { it to (it.session to it.session?.isActive) },
                    networkState.entries.map { it.network to it.network.connectionConfig() },
                )
            }.drop(1).collect { refresh(deviceState, networkState) }
        }
    }

    fun snapshotSources(
        files: List<FileSimpleInfo>,
        deviceState: DeviceState,
        networkState: NetworkState,
        sourceDesk: DiskBase? = null,
    ): List<CopyFileSnapshot> {
        val captured = captureSources(deviceState, networkState)
        return files.map { file ->
            val endpoint = if (file.protocol == FileProtocol.Share && file.protocolId == SYSTEM_SHARE_DESK_ID) {
                CopyEndpointIdentity(runtimeId, FileEndpointRef(FileProtocol.Share, SYSTEM_SHARE_DESK_ID), "system-share", "/")
            } else {
                captured.filter { it.endpoint.endpoint == FileEndpointRef(file.protocol, file.protocolId) }.let { matches ->
                    matches.singleOrNull() ?: matches.singleOrNull { it.desk === sourceDesk }
                }
                    ?.takeIf { it.isCurrent() }?.endpoint ?: error(AppStrings.ui_source_endpoint_unavailable)
            }
            CopyFileSnapshot(endpoint, file)
        }
    }

    internal fun refresh(captured: List<CopyDestinationSource>) {
        check(!closed)
        require(captured.all { it.endpoint.runtimeId == runtimeId })
        require(captured.map { it.endpoint }.distinct().size == captured.size)
        generation++
        jobs.values.forEach { it.cancel() }
        jobs.clear()
        attempts.clear()
        sources.clear()
        sources.putAll(captured.associateBy { it.endpoint })
        val destinations = captured.filter { it.endpoint.endpoint.protocol != FileProtocol.Share }
        mutableLoads.value = destinations.map { CopyDestinationLoad(it.endpoint, it.name) }
        destinations.forEach { retry(it.endpoint) }
    }

    fun retry(endpoint: CopyEndpointIdentity) {
        val source = sources[endpoint]?.takeUnless { closed } ?: return
        jobs.remove(endpoint)?.cancel()
        val attempt = (attempts[endpoint] ?: 0L) + 1
        attempts[endpoint] = attempt
        val currentGeneration = generation
        update(endpoint) { it.copy(loading = true, error = null) }
        jobs[endpoint] = scope.launch {
            val result = try {
                Result.success(permits.withPermit {
                    withTimeout(30_000) {
                        check(source.isCurrent()) { AppStrings.ui_target_endpoint_unavailable }
                        source.load().also {
                            check(source.isCurrent()) { AppStrings.ui_target_endpoint_unavailable }
                        }
                    }
                })
            } catch (timeout: TimeoutCancellationException) {
                Result.failure(timeout)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                Result.failure(error)
            }
            if (!closed && generation == currentGeneration && attempts[endpoint] == attempt) {
                update(endpoint) {
                    it.copy(loading = false, destinations = result.getOrDefault(emptyList()),
                        error = result.exceptionOrNull()?.message ?: if (result.isFailure) AppStrings.ui_target_unavailable else null)
                }
            }
        }
    }

    internal fun source(endpoint: CopyEndpointIdentity): CopyDestinationSource? = sources[endpoint]?.takeIf { it.isCurrent() }

    fun destinationDesk(endpoint: CopyEndpointIdentity): DiskBase? = source(endpoint)?.desk

    fun close() {
        closed = true
        generation++
        scope.cancel()
        jobs.clear()
        attempts.clear()
        sources.clear()
        bindings.clear()
    }

    private fun update(endpoint: CopyEndpointIdentity, change: (CopyDestinationLoad) -> CopyDestinationLoad) {
        mutableLoads.value = loads.value.map { if (it.endpoint == endpoint) change(it) else it }
    }

    private fun binding(owner: Any): String = bindings.firstOrNull { it.first === owner }?.second
        ?: newExternalFileLeaseId().also { bindings += owner to it }

    internal fun captureSources(deviceState: DeviceState, networkState: NetworkState): List<CopyDestinationSource> {
        val local = Local()
        val desks = listOf<DiskBase>(local) + deviceState.devices.toList() +
            deviceState.shares.filter { it.protocol == ShareProtocol.Remote } + networkState.entries.map { it.network }
        return desks.distinctBy { desk -> if (desk is Local) "local" else binding(if (desk is Share) desk.session ?: desk else desk) }.map { desk ->
            val ref = when (desk) {
                is Local -> FileEndpointRef(FileProtocol.Local)
                is Device -> FileEndpointRef(FileProtocol.Device, desk.id)
                is Share -> FileEndpointRef(FileProtocol.Share, desk.id)
                is Network -> FileEndpointRef(FileProtocol.Network, desk.protocolId)
                else -> error(AppStrings.ui_target_endpoint_unavailable)
            }
            val endpoint = CopyEndpointIdentity(runtimeId, ref,
                if (desk is Local) "local" else binding(if (desk is Share) desk.session ?: desk else desk), desk.pathSeparator,
                pathNamespace = if (desk is Network) storageConnectionBinding(desk.connectionConfig()) else ref.sourceId)
            val deviceHosts = (desk as? Device)?.host?.toMap()
            val networkConfig = (desk as? Network)?.connectionConfig()
            val isCurrent = {
                when (desk) {
                    is Local -> true
                    is Device -> deviceState.devices.any { it === desk } && desk.host.size == deviceHosts?.size && desk.host.all { (key, client) -> deviceHosts.get(key) === client }
                    is Share -> deviceState.shares.any { it === desk } && desk.session?.isActive == true
                    is Network -> networkState.entries.any { it.network === desk } && desk.connectionConfig() == networkConfig
                    else -> false
                }
            }
            CopyDestinationSource(endpoint, desk.name, desk, isCurrent) {
                when (desk) {
                    is Local -> withContext(Dispatchers.Default) {
                        val home = PathUtils.getHomePath()
                        val freeSpace = optionalCopyDestinationFreeSpace {
                            PathInfo(home, FileUtils.totalSpace(FileAccessPermission.Allowed, home), FileUtils.freeSpace(FileAccessPermission.Allowed, home))
                        }
                        listOf(directory(endpoint, desk.name, home) {
                            val file = FileUtils.getFile(FileAccessPermission.Allowed, home).getOrThrow()
                            val canonical = PathUtils.resolveCanonicalPath(FileAccessPermission.Allowed, home)
                                ?: error(AppStrings.ui_target_unavailable)
                            PathUtils.getFileAndFolder(FileAccessPermission.Allowed, home).getOrThrow()
                            CopyDestination(CopyFileSnapshot(endpoint, file, canonical), desk.name, desk.name,
                                if (isDropDirectoryWritable(home)) null else AppStrings.ui_read_only, freeSpaceBytes = freeSpace)
                        })
                    }
                    is Device -> {
                        val root = desk.paths.getRootPaths().getOrThrow().firstOrNull()
                            ?: error(AppStrings.ui_target_unavailable)
                        val home = root.path
                        listOf(directory(endpoint, desk.name, home) {
                            val file = desk.files.get(home).getOrThrow().copy(protocol = FileProtocol.Device, protocolId = desk.id)
                            desk.paths.getList(home).getOrThrow()
                            CopyDestination(CopyFileSnapshot(endpoint, file), desk.name, desk.name,
                                if (desk.files.checkDirectoryWriteAccess(home).getOrThrow() == false) AppStrings.ui_read_only else null,
                                freeSpaceBytes = root.copyDestinationFreeSpace())
                        })
                    }
                    is Network -> {
                        val writable = desk.syncCapabilities().getOrThrow().write
                        val home = desk.getRootPaths().firstOrNull()?.path ?: error(AppStrings.ui_target_unavailable)
                        listOf(directory(endpoint, desk.name, home) {
                            val logicalRoot = desk.getFile(home).getOrThrow()
                            val file = desk.getFile(logicalRoot).getOrThrow()
                            desk.getList(file).getOrThrow()
                            CopyDestination(CopyFileSnapshot(endpoint, file), desk.name, desk.name,
                                if (writable) null else AppStrings.ui_read_only)
                        })
                    }
                    else -> emptyList()
                }
            }
        }
    }

    private suspend fun directory(
        endpoint: CopyEndpointIdentity,
        connectionName: String,
        path: String,
        load: suspend () -> CopyDestination,
    ): CopyDestination = try {
        load().also { check(it.directory.isDirectory) { AppStrings.ui_target_unavailable } }
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (error: Exception) {
        val file = FileSimpleInfo.pathFileSimpleInfo(path).copy(protocol = endpoint.endpoint.protocol, protocolId = endpoint.endpoint.sourceId)
        CopyDestination(CopyFileSnapshot(endpoint, file), connectionName, connectionName, error.message ?: AppStrings.ui_target_unavailable)
    }
}

internal fun PathInfo.copyDestinationFreeSpace(): Long? = freeSpace.takeIf { totalSpace > 0 && it in 0..totalSpace }

internal suspend fun optionalCopyDestinationFreeSpace(load: suspend () -> PathInfo): Long? = try {
    load().copyDestinationFreeSpace()
} catch (cancelled: CancellationException) {
    throw cancelled
} catch (_: Exception) {
    null
}
