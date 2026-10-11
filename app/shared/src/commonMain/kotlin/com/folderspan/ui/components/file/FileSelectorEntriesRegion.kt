package com.folderspan.ui.components.file

import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.RadioButtonUnchecked
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.folderspan.data.file.FileSimpleInfo
import com.folderspan.extensions.formatFileSize
import com.folderspan.extensions.timestampToSyncDate
import kotlinx.coroutines.flow.distinctUntilChanged
import strings.AppStrings

internal const val FILE_SELECTOR_LIST_TEST_TAG = "file-selector-list"
internal const val FILE_SELECTOR_GRID_TEST_TAG = "file-selector-grid"
private val FileSelectorGridMinTileWidth = 280.dp
private val FileSelectorGridMinTileHeight = 168.dp
private val FileSelectorResponsiveBreakpoint = 600.dp

internal fun fileSelectorEntryTestTag(path: String): String = "file-selector-entry:$path"

private data class FileSelectorScrollPosition(
    val index: Int,
    val offset: Int,
)

@Composable
internal fun FileSelectorEntriesRegion(
    entries: List<FileSelectorEntryUiModel>,
    path: String,
    onActivate: (FileSelectorEntryUiModel) -> Unit,
    onToggleSelection: (FileSelectorEntryUiModel) -> Unit,
    modifier: Modifier = Modifier,
) {
    val scrollPositions = remember { mutableMapOf<String, FileSelectorScrollPosition>() }
    val listState = rememberLazyListState()
    val gridState = rememberLazyGridState()

    BoxWithConstraints(modifier = modifier) {
        val useGrid = maxWidth >= FileSelectorResponsiveBreakpoint
        LaunchedEffect(path, useGrid, entries.size) {
            val scrollPosition = scrollPositions[path] ?: FileSelectorScrollPosition(0, 0)
            if (entries.isNotEmpty()) {
                val restoredIndex = scrollPosition.index.coerceIn(0, entries.lastIndex)
                if (useGrid) {
                    gridState.scrollToItem(restoredIndex, scrollPosition.offset)
                } else {
                    listState.scrollToItem(restoredIndex, scrollPosition.offset)
                }
            }
            if (useGrid) {
                snapshotFlow {
                    FileSelectorScrollPosition(
                        index = gridState.firstVisibleItemIndex,
                        offset = gridState.firstVisibleItemScrollOffset,
                    )
                }.distinctUntilChanged().collect { position ->
                    scrollPositions[path] = position
                }
            } else {
                snapshotFlow {
                    FileSelectorScrollPosition(
                        index = listState.firstVisibleItemIndex,
                        offset = listState.firstVisibleItemScrollOffset,
                    )
                }.distinctUntilChanged().collect { position ->
                    scrollPositions[path] = position
                }
            }
        }

        if (useGrid) {
            LazyVerticalGrid(
                columns = GridCells.Adaptive(FileSelectorGridMinTileWidth),
                state = gridState,
                modifier = Modifier
                    .fillMaxSize()
                    .testTag(FILE_SELECTOR_GRID_TEST_TAG),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                items(
                    items = entries,
                    key = { entry -> entry.file.path },
                    contentType = { "file-selector-grid-entry" },
                ) { entry ->
                    FileSelectorGridTile(
                        entry = entry,
                        onActivate = { onActivate(entry) },
                        onToggleSelection = { onToggleSelection(entry) },
                    )
                }
            }
        } else {
            LazyColumn(
                state = listState,
                modifier = Modifier
                    .fillMaxSize()
                    .testTag(FILE_SELECTOR_LIST_TEST_TAG),
            ) {
                items(
                    items = entries,
                    key = { entry -> entry.file.path },
                    contentType = { "file-selector-list-entry" },
                ) { entry ->
                    FileSelectorListItem(
                        entry = entry,
                        onActivate = { onActivate(entry) },
                        onToggleSelection = { onToggleSelection(entry) },
                    )
                }
            }
        }
    }
}

@Composable
private fun FileSelectorListItem(
    entry: FileSelectorEntryUiModel,
    onActivate: () -> Unit,
    onToggleSelection: () -> Unit,
) {
    val rejectionMessage = entry.selectionRejection?.localizedMessage()
    FileCard(
        file = entry.file,
        isSelected = entry.isSelected,
        isSelectionMode = false,
        modifier = Modifier
            .fillMaxWidth()
            .testTag(fileSelectorEntryTestTag(entry.file.path))
            .semantics {
                selected = entry.isSelected
                role = Role.Button
                rejectionMessage?.let { message -> stateDescription = message }
            },
        onToggleSelect = onActivate,
        onClick = onActivate,
        trailingContent = {
            FileSelectorSelectionAffordance(
                entry = entry,
                rejectionMessage = rejectionMessage,
                onToggleSelection = onToggleSelection,
            )
        },
    )
}

