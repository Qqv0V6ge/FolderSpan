package com.folderspan.ui.components.buttons

import strings.AppStrings

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowRight
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.changedToUpIgnoreConsumed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import com.folderspan.data.file.FileProtocol
import com.folderspan.data.main.DiskBase
import com.folderspan.data.main.Local
import com.folderspan.data.main.device.Device
import com.folderspan.data.main.network.Network
import com.folderspan.data.main.share.Share
import com.folderspan.data.main.share.ShareProtocol
import com.folderspan.extensions.getIcon
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.time.Duration.Companion.milliseconds

@Immutable
internal data class DiskSwitchMenuItemUiState(
    val id: String,
    val title: String,
)

@Immutable
internal data class DiskSwitchMenuUiState(
    val title: String,
    val items: List<DiskSwitchMenuItemUiState>,
)

@Composable
fun DiskSwitchButton(
    deskType: DiskBase,
    deviceItems: List<Device>,
    shareItems: List<Share>,
    connectedNetworkItems: List<Network>,
    isSmallMode: Boolean = false,
    onSelectDesk: (FileProtocol, DiskBase) -> Unit,
) {
    val scope = rememberCoroutineScope()
    var expanded by remember { mutableStateOf(false) }
    var expandedDevice by remember { mutableStateOf(false) }
    var expandedShare by remember { mutableStateOf(false) }
    var expandedNetwork by remember { mutableStateOf(false) }

    val deviceMap = remember(deviceItems) { deviceItems.associateBy { item -> item.id } }
    val shareMap = remember(shareItems) {
        shareItems.associateBy { item -> "${item.protocol}:${item.id}" }
    }
    val networkMap = remember(connectedNetworkItems) {
        connectedNetworkItems.associateBy { item -> item.protocolId }
    }
    val deviceMenuUiState = remember(deviceItems) {
        DiskSwitchMenuUiState(
            title = AppStrings.ui_equipment,
            items = deviceItems.map { item ->
                DiskSwitchMenuItemUiState(
                    id = item.id,
                    title = item.name,
                )
            }
        )
    }
    val shareMenuUiState = remember(shareItems) {
        DiskSwitchMenuUiState(
            title = AppStrings.ui_share,
            items = shareItems.map { item ->
                DiskSwitchMenuItemUiState(
                    id = "${item.protocol}:${item.id}",
                    title = item.name,
                )
            }
        )
    }
    val networkMenuUiState = remember(connectedNetworkItems) {
        DiskSwitchMenuUiState(
            title = AppStrings.ui_network,
            items = connectedNetworkItems.map { item ->
                DiskSwitchMenuItemUiState(
                    id = item.protocolId,
                    title = item.name,
                )
            }
        )
    }

    var lastDeskType by remember { mutableStateOf<DiskBase?>(null) }
    var currentDeskType by remember { mutableStateOf(deskType) }
    var suppressClick by remember { mutableStateOf(false) }
    var suppressResetJob by remember { mutableStateOf<Job?>(null) }

    LaunchedEffect(deskType) {
        if (currentDeskType != deskType) {
            lastDeskType = currentDeskType
            currentDeskType = deskType
        }
    }

    fun isSameDesk(previous: DiskBase, current: DiskBase): Boolean {
        return when (previous) {
            is Local if current is Local -> true
            is Device if current is Device -> previous.id == current.id
            is Share if current is Share ->
                previous.id == current.id && previous.protocol == current.protocol

            is Network if current is Network -> previous.protocolId == current.protocolId
            else -> false
        }
    }

    fun resolvePreviousDesk(): Pair<FileProtocol, DiskBase>? {
        val previous = lastDeskType ?: return null
        if (isSameDesk(previous, deskType)) return null
        return when (previous) {
            is Local -> FileProtocol.Local to previous
            is Device -> deviceItems.firstOrNull { item -> item.id == previous.id }?.let { item ->
                FileProtocol.Device to item
            }

            is Share -> shareItems.firstOrNull { item ->
                item.id == previous.id && item.protocol == previous.protocol
            }?.let { item ->
                FileProtocol.Share to item
            }

            is Network -> connectedNetworkItems.firstOrNull { item -> item.protocolId == previous.protocolId }
                ?.let { item ->
                    FileProtocol.Network to item
                }

            else -> null
        }
    }

    fun suppressNextClick() {
        suppressResetJob?.cancel()
        suppressClick = true
    }

    fun handleLongPress() {
        suppressNextClick()
        val target = resolvePreviousDesk() ?: return
        onSelectDesk(target.first, target.second)
        expanded = false
        expandedDevice = false
        expandedShare = false
        expandedNetwork = false
    }

    fun toggleMenu() {
        if (suppressClick) {
            suppressClick = false
            return
        }
        expanded = !expanded
    }

    val longPressModifier = Modifier.pointerInput(lastDeskType, deskType) {
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
            val up = withTimeoutOrNull(viewConfiguration.longPressTimeoutMillis) {
                var change = down
                while (change.pressed) {
                    val event = awaitPointerEvent(PointerEventPass.Initial)
                    change = event.changes.firstOrNull { item -> item.id == down.id } ?: continue
                    if (change.changedToUpIgnoreConsumed()) return@withTimeoutOrNull change
                }
                change
            }
            if (up == null) {
                handleLongPress()
                var change = down
                while (change.pressed) {
                    val event = awaitPointerEvent(PointerEventPass.Initial)
                    change = event.changes.firstOrNull { item -> item.id == down.id } ?: continue
                    if (change.changedToUpIgnoreConsumed()) break
                }
                suppressResetJob?.cancel()
                suppressResetJob = scope.launch {
                    delay(50.milliseconds)
                    suppressClick = false
                }
            }
        }
    }

    if (
        deviceItems.isEmpty() &&
        shareItems.isEmpty() &&
        connectedNetworkItems.isEmpty()
    ) return

    if (isSmallMode) {
        // Small mode - 只显示图标
        FilledIconButton(
            modifier = longPressModifier,
            onClick = { toggleMenu() },
        ) {
            Icon(
                imageVector = when (deskType) {
                    is Local -> Icons.Default.Folder
                    is Device -> deskType.type.getIcon()
                    is Share -> Icons.Default.Share
                    is Network -> Icons.Default.Language
                    else -> Icons.Default.Folder
                },
                contentDescription = deskType.name,
            )
        }
    } else {
        // Normal mode - 显示完整的 FilterChip
        FilterChip(
            selected = true,
            modifier = longPressModifier,
            label = {
                if (deskType is Share) {
                    Text(
                        if (deskType.protocol == ShareProtocol.System) {
                            deskType.name
                        } else {
                            AppStrings.ui_share_arg0.format(arg0 = deskType.name)
                        }
                    )
                    return@FilterChip
                }
                Text(deskType.name)
            },
            border = null,
            shape = RoundedCornerShape(25.dp),
            trailingIcon = {
                Icon(
                    if (expanded)
                        Icons.Default.ArrowDropUp
                    else
                        Icons.Default.ArrowDropDown,
                    null
                )
            },
            onClick = { toggleMenu() },
        )
    }

    DropdownMenu(
        expanded = expanded,
        onDismissRequest = {
            expanded = false
        }
    ) {
        DropdownMenuItem(
            text = { Text(AppStrings.ui_local) },
            onClick = {
                onSelectDesk(FileProtocol.Local, Local())
                expanded = false
            },
        )

        if (deviceItems.isNotEmpty()) {
            DeskDeviceMenuButton(
                expanded = expandedDevice,
                uiState = deviceMenuUiState,
                onSelect = { itemId ->
                    val item = deviceMap[itemId] ?: return@DeskDeviceMenuButton
                    onSelectDesk(FileProtocol.Device, item)
                    expanded = false
                },
                onDismissRequest = { item -> expandedDevice = item }
            )
        }
        if (shareItems.isNotEmpty()) {
            DeskShareMenuButton(
                expanded = expandedShare,
                uiState = shareMenuUiState,
                onSelect = { itemId ->
                    val item = shareMap[itemId] ?: return@DeskShareMenuButton
                    onSelectDesk(FileProtocol.Share, item)
                    expanded = false
                },
                onDismissRequest = { item -> expandedShare = item }
            )
        }
        if (connectedNetworkItems.isNotEmpty()) {
            DeskNetworkMenuButton(
                expanded = expandedNetwork,
                uiState = networkMenuUiState,
                onSelect = { itemId ->
                    val item = networkMap[itemId] ?: return@DeskNetworkMenuButton
                    onSelectDesk(FileProtocol.Network, item)
                    expanded = false
                },
                onDismissRequest = { item -> expandedNetwork = item }
            )
        }
    }
}

