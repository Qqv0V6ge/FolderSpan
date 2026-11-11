package com.folderspan.ui.screen.file

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.ui.tooling.preview.Preview
import com.folderspan.data.file.FileProtocol
import com.folderspan.data.file.FileSimpleInfo
import com.folderspan.service.mcp.file.FileEndpointRef
import com.folderspan.ui.state.file.*

internal fun sampleCopyDestinationState(): Pair<CopyDestinationState, List<CopyDestination>> {
    fun endpoint(type: FileProtocol) = CopyEndpointIdentity("preview", FileEndpointRef(type, if (type == FileProtocol.Local) "" else type.name), type.name, "/")
    val local = endpoint(FileProtocol.Local)
    val sources = listOf(
        FileSimpleInfo.pathFileSimpleInfo("/Pictures/photo.jpg").copy(name = "photo.jpg", isDirectory = false, size = 3_200_000),
        FileSimpleInfo.pathFileSimpleInfo("/Documents/项目说明.pdf").copy(name = "项目说明.pdf", isDirectory = false, size = 240_000),
        FileSimpleInfo.pathFileSimpleInfo("/Pictures/旅行照片").copy(name = "旅行照片"),
    )
    val state = CopyDestinationState("preview", sources.map { CopyFileSnapshot(local, it) })
    val targets = listOf(
        Triple(FileProtocol.Local, "本机", "/home/webb"),
        Triple(FileProtocol.Device, "Redmi K30 Pro", "/storage/emulated/0"),
        Triple(FileProtocol.Network, "家庭文件库", "/Archive"),
        Triple(FileProtocol.Local, "照片归档", "/mnt/Archive/Photos/2026/Travel/Originals"),
        Triple(FileProtocol.Device, "存储卡备份", "/storage/1234-5678/Backups"),
        Triple(FileProtocol.Network, "项目资料", "/Projects/FolderSpan/Design/References/Material3"),
    ).map { (type, name, path) ->
        val identity = endpoint(type)
        val file = FileSimpleInfo.pathFileSimpleInfo(path).copy(protocol = type, protocolId = identity.endpoint.sourceId)
        CopyDestination(CopyFileSnapshot(identity, file), name, when (type) {
            FileProtocol.Local -> "本机"
            FileProtocol.Device -> "Redmi K30 Pro"
            else -> "家庭文件库"
        }, freeSpaceBytes = when (path) {
            "/home/webb" -> 128L * 1024 * 1024 * 1024
            "/storage/emulated/0" -> 24L * 1024 * 1024 * 1024
            else -> null
        })
    } + listOf("office-device" to "办公室电脑", "cloud" to "云端备份").map { (id, name) ->
        val type = if (id == "cloud") FileProtocol.Network else FileProtocol.Device
        val identity = CopyEndpointIdentity("preview", FileEndpointRef(type, id), id, "/")
        val path = if (type == FileProtocol.Device) "/home/user" else "/Backups"
        CopyDestination(CopyFileSnapshot(identity, FileSimpleInfo.pathFileSimpleInfo(path).copy(protocol = type, protocolId = id)), name, name)
    }
    state.select(targets.first())
    return state to targets
}

@Composable
internal fun CopyDestinationSample() {
    val (state, targets) = remember { sampleCopyDestinationState() }
    val removed = remember { mutableStateListOf<CopyFileSnapshot>() }
    val controls by state.controls.collectAsState()
    val selected by state.selected.collectAsState()
    val sources by state.sourceSnapshots.collectAsState()
    CopyDestinationPage(controls, targets.distinctBy { it.directory.endpoint }.map { CopyDestinationLoad(it.directory.endpoint, it.connectionName, listOf(it), false) },
        selected, state.visibleDestinations(targets).filterNot { target -> removed.any { it.sameLocation(target.directory) } }, sources, false, emptyList(), null,
        onBack = {}, onScroll = state::rememberScroll,
        onToggle = { target -> if (selected.any { it.directory.sameLocation(target.directory) }) state.remove(target.directory) else state.select(target) },
        onToggleResource = { group -> if (group.all { target -> selected.any { it.directory.sameLocation(target.directory) } }) group.forEach { state.remove(it.directory) } else state.selectAll(group) },
        onRemove = { target -> state.remove(target.directory); removed.add(target.directory) },
        onBrowse = {}, onRetry = {}, onSubmit = {}, onAddSources = {}, onRemoveSource = {})
}

@Preview(name = "Compact", widthDp = 360, heightDp = 800)
@Composable private fun CompactCopyPreview() { MaterialTheme { CopyDestinationSample() } }
@Preview(name = "Short", widthDp = 640, heightDp = 360)
@Composable private fun ShortCopyPreview() { MaterialTheme { CopyDestinationSample() } }
@Preview(name = "Medium", widthDp = 720, heightDp = 900)
@Composable private fun MediumCopyPreview() { MaterialTheme { CopyDestinationSample() } }
@Preview(name = "Expanded", widthDp = 1024, heightDp = 768)
@Composable private fun ExpandedCopyPreview() { MaterialTheme { CopyDestinationSample() } }
@Preview(name = "Large", widthDp = 1366, heightDp = 900)
@Composable private fun LargeCopyPreview() { MaterialTheme { CopyDestinationSample() } }
@Preview(name = "Extra large", widthDp = 1600, heightDp = 900)
@Composable private fun ExtraLargeCopyPreview() { MaterialTheme { CopyDestinationSample() } }
