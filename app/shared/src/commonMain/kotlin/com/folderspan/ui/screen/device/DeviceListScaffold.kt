package com.folderspan.ui.screen.device

import strings.AppStrings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.LazyGridScope
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.folderspan.data.main.device.DeviceType
import com.folderspan.ui.components.dialog.SearchDialog
import com.folderspan.ui.components.grid.GridList
import com.folderspan.ui.components.scaffold.AppScaffold

internal val defaultDeviceTypeOptions: List<Pair<DeviceType?, String>> = listOf(
    null to AppStrings.ui_all,
    DeviceType.JVM to "PC",
    DeviceType.IOS to "IOS",
    DeviceType.Android to AppStrings.ui_android,
    DeviceType.JS to AppStrings.ui_browser,
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun DeviceListScaffold(
    title: String = AppStrings.ui_equipment,
    showSearchDialog: Boolean,
    searchQuery: String,
    searchDraft: String,
    onSearchDraftChange: (String) -> Unit,
    onSearchOpen: () -> Unit,
    onSearchConfirm: () -> Unit,
    onSearchDismiss: () -> Unit,
    selectedDeviceType: DeviceType?,
    onDeviceTypeChange: (DeviceType?) -> Unit,
    onBackClick: () -> Unit,
    modifier: Modifier = Modifier,
    isEmpty: Boolean = false,
    emptyMessage: String? = null,
    content: LazyGridScope.() -> Unit
) {
    if (showSearchDialog) {
        SearchDialog(
            title = AppStrings.ui_search_device,
            query = searchDraft,
            onQueryChange = onSearchDraftChange,
            onConfirm = onSearchConfirm,
            onDismissRequest = onSearchDismiss,
            confirmText = AppStrings.ui_search,
            dismissText = AppStrings.ui_cancel,
            label = AppStrings.ui_enter_device_name,
            onClear = { onSearchDraftChange("") }
        )
    }

    AppScaffold(
        topBar = {
            TopAppBar(
                title = { Text(title) },
                navigationIcon = {
                    IconButton(onClick = onBackClick) {
                        Icon(Icons.AutoMirrored.Default.ArrowBack, null)
                    }
                },
                actions = {
                    IconToggleButton(
                        checked = searchQuery.isNotBlank(),
                        onCheckedChange = {
                            onSearchOpen()
                        }
                    ) {
                        Icon(Icons.Default.Search, contentDescription = AppStrings.ui_search_device)
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            SingleChoiceSegmentedButtonRow(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp),
            ) {
                defaultDeviceTypeOptions.forEachIndexed { index, (type, label) ->
                    SegmentedButton(
                        selected = selectedDeviceType == type,
                        onClick = { onDeviceTypeChange(type) },
                        shape = SegmentedButtonDefaults.itemShape(
                            index = index,
                            count = defaultDeviceTypeOptions.size
                        ),
                    ) {
                        Text(label)
                    }
                }
            }

            GridList(
                isEmpty = isEmpty,
                emptyMessage = emptyMessage,
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
            ) {
                content()
            }
        }
    }
}