@Composable
private fun DiskSwitchMenuButton(
    expanded: Boolean,
    uiState: DiskSwitchMenuUiState,
    onSelect: (String) -> Unit,
    onDismissRequest: (Boolean) -> Unit,
) {
    Box(Modifier.wrapContentSize(Alignment.TopStart)) {
        DropdownMenuItem(
            text = { Text(uiState.title) },
            onClick = { onDismissRequest(true) },
            trailingIcon = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(uiState.items.size.toString())
                    Icon(
                        Icons.AutoMirrored.Outlined.ArrowRight,
                        contentDescription = null
                    )
                }
            }
        )


        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { onDismissRequest(false) }
        ) {
            for ((id, title) in uiState.items) {
                DropdownMenuItem(
                    text = { Text(title) },
                    onClick = {
                        onSelect(id)
                        onDismissRequest(false)
                    }
                )
            }
        }
    }
}

@Composable
private fun DeskDeviceMenuButton(
    expanded: Boolean,
    uiState: DiskSwitchMenuUiState,
    onSelect: (String) -> Unit,
    onDismissRequest: (Boolean) -> Unit,
) {
    DiskSwitchMenuButton(
        expanded = expanded,
        uiState = uiState,
        onSelect = onSelect,
        onDismissRequest = onDismissRequest
    )
}

@Composable
private fun DeskShareMenuButton(
    expanded: Boolean,
    uiState: DiskSwitchMenuUiState,
    onSelect: (String) -> Unit,
    onDismissRequest: (Boolean) -> Unit,
) {
    DiskSwitchMenuButton(
        expanded = expanded,
        uiState = uiState,
        onSelect = onSelect,
        onDismissRequest = onDismissRequest
    )
}

@Composable
internal fun DeskNetworkMenuButton(
    expanded: Boolean,
    uiState: DiskSwitchMenuUiState,
    onSelect: (String) -> Unit,
    onDismissRequest: (Boolean) -> Unit,
) {
    DiskSwitchMenuButton(
        expanded = expanded,
        uiState = uiState,
        onSelect = onSelect,
        onDismissRequest = onDismissRequest
    )
}
