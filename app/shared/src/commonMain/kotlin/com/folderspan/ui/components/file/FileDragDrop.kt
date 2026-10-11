package com.folderspan.ui.components.file

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.composed
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationDrawerItemColors
import androidx.compose.material3.NavigationDrawerItemDefaults
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.input.pointer.isPrimaryPressed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import com.folderspan.data.file.FileSimpleInfo
import com.folderspan.ui.state.file.FileDropBounds
import com.folderspan.ui.state.file.FileDropEndpoint
import com.folderspan.ui.state.file.FileDropSessionRegistry
import com.folderspan.ui.state.file.snapshotFileDragSelection
import kotlinx.coroutines.delay

internal fun Modifier.fileDragSource(
    file: FileSimpleInfo,
    selectedFiles: () -> List<FileSimpleInfo>,
    onDrop: (FileDropEndpoint, List<FileSimpleInfo>) -> Unit,
    onStationaryLongPress: (() -> Unit)?,
): Modifier = composed {
    var coordinates by remember { mutableStateOf<LayoutCoordinates?>(null) }
    val latestSelectedFiles by rememberUpdatedState(selectedFiles)
    val latestDrop by rememberUpdatedState(onDrop)
    val latestLongPress by rememberUpdatedState(onStationaryLongPress)
    onGloballyPositioned { coordinates = it }.pointerInput(file.protocol, file.protocolId, file.path) {
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
            val isMouse = down.type == PointerType.Mouse
            if (isMouse && !currentEvent.buttons.isPrimaryPressed) return@awaitEachGesture
            val snapshot = snapshotFileDragSelection(file, latestSelectedFiles())
            var dragging = false
            var cancelled = false
            try {
                while (true) {
                    val event = awaitPointerEvent(PointerEventPass.Initial)
                    val change = event.changes.firstOrNull { it.id == down.id } ?: break
                    if (!change.pressed && change.isConsumed) break
                    val movement = (change.position - down.position).getDistance()
                    val longPressed = change.uptimeMillis - down.uptimeMillis >= viewConfiguration.longPressTimeoutMillis
                    if (!dragging && movement > viewConfiguration.touchSlop) {
                        if (!isMouse && !longPressed) {
                            cancelled = true
                            break
                        }
                        val position = coordinates?.takeIf { it.isAttached }?.localToRoot(change.position) ?: break
                        FileDropSessionRegistry.startInternal(
                            snapshot, position.x, position.y, previewFile = file, isTouch = !isMouse,
                        )
                        dragging = true
                    }
                    if (dragging) {
                        change.consume()
                        val position = coordinates?.takeIf { it.isAttached }?.localToRoot(change.position) ?: break
                        FileDropSessionRegistry.move(position.x, position.y)
                    }
                    if (!change.pressed) {
                        if (dragging) {
                            FileDropSessionRegistry.captureEndpoint()?.let { endpoint -> latestDrop(endpoint, snapshot) }
                            FileDropSessionRegistry.end()
                        } else if (longPressed && !cancelled && !change.isConsumed) {
                            change.consume()
                            latestLongPress?.invoke()
                        }
                        break
                    }
                    if (!dragging && change.isConsumed) break
                }
            } finally {
                if (dragging) FileDropSessionRegistry.end()
            }
        }
    }
}

internal fun Modifier.fileDropTarget(endpoint: FileDropEndpoint, excludeActions: Boolean = false): Modifier = composed {
    val owner = remember { Any() }
    val actionWidth = with(LocalDensity.current) { 48.dp.toPx() }
    DisposableEffect(owner, endpoint) {
        onDispose { FileDropSessionRegistry.unregister(owner) }
    }
    onGloballyPositioned { coordinates ->
        val bounds = coordinates.boundsInRoot()
        FileDropSessionRegistry.register(
            owner, endpoint,
            FileDropBounds(bounds.left, bounds.top, bounds.right - if (excludeActions) actionWidth else 0f, bounds.bottom),
        )
    }
}

@Composable
internal fun fileDropNavigationColors(endpoint: FileDropEndpoint): NavigationDrawerItemColors {
    val session by FileDropSessionRegistry.session.collectAsState()
    return NavigationDrawerItemDefaults.colors(
        unselectedContainerColor = if (session?.hoveredEndpoint == endpoint) {
            MaterialTheme.colorScheme.secondaryContainer
        } else Color.Transparent,
    )
}

internal fun Modifier.expandOnFileDragHover(enabled: Boolean, onExpand: () -> Unit): Modifier = composed {
    var bounds by remember { mutableStateOf<FileDropBounds?>(null) }
    val session by FileDropSessionRegistry.session.collectAsState()
    val latestExpand by rememberUpdatedState(onExpand)
    val hovering = enabled && session?.let { bounds?.contains(it.horizontal, it.vertical) } == true
    LaunchedEffect(hovering) {
        if (hovering) {
            delay(450L)
            latestExpand()
        }
    }
    onGloballyPositioned {
        val rect = it.boundsInRoot()
        bounds = FileDropBounds(rect.left, rect.top, rect.right, rect.bottom)
    }
}

internal fun Modifier.scrollOnFileDrag(state: LazyListState): Modifier = composed {
    var bounds by remember { mutableStateOf<FileDropBounds?>(null) }
    val session by FileDropSessionRegistry.session.collectAsState()
    val edgeSize = with(LocalDensity.current) { 48.dp.toPx() }
    LaunchedEffect(session != null, state) {
        while (FileDropSessionRegistry.session.value != null) {
            val active = FileDropSessionRegistry.session.value
            val viewport = bounds
            if (active != null && viewport?.contains(active.horizontal, active.vertical) == true) {
                val speed = when {
                    active.vertical < viewport.top + edgeSize -> -edgeSize / 5f
                    active.vertical > viewport.bottom - edgeSize -> edgeSize / 5f
                    else -> 0f
                }
                if (speed != 0f) state.scrollBy(speed)
            }
            delay(16L)
        }
    }
    onGloballyPositioned {
        val rect = it.boundsInRoot()
        bounds = FileDropBounds(rect.left, rect.top, rect.right, rect.bottom)
    }
}