@Composable
private fun FileSelectorGridTile(
    entry: FileSelectorEntryUiModel,
    onActivate: () -> Unit,
    onToggleSelection: () -> Unit,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isHovered by interactionSource.collectIsHoveredAsState()
    var isFocused by remember { mutableStateOf(false) }
    val rejectionMessage = entry.selectionRejection?.localizedMessage()
    val metadata = rememberFileSelectorMetadata(entry.file)
    val shape = MaterialTheme.shapes.medium
    val containerColor = when {
        entry.isSelected -> MaterialTheme.colorScheme.secondaryContainer
        isHovered -> MaterialTheme.colorScheme.surfaceContainerHighest
        else -> Color.Transparent
    }
    val contentColor = if (entry.isSelected) {
        MaterialTheme.colorScheme.onSecondaryContainer
    } else {
        MaterialTheme.colorScheme.onSurface
    }
    val kindLabel = if (entry.file.isDirectory) AppStrings.ui_folder else AppStrings.ui_file
    val accessibilityDescription = remember(entry.file.name, kindLabel, metadata) {
        listOfNotNull(kindLabel, entry.file.name, metadata).joinToString(", ")
    }
    val focusModifier = if (isFocused) {
        Modifier.border(2.dp, MaterialTheme.colorScheme.primary, shape)
    } else {
        Modifier
    }

    CompositionLocalProvider(LocalContentColor provides contentColor) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = FileSelectorGridMinTileHeight)
                .testTag(fileSelectorEntryTestTag(entry.file.path))
                .semantics(mergeDescendants = true) {
                    contentDescription = accessibilityDescription
                    role = Role.Button
                    selected = entry.isSelected
                    rejectionMessage?.let { message -> stateDescription = message }
                }
                .clip(shape)
                .background(containerColor)
                .then(focusModifier)
                .hoverable(interactionSource)
                .onFocusChanged { focusState -> isFocused = focusState.isFocused }
                .clickable(
                    interactionSource = interactionSource,
                    indication = LocalIndication.current,
                    onClick = onActivate,
                )
                .padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 52.dp),
            ) {
                FileIcon(
                    file = entry.file,
                    modifier = Modifier
                        .align(Alignment.Center)
                        .size(48.dp),
                )
                Box(modifier = Modifier.align(Alignment.TopEnd)) {
                    FileSelectorSelectionAffordance(
                        entry = entry,
                        rejectionMessage = rejectionMessage,
                        onToggleSelection = onToggleSelection,
                    )
                }
            }
            Text(
                text = entry.file.name,
                modifier = Modifier.fillMaxWidth(),
                style = MaterialTheme.typography.titleMedium,
                textAlign = TextAlign.Center,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            metadata?.let { text ->
                Text(
                    text = text,
                    modifier = Modifier.fillMaxWidth(),
                    style = MaterialTheme.typography.bodySmall,
                    color = if (entry.isSelected) {
                        contentColor.copy(alpha = 0.8f)
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                    textAlign = TextAlign.Center,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
private fun FileSelectorSelectionAffordance(
    entry: FileSelectorEntryUiModel,
    rejectionMessage: String?,
    onToggleSelection: () -> Unit,
) {
    val selectionDescription = when {
        rejectionMessage != null -> rejectionMessage
        entry.isSelected -> AppStrings.ui_deselect
        else -> AppStrings.ui_select_file
    }
    IconButton(
        onClick = onToggleSelection,
        enabled = rejectionMessage == null,
        modifier = Modifier.semantics {
            contentDescription = selectionDescription
            rejectionMessage?.let { message -> stateDescription = message }
        },
    ) {
        Icon(
            imageVector = when {
                rejectionMessage != null -> Icons.Default.Block
                entry.isSelected -> Icons.Default.CheckCircle
                else -> Icons.Default.RadioButtonUnchecked
            },
            contentDescription = null,
        )
    }
}

@Composable
private fun rememberFileSelectorMetadata(file: FileSimpleInfo): String? {
    val sizeText = remember(file.size, file.isDirectory) {
        file.size.takeIf { size -> size >= 0 }?.let { size ->
            if (file.isDirectory) {
                AppStrings.ui_arg0_items.format(arg0 = size.toString())
            } else {
                size.formatFileSize()
            }
        }
    }
    val dateText = remember(file.createdDate) {
        file.createdDate.takeIf { timestamp -> timestamp >= 0 }?.timestampToSyncDate()
    }
    return remember(sizeText, dateText) {
        listOfNotNull(sizeText, dateText).joinToString(" · ").ifEmpty { null }
    }
}
