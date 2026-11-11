package com.folderspan.ui.screen.file

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.folderspan.data.file.FileProtocol
import com.folderspan.data.file.FileSimpleInfo
import com.folderspan.ui.components.dialog.FullSizeFileSelectorDialog
import com.folderspan.ui.components.file.FileSelectorEntryRegion
import com.folderspan.ui.state.file.*
import strings.AppStrings

@Composable
internal fun CopySourceFilesDialog(
    request: CopyDestinationRequest,
    initialSources: List<CopyFileSnapshot>,
    loads: List<CopyDestinationLoad>,
    onDismiss: () -> Unit,
    onConfirm: (CopyEndpointIdentity, List<FileSimpleInfo>) -> Unit,
) {
    val endpoints = (initialSources.map { it.endpoint } + loads.map { it.endpoint }).distinct()
        .filter { request.discovery.destinationDesk(it) != null }
    var endpoint by remember { mutableStateOf(endpoints.firstOrNull()) }
    var expanded by remember { mutableStateOf(false) }
    var selected by remember(endpoint) { mutableStateOf(emptyList<FileSimpleInfo>()) }
    val desk = endpoint?.let(request.discovery::destinationDesk)
    val initial = initialSources.firstOrNull { it.endpoint == endpoint }
    val directory = initial?.takeIf { it.isDirectory }?.file
        ?: loads.firstOrNull { it.endpoint == endpoint }?.destinations?.firstOrNull()?.directory?.file
    val separator = endpoint?.pathSeparator ?: "/"
    val openPath = if (initial?.endpoint?.endpoint?.protocol == FileProtocol.Network) directory?.path.orEmpty()
        else initial?.file?.let { file ->
            if (file.isDirectory) file.path else file.path.substringBeforeLast(separator, separator)
        } ?: directory?.path.orEmpty()
    FullSizeFileSelectorDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                Text(AppStrings.copy_destination_add_sources, Modifier.weight(1f), style = MaterialTheme.typography.headlineSmall)
                Box {
                    TextButton({ expanded = true }, enabled = endpoints.size > 1, modifier = Modifier.widthIn(max = 160.dp).testTag("copy-source-endpoint")) {
                        Text(desk?.name ?: AppStrings.ui_source_endpoint_unavailable, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    }
                    DropdownMenu(expanded, { expanded = false }) {
                        endpoints.forEach { candidate ->
                            DropdownMenuItem(text = { Text(request.discovery.destinationDesk(candidate)?.name.orEmpty()) },
                                onClick = { endpoint = candidate; expanded = false })
                        }
                    }
                }
            }
        },
        dismissButton = { TextButton(onDismiss) { Text(AppStrings.ui_cancel) } },
        confirmButton = {
            TextButton({ endpoint?.let { onConfirm(it, selected.toList()) } }, enabled = desk != null && selected.isNotEmpty(),
                modifier = Modifier.testTag("copy-confirm-sources")) { Text(AppStrings.ui_add) }
        },
    ) {
        if (desk == null) Text(AppStrings.ui_source_endpoint_unavailable, Modifier.padding(24.dp))
        else key(endpoint) {
            FileSelectorEntryRegion(deskType = desk, openPath = openPath, initialDirectory = directory,
                onFilesSelected = { selected = it.toList() })
        }
    }
}
