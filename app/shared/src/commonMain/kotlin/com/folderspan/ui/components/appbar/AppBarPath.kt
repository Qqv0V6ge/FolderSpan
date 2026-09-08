package com.folderspan.ui.components.appbar

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.ArrowDropUp
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import com.folderspan.data.file.FileSimpleInfo
import com.folderspan.data.file.PathInfo
import com.folderspan.data.main.share.Share
import com.folderspan.data.main.share.ShareProtocol
import com.folderspan.extensions.parsePath
import com.folderspan.ui.components.buttons.DiskSwitchButton
import com.folderspan.ui.state.file.FileBookmarkState
import com.folderspan.ui.state.file.FileFilterState
import com.folderspan.ui.state.file.FileState
import com.folderspan.ui.state.file.DrawerBookmark
import com.folderspan.ui.state.main.DeviceState
import com.folderspan.ui.state.main.NetworkState
import com.folderspan.utils.NaturalOrderComparator
import com.folderspan.utils.PathUtils
import com.folderspan.utils.scrollToItemAfterFrame
import com.eygraber.uri.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.koin.compose.koinInject


@Composable
fun AppBarPathContainer() {
    val scope = rememberCoroutineScope()

    val fileState = koinInject<FileState>()
    val path by fileState.path.collectAsState()
    val rootPath by fileState.rootPath.collectAsState()
    val rootPaths by fileState.rootPaths.collectAsState()
    val deskType by fileState.deskType.collectAsState()
    val pathSeparator = remember(deskType) { deskType.pathSeparator }
    val deviceState = koinInject<DeviceState>()
    val networkState = koinInject<NetworkState>()
    val fileBookmarkState = koinInject<FileBookmarkState>()
    val fileFilterState = koinInject<FileFilterState>()
    val isHideFile by fileFilterState.isHideFile.collectAsState()
    val deviceItems = deviceState.devices.toList()
    val shareItems = deviceState.shares.toList()
    val connectedNetworkItems = networkState.connectedNetworks

    fun splitPath(value: String): List<String> {
        if (value.isBlank()) return emptyList()
        return if ((deskType is Share && (deskType as Share).protocol == ShareProtocol.System)) {
            listOf((Uri.parse(value).lastPathSegment ?: "/").split("/").last())
        } else {
            runCatching { value.parsePath() }.getOrDefault(emptyList())
        }
    }

    val rootSegments = remember(rootPath.path, pathSeparator, deskType) {
        splitPath(rootPath.path)
    }

    val newPath = path.replaceFirst(rootPath.path, "")

    val allSegments = remember(newPath, pathSeparator, deskType) {
        splitPath(newPath)
    }
    val paths = remember(rootSegments, allSegments) {
        if (allSegments.size >= rootSegments.size &&
            allSegments.take(rootSegments.size) == rootSegments
        ) {
            allSegments.drop(rootSegments.size)
        } else {
            allSegments
        }
    }
    val listState = rememberLazyListState((paths.size - 1).coerceAtLeast(0))

    Row {
        DiskSwitchButton(
            deskType = deskType,
            deviceItems = deviceItems,
            shareItems = shareItems,
            connectedNetworkItems = connectedNetworkItems,
            isSmallMode = true,
        ) { protocol, type ->
            fileState.updateDesk(protocol, type)
        }

        LazyRow(state = listState) {
            item {
                RootPathSwitch(
                    rootPath = rootPath,
                    rootPaths = rootPaths,
                    onSelectPath = { selectedPath ->
                        scope.launch(Dispatchers.Default) {
                            fileState.updatePath(selectedPath)
                        }
                    },
                )
            }

            itemsIndexed(
                items = paths,
                key = { index, _ ->
                    buildPath(rootPath.path, paths.subList(0, index + 1), pathSeparator)
                }
            ) { index, text ->
                val nowPath = buildPath(rootPath.path, paths.subList(0, index), pathSeparator)
                PathSwitchContainer(
                    name = text,
                    path = nowPath,
                    isHideFile = isHideFile,
                    bookmarks = fileBookmarkState.bookmarks.toList(),
                    loadFileAndFolder = fileState::getFileAndFolder,
                    onClick = {
                        scope.launch(Dispatchers.Default) {
                            if (!(deskType is Share && (deskType as Share).protocol == ShareProtocol.System)) {
                                val newPath = buildPath(rootPath.path, paths.subList(0, index + 1), pathSeparator)
                                fileState.updatePath(newPath)
                            }
                        }
                    },
                    onSelected = { selectedPath ->
                        scope.launch(Dispatchers.Default) {
                            fileState.updatePath(selectedPath)
                        }
                    }
                )
            }
        }
    }

    LaunchedEffect(paths) {
        listState.scrollToItemAfterFrame(paths.size)
    }
}

