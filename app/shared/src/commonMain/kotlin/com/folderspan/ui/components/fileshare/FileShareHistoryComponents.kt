package com.folderspan.ui.components.fileshare

import strings.AppStrings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.InsertDriveFile
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.folderspan.data.file.FileShareHistory
import com.folderspan.data.main.device.DeviceType
import com.folderspan.extensions.formatFileSize
import com.folderspan.extensions.timestampToSyncDate
import com.folderspan.extensions.timestampToYMDHM
import com.folderspan.ui.state.file.FileShareStatus

@Composable
fun FileShareHistoryListItem(
    history: FileShareHistory,
    onDelete: () -> Unit,
    onClick: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val sizeText = remember(history.fileSize, history.isDirectory) {
        if (history.isDirectory) AppStrings.ui_arg0_items.format(arg0 = (history.fileSize).toString()) else history.fileSize.formatFileSize()
    }
    val timestampText = remember(history.timestamp) { history.timestamp.timestampToYMDHM() }
    val statusColor = when (history.status) {
        FileShareStatus.WAITING -> MaterialTheme.colorScheme.tertiary
        FileShareStatus.SENDING -> MaterialTheme.colorScheme.secondary
        FileShareStatus.COMPLETED -> MaterialTheme.colorScheme.primary
        FileShareStatus.REJECTED -> MaterialTheme.colorScheme.error
        FileShareStatus.ERROR -> MaterialTheme.colorScheme.error
    }

    ListItem(
        modifier = modifier.clickable(onClick = onClick),
        overlineContent = {
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Badge(
                    containerColor = if (history.isOutgoing) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.primary
                ) {
                    Text(if (history.isOutgoing) AppStrings.ui_send else AppStrings.ui_receive)
                }

                Badge(
                    containerColor = statusColor
                ) {
                    Text(
                        when (history.status) {
                            FileShareStatus.WAITING -> AppStrings.ui_waiting
                            FileShareStatus.SENDING -> AppStrings.ui_transmitting
                            FileShareStatus.COMPLETED -> AppStrings.ui_completed
                            FileShareStatus.REJECTED -> AppStrings.ui_rejected
                            FileShareStatus.ERROR -> AppStrings.ui_error
                        }
                    )
                }
            }
        },
        headlineContent = {
            Text(
                history.fileName,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        },
        supportingContent = {
            Row {
                Text(
                    sizeText,
                    style = MaterialTheme.typography.bodySmall
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    timestampText,
                    style = MaterialTheme.typography.bodySmall
                )
            }
        },
        leadingContent = {
            if (history.isDirectory) {
                Icon(Icons.Default.Folder, null)
            } else {
                Icon(Icons.AutoMirrored.Filled.InsertDriveFile, null)
            }
        },
        trailingContent = {
            IconButton(
                onClick = onDelete,
                modifier = Modifier.padding(start = 8.dp)
            ) {
                Icon(Icons.Default.Delete, AppStrings.ui_delete_record)
            }
        }
    )
}

