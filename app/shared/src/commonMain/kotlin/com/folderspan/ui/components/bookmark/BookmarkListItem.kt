package com.folderspan.ui.components.bookmark

import strings.AppStrings

import androidx.compose.foundation.clickable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.RadioButtonUnchecked
import androidx.compose.material3.*
import androidx.compose.material3.MaterialTheme.colorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import com.folderspan.ui.state.file.DrawerBookmark

@Composable
fun BookmarkListItem(
    bookmark: DrawerBookmark,
    modifier: Modifier = Modifier,
    isSelected: Boolean = false,
    isSelectionMode: Boolean = false,
    onToggleSelect: (() -> Unit)? = null,
    onClick: (() -> Unit)? = null,
    trailingContent: @Composable (() -> Unit)? = null
) {
    val containerColor = if (isSelected) colorScheme.secondaryContainer else colorScheme.surface
    val headlineColor = if (isSelected) colorScheme.onSecondaryContainer else colorScheme.onSurface
    val supportingColor = if (isSelected) colorScheme.onSecondaryContainer.copy(alpha = 0.8f) else colorScheme.outline

    val clickableModifier = if (onClick != null) {
        modifier.clickable(onClick = onClick)
    } else {
        modifier
    }

    ListItem(
        modifier = clickableModifier,
        colors = ListItemDefaults.colors(
            containerColor = containerColor,
            headlineColor = headlineColor,
            supportingColor = supportingColor
        ),
        leadingContent = {
            IconButton(onClick = { onToggleSelect?.invoke() }) {
                if (isSelectionMode || isSelected) {
                    Icon(
                        imageVector = if (isSelected) Icons.Default.CheckCircle else Icons.Default.RadioButtonUnchecked,
                        contentDescription = if (isSelected) AppStrings.ui_deselect else AppStrings.ui_select_bookmark
                    )
                } else {
                    Icon(bookmark.icon(), contentDescription = null)
                }
            }
        },
        headlineContent = {
            Text(
                text = bookmark.name,
                style = MaterialTheme.typography.titleMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        },
        supportingContent = {
            Text(
                text = bookmark.path,
                style = MaterialTheme.typography.bodySmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                color = supportingColor
            )
        },
        trailingContent = trailingContent
    )
}