/**
 * 根路径切换组件，用于显示根路径的切换按钮
 */
@Composable
fun RootPathSwitch(
    rootPath: PathInfo,
    rootPaths: List<PathInfo>,
    onSelectPath: (String) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    val displayText = remember(rootPath.path) {
        middleEllipsize(rootPath.path, PATH_SWITCH_LABEL_MAX_CHARS)
    }

    Box(modifier = Modifier.wrapContentSize(Alignment.TopStart)) {
        FilterChip(
            selected = false,
            label = { Text(displayText, maxLines = 1, softWrap = false) },
            border = null,
            shape = RoundedCornerShape(25.dp),
            leadingIcon = {
                Icon(
                    Icons.Default.Folder,
                    null
                )
            },
            trailingIcon = if (rootPaths.size > 1) {
                {
                    Icon(
                        if (expanded) Icons.Default.ArrowDropUp else Icons.Default.ArrowDropDown,
                        null,
                        Modifier.clip(RoundedCornerShape(25.dp)).clickable { expanded = !expanded }
                    )
                }
            } else {
                null
            },
            onClick = { onSelectPath(rootPath.path) }
        )

        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
        ) {
            for ((path) in rootPaths) {
                DropdownMenuItem(
                    text = { Text(path) },
                    onClick = {
                        onSelectPath(path)
                        expanded = false
                    })
            }
        }
    }
}

private fun buildPath(base: String, segments: List<String>, separator: String): String {
    if (segments.isEmpty()) {
        return base
    }
    val normalizedBase = when {
        base.isEmpty() -> ""
        base.endsWith(separator) -> base
        else -> base + separator
    }
    return normalizedBase + segments.joinToString(separator)
}

@Immutable
internal data class PathSwitchDropdownItem(
    val name: String,
    val path: String,
)

@Immutable
internal data class PathSwitchDropdownUiState(
    val items: List<PathSwitchDropdownItem>,
)

/**
 * 路径切换组件，用于显示路径的切换按钮
 *
 * @param name 组件显示的名称
 * @param path 要切换的路径
 * @param selected 是否选中状态，默认为false
 * @param onClick 点击事件回调函数
 * @param onSelected 选中回调函数，参数为选中的路径
 */