@Composable
fun FileShareHistoryDetailDialog(
    history: FileShareHistory,
    onDismiss: () -> Unit
) {
    val statusColor = when (history.status) {
        FileShareStatus.WAITING -> MaterialTheme.colorScheme.tertiary
        FileShareStatus.SENDING -> MaterialTheme.colorScheme.secondary
        FileShareStatus.COMPLETED -> MaterialTheme.colorScheme.primary
        FileShareStatus.REJECTED -> MaterialTheme.colorScheme.error
        FileShareStatus.ERROR -> MaterialTheme.colorScheme.error
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Icon(
                    if (history.isDirectory) Icons.Default.Folder else Icons.AutoMirrored.Filled.InsertDriveFile,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary
                )
                Text(AppStrings.ui_share_details)
            }
        },
        text = {
            Surface(
                shape = RoundedCornerShape(12.dp),
                tonalElevation = 1.dp,
                modifier = Modifier.fillMaxWidth()
            ) {
                SelectionContainer {
                    Column(
                        verticalArrangement = Arrangement.spacedBy(16.dp),
                        modifier = Modifier
                            .padding(16.dp)
                            .verticalScroll(rememberScrollState())
                    ) {
                        FileShareHistoryDetailSection(
                            title = AppStrings.ui_file_information,
                            icon = Icons.Default.Description
                        ) {
                            FileShareHistoryLabeledText(AppStrings.ui_file_name, history.fileName)
                            FileShareHistoryLabeledText(AppStrings.ui_type, if (history.isDirectory) AppStrings.ui_folder else AppStrings.ui_file)
                            FileShareHistoryLabeledText(
                                AppStrings.ui_size,
                                if (history.isDirectory) AppStrings.ui_arg0_items.format(arg0 = (history.fileSize).toString()) else history.fileSize.formatFileSize()
                            )
                            FileShareHistoryLabeledText(AppStrings.ui_path, history.filePath)

                            if (history.savePath != null && !history.isOutgoing) {
                                FileShareHistoryLabeledText(AppStrings.ui_save_path, history.savePath)
                            }
                        }

                        FileShareHistoryDetailSection(
                            title = AppStrings.ui_transmit_information,
                            icon = Icons.Default.SwapHoriz
                        ) {
                            FileShareHistoryLabeledText(
                                if (history.isOutgoing) AppStrings.ui_receiving_equipment else AppStrings.ui_sending_device,
                                if (history.isOutgoing) history.targetDeviceName else history.sourceDeviceName
                            )

                            FileShareHistoryLabeledText(AppStrings.ui_time, history.timestamp.timestampToSyncDate())

                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text(
                                    text = AppStrings.ui_status,
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.width(80.dp)
                                )

                                Badge(
                                    containerColor = statusColor
                                ) {
                                    Text(
                                        when (history.status) {
                                            FileShareStatus.WAITING -> AppStrings.ui_waiting
                                            FileShareStatus.SENDING -> AppStrings.ui_transmitting
                                            FileShareStatus.COMPLETED -> AppStrings.ui_completed
                                            FileShareStatus.REJECTED -> AppStrings.ui_rejected
                                            FileShareStatus.ERROR -> AppStrings.ui_error
                                        },
                                        color = MaterialTheme.colorScheme.onPrimary
                                    )
                                }
                            }

                            val errorMessage = history.errorMessage
                            if (history.status == FileShareStatus.ERROR && errorMessage != null) {
                                Spacer(Modifier.height(8.dp))
                                Surface(
                                    color = MaterialTheme.colorScheme.errorContainer,
                                    shape = RoundedCornerShape(8.dp),
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        modifier = Modifier.padding(8.dp)
                                    ) {
                                        Icon(
                                            Icons.Default.Error,
                                            contentDescription = null,
                                            tint = MaterialTheme.colorScheme.error,
                                            modifier = Modifier.padding(end = 8.dp)
                                        )
                                        Text(
                                            text = errorMessage,
                                            style = MaterialTheme.typography.bodyMedium,
                                            color = MaterialTheme.colorScheme.onErrorContainer
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(AppStrings.ui_close)
            }
        }
    )
}

@Composable
fun FileShareHistoryDetailSection(
    title: String,
    icon: ImageVector,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit
) {
    Column(
        verticalArrangement = Arrangement.spacedBy(8.dp),
        modifier = modifier,
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Icon(
                icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(18.dp)
            )
            Text(
                text = title,
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.primary
            )
        }

        Surface(
            shape = RoundedCornerShape(8.dp),
            tonalElevation = 0.5.dp,
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                modifier = Modifier.padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                content()
            }
        }
    }
}

@Composable
fun FileShareHistoryLabeledText(
    label: String,
    value: String?,
    modifier: Modifier = Modifier,
    emptyPlaceholder: String = AppStrings.ui_none,
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.Top
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(80.dp)
        )

        if (value != null) {
            Text(
                text = value,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.weight(1f)
            )
        } else {
            Text(
                text = emptyPlaceholder,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f)
            )
        }
    }
}

internal fun sampleFileShareHistory(): FileShareHistory {
    return FileShareHistory(
        id = 1L,
        fileName = "report.pdf",
        filePath = "/Documents/report.pdf",
        fileSize = 2_048_000,
        isDirectory = false,
        isOutgoing = true,
        timestamp = 0L,
        status = FileShareStatus.COMPLETED,
        errorMessage = null,
        savePath = "/Downloads/report.pdf",
        sourceDeviceId = "device-a",
        sourceDeviceName = "Pixel",
        sourceDeviceType = DeviceType.Android,
        targetDeviceId = "device-b",
        targetDeviceName = "Mac",
        targetDeviceType = DeviceType.JVM
    )
}
