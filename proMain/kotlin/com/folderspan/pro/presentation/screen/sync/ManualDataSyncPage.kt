package com.folderspan.pro.presentation.screen.sync

import strings.AppStrings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.folderspan.pro.core.ui.components.AuthStatusTone
import com.folderspan.pro.core.ui.components.ProSnackbarEffect
import com.folderspan.pro.core.ui.components.ProSnackbarHost
import com.folderspan.pro.core.ui.components.proSnackbarPrompt
import com.folderspan.pro.domain.usecase.ManualSyncCategory
import com.folderspan.ui.components.grid.GridList
import com.folderspan.ui.components.grid.GridListFabPadding

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ManualDataSyncPage(
    modifier: Modifier = Modifier,
    state: ManualDataSyncUiState,
    categories: List<ManualDataSyncCategoryItem> = ManualDataSyncCategoryItem.defaultItems(),
    onNavigateBack: () -> Unit = {},
    onToggleCategory: (ManualSyncCategory) -> Unit = {},
    onSync: () -> Unit = {},
) {
    val canSync = !state.isSyncing && state.selectedCategories.isNotEmpty()
    val snackbarHostState = remember { SnackbarHostState() }
    ProSnackbarEffect(
        hostState = snackbarHostState,
        prompt = proSnackbarPrompt(
            message = state.feedback?.text,
            tone = state.feedback?.tone ?: AuthStatusTone.Info,
        ),
    )

    Scaffold(
        modifier = modifier,
        snackbarHost = {
            ProSnackbarHost(hostState = snackbarHostState)
        },
        topBar = {
            TopAppBar(
                title = { Text(AppStrings.ui_data_synchronization) },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack, enabled = !state.isSyncing) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = AppStrings.ui_return)
                    }
                },
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = {
                    if (canSync) onSync()
                },
                modifier = Modifier.semantics {
                    if (!canSync) disabled()
                },
                containerColor = if (canSync) {
                    MaterialTheme.colorScheme.primaryContainer
                } else {
                    MaterialTheme.colorScheme.surfaceVariant
                },
                contentColor = if (canSync) {
                    MaterialTheme.colorScheme.onPrimaryContainer
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
                icon = {
                    Icon(Icons.Filled.Sync, contentDescription = null)
                },
                text = {
                    Text(if (state.isSyncing) AppStrings.ui_syncing else AppStrings.ui_start_syncing)
                },
            )
        },
    ) { padding ->
        GridList(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            isEmpty = categories.isEmpty(),
            floatingActionButtonPadding = GridListFabPadding,
            verticalSpacing = 12.dp,
            horizontalSpacing = 12.dp,
        ) {
            items(categories, key = { it.category.name }) { item ->
                ManualDataSyncCategoryRow(
                    item = item,
                    checked = item.category in state.selectedCategories,
                    enabled = !state.isSyncing,
                    onToggle = { onToggleCategory(item.category) },
                )
            }

            if (state.isSyncing) {
                item(key = "sync-progress", span = { GridItemSpan(maxLineSpan) }) {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                }
            }
        }
    }
}

@Preview(name = "Compact", widthDp = 360, heightDp = 800)
@Preview(name = "Short landscape", widthDp = 640, heightDp = 360)
@Preview(name = "Medium", widthDp = 720, heightDp = 900)
@Preview(name = "Expanded", widthDp = 1024, heightDp = 768)
@Preview(name = "Large", widthDp = 1366, heightDp = 900)
@Preview(name = "Extra-large", widthDp = 1600, heightDp = 900)
@Composable
private fun ManualDataSyncPagePreview() {
    MaterialTheme {
        ManualDataSyncPage(
            state = ManualDataSyncUiState(selectedCategories = ManualSyncCategory.entries.toSet()),
        )
    }
}

@Composable
private fun ManualDataSyncCategoryRow(
    item: ManualDataSyncCategoryItem,
    checked: Boolean,
    enabled: Boolean,
    onToggle: () -> Unit,
) {
    ListItem(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = enabled, onClick = onToggle),
        headlineContent = {
            Text(
                text = item.title,
                fontWeight = FontWeight.Medium,
            )
        },
        supportingContent = { Text(item.subtitle) },
        trailingContent = {
            Checkbox(
                checked = checked,
                enabled = enabled,
                onCheckedChange = { onToggle() },
            )
        },
    )
}