@Composable
internal fun PathSwitchContainer(
    name: String,
    path: String,
    isHideFile: Boolean,
    bookmarks: List<DrawerBookmark>,
    loadFileAndFolder: suspend (String) -> Result<List<FileSimpleInfo>>,
    selected: Boolean = false,
    onClick: () -> Unit,
    onSelected: (String) -> Unit
) {
    var fileInfos by remember { mutableStateOf<List<FileSimpleInfo>>(listOf()) }
    val currentLoadFileAndFolder by rememberUpdatedState(loadFileAndFolder)

    LaunchedEffect(path, isHideFile) {
        val fileAndFolder = currentLoadFileAndFolder(path)
        if (fileAndFolder.isSuccess) {
            fileInfos = (fileAndFolder.getOrNull() ?: emptyList())
                .filter { item -> item.isDirectory }
                .filter { item -> item.isHidden == isHideFile }
                .sortedWith(NaturalOrderComparator())
        }
    }

    // 计算当前“面包屑”节点的完整路径，用于与书签路径比对
    val separator = PathUtils.getPathSeparator()
    val fullPath = remember(path, name) {
        if (path.endsWith(separator)) path + name else path + separator + name
    }

    // 尝试按路径命中书签：命中后替换显示名称，并准备左侧图标
    val matchedBookmark = bookmarks.firstOrNull { item -> item.path == fullPath }
    var displayName = name
    if (matchedBookmark?.name != null) {
        displayName = "${matchedBookmark.name}(${name})"
    }
    // 根据书签类型选择对应图标
    val leadingIcon: (@Composable (() -> Unit))? = matchedBookmark?.let { bookmark ->
        { Icon(bookmark.icon(), null) }
    }

    val dropdownUiState = remember(fileInfos) {
        PathSwitchDropdownUiState(
            items = fileInfos.map { item ->
                PathSwitchDropdownItem(
                    name = item.name,
                    path = item.path,
                )
            }
        )
    }

    PathSwitch(displayName, dropdownUiState, selected, onClick, onSelected, leadingIcon)
}

/**
 * 渲染路径切换组件
 *
 * @param name 组件显示的名称
 * @param selected 是否选中状态
 * @param onClick 点击事件回调函数
 * @param onSelected 选中回调函数，参数为选中的路径
 * @param leadingIcon 左侧图标插槽（命中书签时显示）
 */
@Composable
internal fun PathSwitch(
    name: String,
    dropdownUiState: PathSwitchDropdownUiState,
    selected: Boolean,
    onClick: () -> Unit,
    onSelected: (String) -> Unit,
    leadingIcon: (@Composable (() -> Unit))? = null
) {
    var expanded by remember { mutableStateOf(false) }
    val displayText = remember(name) {
        middleEllipsize(name, PATH_SWITCH_LABEL_MAX_CHARS)
    }

    Box(modifier = Modifier.wrapContentSize(Alignment.TopStart)) {
        FilterChip(
            selected = selected,
            label = { Text(displayText, maxLines = 1, softWrap = false) },
            border = null,
            shape = RoundedCornerShape(25.dp),
            leadingIcon = leadingIcon,
            trailingIcon = if (dropdownUiState.items.size > 1) {
                {
                    Icon(
                        if (expanded) Icons.Default.ArrowDropUp else Icons.Default.ArrowDropDown,
                        null,
                        Modifier.clip(RoundedCornerShape(25.dp)).clickable { expanded = !expanded }
                    )
                }
            } else {
                null
            },
            onClick = onClick
        )

        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
        ) {
            for ((pathName, path) in dropdownUiState.items) {
                DropdownMenuItem(
                    text = { Text(pathName) },
                    onClick = {
                        onSelected(path)
                        expanded = false
                    })
            }
        }
    }
}

@Composable
internal fun RenderPathSwitch(
    name: String,
    dropdownUiState: PathSwitchDropdownUiState,
    selected: Boolean,
    onClick: () -> Unit,
    onSelected: (String) -> Unit,
    leadingIcon: (@Composable (() -> Unit))? = null,
) {
    PathSwitch(name, dropdownUiState, selected, onClick, onSelected, leadingIcon)
}

private const val PATH_SWITCH_LABEL_MAX_CHARS = 24
private const val PATH_SWITCH_ELLIPSIS = "..."

private fun middleEllipsize(text: String, maxChars: Int): String {
    if (maxChars <= 0) {
        return ""
    }
    if (text.length <= maxChars) {
        return text
    }
    if (maxChars <= PATH_SWITCH_ELLIPSIS.length) {
        return text.take(maxChars)
    }
    val remaining = maxChars - PATH_SWITCH_ELLIPSIS.length
    val headCount = remaining / 2 + (remaining % 2)
    val tailCount = remaining / 2
    return text.take(headCount) + PATH_SWITCH_ELLIPSIS + text.takeLast(tailCount)
}
