package com.folderspan.ui.components.file

import strings.AppStrings

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.material3.MaterialTheme.colorScheme
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.folderspan.data.file.FileProtocol
import com.folderspan.data.file.FileSimpleInfo
import com.folderspan.data.file.GetFileFilterType
import com.folderspan.data.file.toIcon
import com.folderspan.data.main.DiskMenuPermission
import com.folderspan.db.FileFavorite
import com.folderspan.extensions.formatFileSize
import com.folderspan.extensions.timestampToSyncDate
import com.folderspan.ui.components.combinedClickableWithContextClick
import com.folderspan.ui.thumbnail.FileThumbnailLoader

private const val HIDDEN_FILE_ALPHA = 0.6f
private const val IGNORED_FILE_ALPHA = 0.38f

enum class FileItemOperationStatus(val label: String) {
    Copy(AppStrings.ui_copying),
    Move(AppStrings.task_status_moving),
    Delete(AppStrings.ui_deleting),
}

@Composable
fun FileCard(
    file: FileSimpleInfo,
    isSelected: Boolean,
    isSelectionMode: Boolean = false,
    isFavorite: Boolean = false,
    operationStatus: FileItemOperationStatus? = null,
    thumbnailLoader: FileThumbnailLoader? = null,
    loadThumbnail: Boolean = false,
    modifier: Modifier = Modifier,
    onToggleSelect: () -> Unit,
    onClick: () -> Unit,
    onLongClick: (() -> Unit)? = null,
    trailingContent: @Composable (() -> Unit)? = null,
) {
    val selectedContainerColor = when (operationStatus) {
        FileItemOperationStatus.Copy -> colorScheme.primaryContainer
        FileItemOperationStatus.Move -> colorScheme.tertiaryContainer
        FileItemOperationStatus.Delete -> colorScheme.errorContainer
        null -> colorScheme.secondaryContainer
    }
    val selectedContentColor = when (operationStatus) {
        FileItemOperationStatus.Copy -> colorScheme.onPrimaryContainer
        FileItemOperationStatus.Move -> colorScheme.onTertiaryContainer
        FileItemOperationStatus.Delete -> colorScheme.onErrorContainer
        null -> colorScheme.onSecondaryContainer
    }
    val containerColor = when {
        operationStatus != null -> selectedContainerColor
        isSelected -> colorScheme.secondaryContainer
        else -> Color.Transparent
    }
    val headlineColor = when {
        operationStatus != null -> selectedContentColor
        isSelected -> colorScheme.onSecondaryContainer
        else -> colorScheme.onSurface
    }
    val supportingColor =
        if (operationStatus != null || isSelected) headlineColor.copy(alpha = 0.8f) else colorScheme.outline
    val itemAlpha = when {
        file.isIgnored -> IGNORED_FILE_ALPHA
        file.isHidden -> HIDDEN_FILE_ALPHA
        else -> 1f
    }
    val createdDateText = remember(file.createdDate) {
        file.createdDate.takeIf { timestamp -> timestamp >= 0 }?.timestampToSyncDate()
    }
    val sizeText = remember(file.size, file.isDirectory) {
        file.size.takeIf { size -> size >= 0 }?.let { size ->
            if (file.isDirectory) AppStrings.ui_arg0_items.format(arg0 = (size).toString()) else size.formatFileSize()
        }
    }
    val accessibilityDescription = remember(
        file.name,
        file.description,
        sizeText,
        createdDateText,
        operationStatus,
    ) {
        listOfNotNull(
            file.name.takeIf(String::isNotEmpty),
            file.description.takeIf(String::isNotEmpty),
            operationStatus?.label,
            sizeText,
            createdDateText,
        ).joinToString(", ")
    }
    val selectionDescription = if (isSelected) AppStrings.ui_deselect else AppStrings.ui_select_file
    val itemModifier = if (itemAlpha < 1f) modifier.alpha(itemAlpha) else modifier

    ListItem(
        colors = ListItemDefaults.colors(
            containerColor = containerColor,
            headlineColor = headlineColor,
            supportingColor = supportingColor
        ),
        overlineContent = if (file.description.isNotEmpty()) {
            {
                Text(
                    text = file.description,
                    modifier = Modifier.clearAndSetSemantics {},
                )
            }
        } else {
//            if (file.isDirectory && bookmark != null) {
//                { Text(bookmark.name) }
//            } else {
            null
//            }
        },
        headlineContent = {
            if (operationStatus != null) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clearAndSetSemantics {},
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    FileOperationBadge(operationStatus)

                    if (file.name.isNotEmpty()) {
                        Text(
                            text = file.name,
                            modifier = Modifier.weight(1f),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            } else if (file.name.isNotEmpty()) {
                Text(
                    text = file.name,
                    modifier = Modifier.clearAndSetSemantics {},
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        },
        supportingContent = {
            Row(
                modifier = Modifier.clearAndSetSemantics {},
                verticalAlignment = Alignment.CenterVertically,
            ) {
                file.protocol.toIcon()

                if (isFavorite) {
                    Icon(
                        Icons.Default.FavoriteBorder,
                        null,
                        Modifier.size(12.dp),
                        tint = colorScheme.primary
                    )
                    Spacer(Modifier.width(12.dp))
                }

                if (sizeText != null) {
                    Text(sizeText, style = MaterialTheme.typography.bodySmall)
                    Spacer(Modifier.width(8.dp))
                }
                if (createdDateText != null) {
                    Text(
                        createdDateText,
                        style = MaterialTheme.typography.bodySmall,
                        color = supportingColor
                    )
                }
            }
        },
        leadingContent = {
            IconButton(
                onClick = onToggleSelect,
                modifier = Modifier.semantics {
                    contentDescription = selectionDescription
                },
            ) {
                when {
                    isSelectionMode -> {
                        Icon(
                            imageVector = if (isSelected) Icons.Default.CheckCircle else Icons.Default.RadioButtonUnchecked,
                            contentDescription = null,
                        )
                    }

                    else -> FileThumbnail(
                        file = file,
                        loader = thumbnailLoader,
                        enabled = loadThumbnail,
                        targetSize = 40.dp,
                        modifier = Modifier.size(40.dp),
                    ) {
                        FileIcon(file)
                    }
                }
            }
        },
        trailingContent = trailingContent,
        modifier = itemModifier
            .combinedClickableWithContextClick(
                onClick = onClick,
                onLongClick = onLongClick,
            )
            .semantics(mergeDescendants = true) {
                contentDescription = accessibilityDescription
            }
    )
}

@Composable
fun FileGridCard(
    file: FileSimpleInfo,
    isSelected: Boolean,
    isSelectionMode: Boolean = false,
    operationStatus: FileItemOperationStatus? = null,
    thumbnailLoader: FileThumbnailLoader? = null,
    loadThumbnail: Boolean = false,
    modifier: Modifier = Modifier,
    onToggleSelect: () -> Unit,
    onClick: () -> Unit,
    onLongClick: (() -> Unit)? = null,
    menuContent: @Composable (() -> Unit)? = null,
) {
    val selectedContainerColor = when (operationStatus) {
        FileItemOperationStatus.Copy -> colorScheme.primaryContainer
        FileItemOperationStatus.Move -> colorScheme.tertiaryContainer
        FileItemOperationStatus.Delete -> colorScheme.errorContainer
        null -> colorScheme.secondaryContainer
    }
    val selectedContentColor = when (operationStatus) {
        FileItemOperationStatus.Copy -> colorScheme.onPrimaryContainer
        FileItemOperationStatus.Move -> colorScheme.onTertiaryContainer
        FileItemOperationStatus.Delete -> colorScheme.onErrorContainer
        null -> colorScheme.onSecondaryContainer
    }
    val containerColor = when {
        operationStatus != null -> selectedContainerColor
        isSelected -> colorScheme.secondaryContainer
        else -> Color.Transparent
    }
    val headlineColor = when {
        operationStatus != null -> selectedContentColor
        isSelected -> colorScheme.onSecondaryContainer
        else -> colorScheme.onSurface
    }
    val itemAlpha = when {
        file.isIgnored -> IGNORED_FILE_ALPHA
        file.isHidden -> HIDDEN_FILE_ALPHA
        else -> 1f
    }
    val accessibilityDescription = file.name.ifEmpty { AppStrings.ui_unnamed }
    val selectionDescription = if (isSelected) AppStrings.ui_deselect else AppStrings.ui_select_file
    val itemModifier = if (itemAlpha < 1f) modifier.alpha(itemAlpha) else modifier

    Card(
        modifier = itemModifier
            .fillMaxWidth()
            .combinedClickableWithContextClick(
                onClick = onClick,
                onLongClick = onLongClick,
            )
            .semantics(mergeDescendants = true) {
                contentDescription = accessibilityDescription
            },
        shape = RoundedCornerShape(0.dp),
        colors = CardDefaults.cardColors(containerColor = containerColor)
    ) {
        Box(Modifier.fillMaxSize()) {
            if (operationStatus != null) {
                Box(
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .padding(top = 5.dp, start = 5.dp)
                ) {
                    FileOperationBadge(operationStatus)
                }
            }

            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 4.dp, vertical = 5.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Spacer(Modifier.weight(1f))
                IconButton(
                    onClick = onToggleSelect,
                    modifier = Modifier
                        .size(52.dp)
                        .semantics {
                            contentDescription = selectionDescription
                        },
                ) {
                    when {
                        isSelectionMode -> {
                            Icon(
                                imageVector = if (isSelected) Icons.Default.CheckCircle else Icons.Default.RadioButtonUnchecked,
                                contentDescription = null,
                                modifier = Modifier.size(34.dp),
                                tint = headlineColor
                            )
                        }

                        else -> FileThumbnail(
                            file = file,
                            loader = thumbnailLoader,
                            enabled = loadThumbnail,
                            targetSize = 52.dp,
                            modifier = Modifier.size(46.dp),
                        ) {
                            FileIcon(
                                file = file,
                                modifier = Modifier.size(34.dp),
                            )
                        }
                    }
                }
                Spacer(Modifier.weight(1f))
                Text(
                    text = file.name.ifEmpty { AppStrings.ui_unnamed },
                    modifier = Modifier.clearAndSetSemantics {},
                    style = MaterialTheme.typography.titleSmall,
                    color = headlineColor,
                    textAlign = TextAlign.Center,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(Modifier.weight(1f))
            }

            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(top = 1.dp, end = 1.dp)
            ) {
                menuContent?.invoke()
            }
        }
    }
}

@Composable
private fun FileOperationBadge(status: FileItemOperationStatus) {
    val (containerColor, contentColor) = when (status) {
        FileItemOperationStatus.Copy -> Pair(
            colorScheme.primary,
            colorScheme.onPrimary
        )

        FileItemOperationStatus.Move -> Pair(
            colorScheme.tertiary,
            colorScheme.onTertiary
        )

        FileItemOperationStatus.Delete -> Pair(
            colorScheme.error,
            colorScheme.onError
        )
    }

    Badge(
        containerColor = containerColor,
        contentColor = contentColor,
    ) {
        Text(
            text = status.label,
            style = MaterialTheme.typography.labelSmall,
            maxLines = 1
        )
    }
}

@Composable
fun FileFavoriteCard(
    favorite: FileFavorite,
    onClick: () -> Unit,
    onFixed: () -> Unit,
    onRemove: () -> Unit,
    isSelectionMode: Boolean = false,
    isSelected: Boolean = false,
    onToggleSelect: () -> Unit = {},
) {
    val isSelectedHighlight = isSelectionMode && isSelected
    val sizeText = remember(favorite.size, favorite.isDirectory) {
        favorite.size.takeIf { size -> size >= 0 }?.let { size ->
            if (favorite.isDirectory) AppStrings.ui_arg0_items.format(arg0 = (size).toString()) else size.formatFileSize()
        }
    }
    val createdDateText = remember(favorite.createdDate) {
        favorite.createdDate.takeIf { timestamp -> timestamp >= 0 }?.timestampToSyncDate()
    }
    val iconFile = remember(favorite.isDirectory, favorite.mineType) {
        FileSimpleInfo(
            name = "",
            description = "",
            isDirectory = favorite.isDirectory,
            isHidden = false,
            path = "",
            mineType = favorite.mineType,
            size = 0,
            createdDate = 0,
            updatedDate = 0,
        )
    }
    val (containerColor, headlineColor, supportingColor) = when {
        isSelectedHighlight -> Triple(
            colorScheme.secondaryContainer,
            colorScheme.onSecondaryContainer,
            colorScheme.onSecondaryContainer.copy(alpha = 0.8f)
        )

        favorite.isFixed -> Triple(
            colorScheme.secondaryContainer,
            colorScheme.onSecondaryContainer,
            colorScheme.onSecondaryContainer.copy(alpha = 0.8f)
        )

        else -> Triple(
            Color.Transparent,
            colorScheme.onSurface,
            colorScheme.outline
        )
    }

    ListItem(
        headlineContent = { if (favorite.name.isNotEmpty()) Text(favorite.name) },
        supportingContent = {
            Column {
                Row(verticalAlignment = Alignment.CenterVertically) {
//                    Text(favorite.user, style = MaterialTheme.typography.bodySmall)
//                    Spacer(Modifier.width(8.dp))
//                    Text(favorite.userGroup, style = MaterialTheme.typography.bodySmall)
//                    Spacer(Modifier.width(8.dp))
                    if (sizeText != null) {
                        Text(sizeText, style = MaterialTheme.typography.bodySmall)
                        Spacer(Modifier.width(8.dp))
                    }
                    if (createdDateText != null) {
                        Text(createdDateText, style = MaterialTheme.typography.bodySmall)
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    favorite.protocol.toIcon()
                    Text(
                        favorite.path,
                        style = MaterialTheme.typography.bodySmall,
                        maxLines = 1,
                        color = supportingColor
                    )
                }
            }
        },
        colors = ListItemDefaults.colors(
            containerColor = containerColor,
            headlineColor = headlineColor,
            supportingColor = supportingColor
        ),
        leadingContent = {
            IconButton(onClick = onToggleSelect) {
                if (isSelectionMode) {
                    Icon(
                        imageVector = if (isSelected) Icons.Default.CheckCircle else Icons.Default.RadioButtonUnchecked,
                        contentDescription = if (isSelected) AppStrings.ui_deselect else AppStrings.ui_select_favorites
                    )
                } else {
                    FileIcon(file = iconFile)
                }
            }
        },
        trailingContent = if (isSelectionMode) {
            null
        } else {
            { FileFavoriteCardMenu(favorite = favorite, onFixed = onFixed, onRemove = onRemove) }
        },
        modifier = Modifier.clickable(onClick = onClick)
    )
}

@Composable
fun FileIcon(
    file: FileSimpleInfo,
    modifier: Modifier = Modifier,
) {
    GetFileFilterType(
        type = file.fileFilterType,
        modifier = modifier,
    )
}

internal fun shouldRequireNavigatorForFileCardMenuComposition(): Boolean = false

internal fun resolveFileCardMenuPermission(
    basePermission: DiskMenuPermission,
    file: FileSimpleInfo,
    selectedFiles: Collection<FileSimpleInfo>,
    isPasteCopyFile: Boolean,
    isPasteMoveFile: Boolean,
    hasNavigator: Boolean,
): DiskMenuPermission {
    val currentSelectionDisallowsPaste =
        selectedFiles.size > 1 || selectedFiles.any { item -> item.isDirectory }
    return resolveFileCardMenuPermission(
        basePermission = basePermission,
        file = file,
        isFileChecked = selectedFiles.contains(file),
        currentSelectionDisallowsPaste = currentSelectionDisallowsPaste,
        isPasteCopyFile = isPasteCopyFile,
        isPasteMoveFile = isPasteMoveFile,
        hasNavigator = hasNavigator,
    )
}

internal fun hasAnyFileCardMenuPermission(
    basePermission: DiskMenuPermission,
    file: FileSimpleInfo,
    isFileChecked: Boolean,
    currentSelectionDisallowsPaste: Boolean,
    isPasteCopyFile: Boolean,
    isPasteMoveFile: Boolean,
    hasNavigator: Boolean,
): Boolean {
    if (
        basePermission.delete ||
        basePermission.rename ||
        basePermission.favorite ||
        basePermission.info ||
        (basePermission.share && hasNavigator)
    ) {
        return true
    }
    if (basePermission.read && !file.isDirectory && hasNavigator) {
        return true
    }

    val applyPasteState = file.protocol != FileProtocol.Share
    val canPaste = basePermission.paste &&
        applyPasteState &&
        (isPasteCopyFile || isPasteMoveFile) &&
        !isFileChecked &&
        !currentSelectionDisallowsPaste
    val canCopyOrMove = (basePermission.copy || basePermission.move) &&
        (!applyPasteState || (!isPasteCopyFile && !isPasteMoveFile)) &&
        !isFileChecked
    return canPaste || canCopyOrMove
}

internal fun resolveFileCardMenuPermission(
    basePermission: DiskMenuPermission,
    file: FileSimpleInfo,
    isFileChecked: Boolean,
    currentSelectionDisallowsPaste: Boolean,
    isPasteCopyFile: Boolean,
    isPasteMoveFile: Boolean,
    hasNavigator: Boolean,
): DiskMenuPermission {
    val applyPasteState = file.protocol != FileProtocol.Share
    val canReadFile = basePermission.read && !file.isDirectory && hasNavigator

    return DiskMenuPermission(
        read = canReadFile,
        write = canReadFile && basePermission.write,
        paste = basePermission.paste &&
                applyPasteState &&
                (isPasteCopyFile || isPasteMoveFile) &&
                !isFileChecked &&
                !currentSelectionDisallowsPaste,
        copy = basePermission.copy &&
                (!applyPasteState || (!isPasteCopyFile && !isPasteMoveFile)) &&
                !isFileChecked,
        move = basePermission.move &&
                (!applyPasteState || (!isPasteCopyFile && !isPasteMoveFile)) &&
                !isFileChecked,
        delete = basePermission.delete,
        rename = basePermission.rename,
        setting = false,
        favorite = basePermission.favorite,
        share = basePermission.share && hasNavigator,
        info = basePermission.info,
    )
}

@Composable
fun FileMenu(
    permission: DiskMenuPermission? = null,
    isFavorite: Boolean = false,
    showTrigger: Boolean = true,
    expanded: Boolean? = null,
    onExpandedChange: ((Boolean) -> Unit)? = null,
    onPaste: () -> Unit = {},
    onCopy: () -> Unit = {},
    onMove: () -> Unit = {},
    onDelete: () -> Unit = {},
    onRead: () -> Unit = {},
    onRename: () -> Unit = {},
    onFavorite: () -> Unit = {},
    onShare: () -> Unit = {},
    onInfo: () -> Unit = {},
) {
    var internalExpanded by remember { mutableStateOf(false) }
    val menuExpanded = expanded ?: internalExpanded
    val updateExpanded: (Boolean) -> Unit = { value ->
        if (expanded != null && onExpandedChange != null) {
            onExpandedChange(value)
        } else {
            internalExpanded = value
        }
    }
    val resolvedPermission = permission ?: DiskMenuPermission()

    Box(Modifier.wrapContentSize(Alignment.TopStart)) {
        if (showTrigger) {
            FileMenuTrigger(onClick = { updateExpanded(true) })
        }

        DropdownMenu(
            expanded = menuExpanded,
            onDismissRequest = { updateExpanded(false) }
        ) {
            FileMenuItems(
                permission = resolvedPermission,
                isFavorite = isFavorite,
                onMenuDismiss = { updateExpanded(false) },
                onPaste = onPaste,
                onCopy = onCopy,
                onMove = onMove,
                onDelete = onDelete,
                onRead = onRead,
                onRename = onRename,
                onFavorite = onFavorite,
                onShare = onShare,
                onInfo = onInfo
            )
        }
    }
}

@Composable
internal fun FileMenuTrigger(onClick: () -> Unit) {
    IconButton(
        onClick = onClick,
        modifier = Modifier.semantics {
            contentDescription = AppStrings.ui_more_actions
        },
    ) {
        Icon(
            Icons.Filled.MoreVert,
            contentDescription = null,
        )
    }
}

@Composable
internal fun FileMenuItems(
    permission: DiskMenuPermission,
    isFavorite: Boolean = false,
    onMenuDismiss: () -> Unit = {},
    onPaste: () -> Unit = {},
    onCopy: () -> Unit = {},
    onMove: () -> Unit = {},
    onDelete: () -> Unit = {},
    onRead: () -> Unit = {},
    onRename: () -> Unit = {},
    onFavorite: () -> Unit = {},
    onShare: () -> Unit = {},
    onInfo: () -> Unit = {},
) {
    val hasFileOperations = permission.copy || permission.move || permission.delete
    val hasConfigOperations = permission.read || permission.rename
    val hasShareOperations = permission.favorite || permission.share
    val hasItemsBeforeConfig = permission.paste || hasFileOperations
    val hasItemsBeforeShare = hasItemsBeforeConfig || hasConfigOperations
    val hasItemsBeforeInfo = hasItemsBeforeShare || hasShareOperations

    if (permission.paste) {
        DropdownMenuItem(
            text = { Text(AppStrings.ui_paste) },
            onClick = {
                onMenuDismiss()
                onPaste()
            },
            leadingIcon = {
                Icon(Icons.Outlined.ContentPaste, null)
            })
    }

    if (permission.paste && (hasFileOperations || hasConfigOperations || hasShareOperations || permission.info)) {
        HorizontalDivider()
    }

    if (permission.copy) {
        DropdownMenuItem(
            text = { Text(AppStrings.ui_copy) },
            onClick = {
                onMenuDismiss()
                onCopy()
            },
            leadingIcon = {
                Icon(Icons.Outlined.FileCopy, null)
            })
    }

    if (permission.move) {
        DropdownMenuItem(
            text = { Text(AppStrings.ui_move) },
            onClick = {
                onMenuDismiss()
                onMove()
            },
            leadingIcon = {
                Icon(Icons.Outlined.ContentCut, null)
            })
    }

    if (permission.delete) {
        DropdownMenuItem(
            text = { Text(AppStrings.ui_delete) },
            onClick = {
                onMenuDismiss()
                onDelete()
            },
            leadingIcon = {
                Icon(Icons.Outlined.Delete, null)
            })
    }

    if (hasItemsBeforeConfig && hasConfigOperations) {
        HorizontalDivider()
    }

    if (permission.read) {
        DropdownMenuItem(
            text = { Text(if (permission.write) AppStrings.ui_edit else AppStrings.ui_view) },
            onClick = {
                onMenuDismiss()
                onRead()
            },
            leadingIcon = {
                Icon(
                    if (permission.write) Icons.Outlined.Edit else Icons.Outlined.Visibility,
                    contentDescription = null,
                )
            }
        )
    }

    if (permission.rename) {
        DropdownMenuItem(
            text = { Text(AppStrings.ui_rename) },
            onClick = {
                onMenuDismiss()
                onRename()
            },
            leadingIcon = {
                Icon(Icons.Outlined.DriveFileRenameOutline, null)
            })
    }

    if (hasItemsBeforeShare && hasShareOperations) {
        HorizontalDivider()
    }

    if (permission.favorite) {
        DropdownMenuItem(
            text = { Text(AppStrings.ui_collection) },
            onClick = {
                onMenuDismiss()
                onFavorite()
            },
            leadingIcon = {
                Icon(
                    if (isFavorite)
                        Icons.Outlined.Favorite
                    else
                        Icons.Outlined.FavoriteBorder,
                    contentDescription = null
                )
            })
    }

    if (permission.share) {
        DropdownMenuItem(
            text = { Text(AppStrings.ui_share) },
            onClick = {
                onMenuDismiss()
                onShare()
            },
            leadingIcon = {
                Icon(Icons.Outlined.Share, null)
            })
    }

    if (hasItemsBeforeInfo && permission.info) {
        HorizontalDivider()
    }

    if (permission.info) {
        DropdownMenuItem(
            text = { Text(AppStrings.ui_properties) },
            onClick = {
                onMenuDismiss()
                onInfo()
            },
            leadingIcon = {
                Icon(Icons.Outlined.Info, null)
            })
    }
}

@Composable
fun FileFavoriteCardMenu(
    favorite: FileFavorite,
    onFixed: () -> Unit,
    onRemove: () -> Unit
) {
    var expanded by remember { mutableStateOf(false) }

    Box(Modifier.wrapContentSize(Alignment.TopStart)) {
        Icon(
            Icons.Filled.MoreVert,
            AppStrings.ui_more,
            modifier = Modifier
                .clip(RoundedCornerShape(25.dp))
                .clickable { expanded = true }
        )
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false }
        ) {
            DropdownMenuItem(
                text = {
                    if (favorite.isFixed) {
                        Text(AppStrings.ui_cancel)
                    } else {
                        Text(AppStrings.ui_fixed)
                    }
                },
                onClick = {
                    onFixed()
                    expanded = false
                },
                leadingIcon = {
                    Icon(
                        Icons.Outlined.VerticalAlignTop,
                        contentDescription = null
                    )
                })
            DropdownMenuItem(
                text = { Text(AppStrings.ui_delete) },
                onClick = {
                    onRemove()
                    expanded = false
                },
                leadingIcon = {
                    Icon(
                        Icons.Outlined.Delete,
                        contentDescription = null
                    )
                })
        }
    }
}
