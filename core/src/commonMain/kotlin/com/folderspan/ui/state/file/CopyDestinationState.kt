package com.folderspan.ui.state.file

import com.folderspan.data.file.FileProtocol
import com.folderspan.data.file.FileSimpleInfo
import com.folderspan.service.mcp.file.FileEndpointRef
import com.folderspan.service.mcp.file.normalizeEndpointPath
import com.folderspan.ui.state.main.TaskRuntimeEndpointRef
import kotlinx.serialization.Serializable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import strings.AppStrings

/** A binding identifies the captured connection, independently of its display name. */
@Serializable
data class CopyEndpointIdentity(
    val runtimeId: String,
    val endpoint: FileEndpointRef,
    val connectionBinding: String,
    val pathSeparator: String,
    val caseSensitive: Boolean = pathSeparator != "\\",
    val pathNamespace: String = endpoint.sourceId,
) {
    init {
        require(runtimeId.isNotBlank())
        require(endpoint.protocol == FileProtocol.Local || connectionBinding.isNotBlank())
        require(pathSeparator == "/" || pathSeparator == "\\")
    }

    internal fun pathKey(path: String): String {
        val normalized = normalizeEndpointPath(path, pathSeparator)
        val unified = if (pathSeparator == "\\") normalized.replace('\\', '/') else normalized
        val key = unified.trimEnd('/').ifEmpty { "/" }
        return if (caseSensitive) key else key.lowercase()
    }
}

/** FileSimpleInfo is mutable; neither input objects nor returned copies can mutate this snapshot. */
class CopyFileSnapshot(
    val endpoint: CopyEndpointIdentity,
    file: FileSimpleInfo,
    canonicalPath: String = file.path,
) {
    private val captured = file.copy()
    val file: FileSimpleInfo get() = captured.copy()
    val location = TaskRuntimeEndpointRef(file.protocol, file.protocolId, file.path, file.storageReference)
    internal val pathKey = endpoint.pathKey(canonicalPath)
    val isDirectory: Boolean get() = captured.isDirectory

    init {
        require(file.protocol == endpoint.endpoint.protocol && file.protocolId == endpoint.endpoint.sourceId)
    }

    fun matchesCurrent(currentEndpoint: CopyEndpointIdentity, current: FileSimpleInfo): Boolean =
        endpoint == currentEndpoint && captured == current

    private fun sharesEndpoint(other: CopyFileSnapshot): Boolean =
        endpoint.runtimeId == other.endpoint.runtimeId && endpoint.endpoint == other.endpoint.endpoint &&
            endpoint.pathSeparator == other.endpoint.pathSeparator && endpoint.caseSensitive == other.endpoint.caseSensitive

    fun sameLocation(other: CopyFileSnapshot): Boolean {
        if (!sharesEndpoint(other)) return false
        val id = location.storageReference?.id?.takeIf { it.isNotEmpty() }
        val otherId = other.location.storageReference?.id?.takeIf { it.isNotEmpty() }
        if (id != null && otherId != null) return id == otherId
        return endpoint.pathNamespace == other.endpoint.pathNamespace && pathKey == other.pathKey
    }

    internal fun contains(other: CopyFileSnapshot): Boolean {
        if (!sharesEndpoint(other)) return false
        val id = location.storageReference?.id?.takeIf { it.isNotEmpty() }
        val reference = other.location.storageReference
        return sameLocation(other) ||
            (id != null && (reference?.parentId == id || reference?.ancestorId == id)) ||
            (endpoint.pathNamespace == other.endpoint.pathNamespace && other.pathKey.startsWith(pathKey.trimEnd('/') + "/"))
    }

    internal fun isCopyIntoSelf(target: CopyFileSnapshot): Boolean {
        if (!sharesEndpoint(target)) return false
        if (sameLocation(target) || (isDirectory && contains(target))) return true
        val parentId = location.storageReference?.parentId?.takeIf { it.isNotEmpty() }
        if (parentId != null && parentId == target.location.storageReference?.id) return true
        if (endpoint.pathNamespace != target.endpoint.pathNamespace) return false
        val child = target.pathKey.trimEnd('/') + "/" +
            if (endpoint.caseSensitive) captured.name else captured.name.lowercase()
        return child == pathKey
    }
}

data class CopyDestination(
    val directory: CopyFileSnapshot,
    val name: String,
    val connectionName: String,
    val unavailableReason: String? = null,
    val freeSpaceBytes: Long? = null,
)

enum class CopyDestinationSelectionResult {
    Added, AlreadySelected, Unavailable, WrongRuntime, OverlappingDirectory, SourceDestinationConflict,
}

data class CopyDestinationControls(
    val firstVisibleItemIndex: Int = 0,
    val firstVisibleItemOffset: Int = 0,
)

/** Owned by one route; accepted tasks will be owned by the task runtime. */
class CopyDestinationState(
    val runtimeId: String,
    sources: List<CopyFileSnapshot>,
    val requestId: String = newExternalFileLeaseId(),
) {
    private val mutableSources = MutableStateFlow(sources.toList())
    val sourceSnapshots = mutableSources.asStateFlow()
    val sources: List<CopyFileSnapshot> get() = sourceSnapshots.value
    internal val sourceMutationMutex = Mutex()
    private val mutableSourcesLocked = MutableStateFlow(false)
    val sourcesLocked = mutableSourcesLocked.asStateFlow()
    private val mutableSelected = MutableStateFlow<List<CopyDestination>>(emptyList())
    val selected = mutableSelected.asStateFlow()
    private val mutableControls = MutableStateFlow(CopyDestinationControls())
    val controls = mutableControls.asStateFlow()

    init {
        require(runtimeId.isNotBlank() && requestId.isNotBlank() && sources.isNotEmpty())
        require(sources.all { it.endpoint.runtimeId == runtimeId })
    }

    internal fun lockSources(locked: Boolean) { mutableSourcesLocked.value = locked }

    internal fun replaceSources(sources: List<CopyFileSnapshot>) {
        check(!sourcesLocked.value) { AppStrings.copy_destination_sources_locked }
        require(sources.all { it.endpoint.runtimeId == runtimeId })
        check(sources.none { source -> selected.value.any { source.isCopyIntoSelf(it.directory) } }) {
            AppStrings.ui_drop_copy_self_path
        }
        mutableSources.value = sources.toList()
    }

    fun belongsToRuntime(currentRuntimeId: String): Boolean = runtimeId == currentRuntimeId

    fun rememberScroll(index: Int, offset: Int) {
        mutableControls.value = controls.value.copy(firstVisibleItemIndex = index, firstVisibleItemOffset = offset)
    }

    fun visibleDestinations(candidates: List<CopyDestination>): List<CopyDestination> =
        candidates.filter { it.directory.endpoint.endpoint.protocol != FileProtocol.Share }
            .sortedWith(compareBy<CopyDestination> { it.directory.endpoint.endpoint.protocol.ordinal }
                .thenBy { it.connectionName.lowercase() }.thenBy { it.directory.pathKey })

    fun select(destination: CopyDestination): CopyDestinationSelectionResult {
        val target = destination.directory
        if (!belongsToRuntime(target.endpoint.runtimeId)) return CopyDestinationSelectionResult.WrongRuntime
        if (target.endpoint.endpoint.protocol == FileProtocol.Share || !target.isDirectory || destination.unavailableReason != null) {
            return CopyDestinationSelectionResult.Unavailable
        }
        if (selected.value.any { it.directory.sameLocation(target) }) {
            return CopyDestinationSelectionResult.AlreadySelected
        }
        if (selected.value.any { it.directory.contains(target) || target.contains(it.directory) }) {
            return CopyDestinationSelectionResult.OverlappingDirectory
        }
        if (sources.any { it.isCopyIntoSelf(target) }) {
            return CopyDestinationSelectionResult.SourceDestinationConflict
        }
        mutableSelected.value += destination
        return CopyDestinationSelectionResult.Added
    }

    fun selectAll(destinations: List<CopyDestination>): CopyDestinationSelectionResult {
        val previous = selected.value
        destinations.forEach { destination ->
            val result = select(destination)
            if (result != CopyDestinationSelectionResult.Added && result != CopyDestinationSelectionResult.AlreadySelected) {
                mutableSelected.value = previous
                return result
            }
        }
        return if (selected.value == previous) CopyDestinationSelectionResult.AlreadySelected else CopyDestinationSelectionResult.Added
    }

    fun remove(directory: CopyFileSnapshot) {
        mutableSelected.value = selected.value.filterNot { it.directory.sameLocation(directory) }
    }

    fun updateAvailability(directory: CopyFileSnapshot, unavailableReason: String?) {
        mutableSelected.value = selected.value.map { destination ->
            if (destination.directory.sameLocation(directory)) destination.copy(unavailableReason = unavailableReason)
            else destination
        }
    }
}
