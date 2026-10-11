package com.folderspan.ui.components.dialog

import strings.AppStrings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.DisableSelection
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.InsertDriveFile
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.folderspan.data.file.FileInfo
import com.folderspan.data.file.FileSimpleInfo
import com.folderspan.extensions.*
import com.folderspan.ui.components.file.FileIcon
import com.folderspan.ui.components.menu.EditableExposedDropdownMenu
import com.folderspan.ui.components.model.StringListUiState
import com.folderspan.ui.state.file.FileOperation
import com.folderspan.ui.state.file.FileOperationType
import com.folderspan.ui.state.file.FilePropertyScanIssue
import kotlin.math.roundToInt

internal fun parentDisplayPath(path: String): String {
    if (path.isEmpty()) return ""
    val normalized = path.trimEnd('/', '\\')
    if (normalized.isEmpty()) return path.take(1)
    val separatorIndex = maxOf(normalized.lastIndexOf('/'), normalized.lastIndexOf('\\'))
    return when {
        separatorIndex < 0 -> ""
        separatorIndex == 0 -> normalized.take(1)
        else -> normalized.substring(0, separatorIndex)
    }
}


data class FileInfoDialogUiState(
    val fileInfo: FileSimpleInfo,
    val totalSize: Long,
    val freeSpace: Long,
    val totalSpace: Long,
    val fileCount: Int,
    val folderCount: Int,
    val isTraversing: Boolean,
    val errorText: String,
    val fullInfo: FileInfo?,
    val fullInfoError: String,
    val fileTypeText: String,
    val skippedItemCount: Int,
    val scanIssues: List<FilePropertyScanIssue>,
)

data class BatchFileInfoDialogUiState(
    val itemCount: Int,
    val selectionPath: String,
    val typeText: String,
    val totalSize: Long,
    val freeSpace: Long,
    val totalSpace: Long,
    val fileCount: Int,
    val folderCount: Int,
    val isTraversing: Boolean,
    val errorText: String,
    val skippedItemCount: Int,
    val scanIssues: List<FilePropertyScanIssue>,
)


// 文件信息弹窗（大小统计与详情展示）
@Composable
fun FileInfoDialog(uiState: FileInfoDialogUiState, onCancel: () -> Unit) {
    val fileInfo = uiState.fileInfo
    val failureCount = propertyScanFailureCount(uiState.skippedItemCount, uiState.errorText)
    var showFailureDetails by remember(fileInfo.path) { mutableStateOf(false) }
    val progressValue =
        if (uiState.totalSpace <= 0) 0F else (uiState.totalSize.toFloat() / uiState.totalSpace.toFloat())

    if (showFailureDetails && failureCount > 0) {
        PropertyScanIssuesDialog(
            failureCount = failureCount,
            skippedItemCount = uiState.skippedItemCount,
            errorText = uiState.errorText,
            scanIssues = uiState.scanIssues,
            onDismissRequest = { showFailureDetails = false },
        )
        return
    }

    AlertDialog(
        icon = {
            PropertyDialogHeaderIcon(
                failureCount = failureCount,
                onFailureClick = { showFailureDetails = true },
            ) {
                FileIcon(fileInfo)
            }
        },
        title = { SelectionContainer { Text(fileInfo.name) } },
        text = {
            SelectionContainer {
                Column {
                    LinearProgressIndicator(
                        progress = { progressValue },
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(10.dp))
                            .height(10.dp)
                    )
                    Spacer(Modifier.height(4.dp))
                    DisableSelection {
                        Column(Modifier.fillMaxWidth()) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(12.dp)
                            ) {
                                // 已用
                                Column(Modifier.weight(1f)) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Spacer(
                                            Modifier
                                                .size(6.dp)
                                                .clip(RoundedCornerShape(6.dp))
                                                .background(ProgressIndicatorDefaults.linearColor)
                                        )
                                        Spacer(Modifier.width(6.dp))
                                        Text(AppStrings.ui_used, style = MaterialTheme.typography.labelSmall)
                                    }
                                    Spacer(Modifier.height(2.dp))
                                    Text(
                                        "${uiState.totalSize.formatFileSize()} (${(progressValue * 100).roundToInt()}%)",
                                        style = MaterialTheme.typography.bodySmall,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }

                                // 剩余
                                Column(Modifier.weight(1f)) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Spacer(
                                            Modifier
                                                .size(6.dp)
                                                .clip(RoundedCornerShape(6.dp))
                                                .background(ProgressIndicatorDefaults.linearTrackColor)
                                        )
                                        Spacer(Modifier.width(6.dp))
                                        Text(AppStrings.ui_remaining, style = MaterialTheme.typography.labelSmall)
                                    }
                                    Spacer(Modifier.height(2.dp))
                                    Text(
                                        uiState.freeSpace.formatFileSize(),
                                        style = MaterialTheme.typography.bodySmall,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }

                                // 总计
                                Column(Modifier.weight(1f)) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Spacer(
                                            Modifier
                                                .size(6.dp)
                                                .clip(RoundedCornerShape(6.dp))
                                                .background(MaterialTheme.colorScheme.outlineVariant)
                                        )
                                        Spacer(Modifier.width(6.dp))
                                        Text(AppStrings.ui_total, style = MaterialTheme.typography.labelSmall)
                                    }
                                    Spacer(Modifier.height(2.dp))
                                    Text(
                                        uiState.totalSpace.formatFileSize(),
                                        style = MaterialTheme.typography.bodySmall,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                            }

                            Spacer(Modifier.height(4.dp))
                        }
                    }
                    Spacer(Modifier.height(12.dp))

                    Column(
                        Modifier.verticalScroll(
                            rememberScrollState()
                        )
                    ) {
                        Row(Modifier.fillMaxWidth().padding(4.dp)) {
                            DisableSelection {
                                Text(AppStrings.ui_location, Modifier.weight(0.3f))
                            }
                            Spacer(Modifier.width(8.dp))
                            Text(parentDisplayPath(fileInfo.path), Modifier.weight(0.7f))
                        }
                        Row(Modifier.fillMaxWidth().padding(4.dp)) {
                            DisableSelection {
                                Text(AppStrings.ui_type, Modifier.weight(0.3f))
                            }
                            Spacer(Modifier.width(8.dp))
                            DisableSelection {
                                Text(uiState.fileTypeText, Modifier.weight(0.7f))
                            }
                        }
                        Row(Modifier.fillMaxWidth().padding(4.dp)) {
                            DisableSelection {
                                Text(AppStrings.ui_permissions, Modifier.weight(0.3f))
                            }
                            Spacer(Modifier.width(8.dp))
                            val info = uiState.fullInfo
                            if (info == null) {
                                Text(
                                    if (uiState.fullInfoError.isNotEmpty()) "-" else AppStrings.ui_dialog_loading,
                                    Modifier.weight(0.7f),
                                )
                            } else {
                                Text(
                                    "${info.permissions.formatPermissionsOctal()} (${info.permissions.formatPermissionsRwx()})",
                                    Modifier.weight(0.7f)
                                )
                            }
                        }
                        val info = uiState.fullInfo
                        if (info != null) {
                            if (info.user.isNotEmpty()) {
                                Row(Modifier.fillMaxWidth().padding(4.dp)) {
                                    DisableSelection { Text(AppStrings.ui_user, Modifier.weight(0.3f)) }
                                    Spacer(Modifier.width(8.dp))
                                    Text(info.user, Modifier.weight(0.7f))
                                }
                            }
                            if (info.userGroup.isNotEmpty()) {
                                Row(Modifier.fillMaxWidth().padding(4.dp)) {
                                    DisableSelection { Text(AppStrings.ui_user_group, Modifier.weight(0.3f)) }
                                    Spacer(Modifier.width(8.dp))
                                    Text(info.userGroup, Modifier.weight(0.7f))
                                }
                            }
                        }
                        val countStatusSuffix = if (uiState.isTraversing) AppStrings.ui_statistical else ""
                        if (uiState.fileCount > 0 || fileInfo.isDirectory || uiState.errorText.isNotEmpty()) {
                            Row(Modifier.fillMaxWidth().padding(4.dp)) {
                                DisableSelection {
                                    Text(AppStrings.ui_file, Modifier.weight(0.3f))
                                }
                                Spacer(Modifier.width(8.dp))
                                Text("${uiState.fileCount}$countStatusSuffix", Modifier.weight(0.7f))
                            }
                        }
                        if (uiState.folderCount > 0 || fileInfo.isDirectory || uiState.errorText.isNotEmpty()) {
                            Row(Modifier.fillMaxWidth().padding(4.dp)) {
                                DisableSelection {
                                    Text(AppStrings.ui_folder, Modifier.weight(0.3f))
                                }
                                Spacer(Modifier.width(8.dp))
                                Text("${uiState.folderCount}$countStatusSuffix", Modifier.weight(0.7f))
                            }
                        }
                        Row(Modifier.fillMaxWidth().padding(4.dp)) {
                            DisableSelection {
                                Text(AppStrings.ui_creation_time, Modifier.weight(0.3f))
                            }
                            Spacer(Modifier.width(8.dp))
                            Text(fileInfo.createdDate.timestampToSyncDate(), Modifier.weight(0.7f))
                        }
                        Row(Modifier.fillMaxWidth().padding(4.dp)) {
                            DisableSelection {
                                Text(AppStrings.ui_update_time, Modifier.weight(0.3f))
                            }
                            Spacer(Modifier.width(8.dp))
                            Text(fileInfo.updatedDate.timestampToSyncDate(), Modifier.weight(0.7f))
                        }
                    }
                }
            }
        },
        onDismissRequest = onCancel,
        confirmButton = {},
        dismissButton = {}
    )
}

@Composable
fun BatchFileInfoDialog(uiState: BatchFileInfoDialogUiState, onCancel: () -> Unit) {
    val failureCount = propertyScanFailureCount(uiState.skippedItemCount, uiState.errorText)
    var showFailureDetails by remember(uiState.selectionPath, uiState.itemCount) { mutableStateOf(false) }
    val progressValue =
        if (uiState.totalSpace <= 0) 0F else (uiState.totalSize.toFloat() / uiState.totalSpace.toFloat())
    val countStatusSuffix = if (uiState.isTraversing) AppStrings.ui_statistical else ""

    if (showFailureDetails && failureCount > 0) {
        PropertyScanIssuesDialog(
            failureCount = failureCount,
            skippedItemCount = uiState.skippedItemCount,
            errorText = uiState.errorText,
            scanIssues = uiState.scanIssues,
            onDismissRequest = { showFailureDetails = false },
        )
        return
    }

    AlertDialog(
        icon = {
            PropertyDialogHeaderIcon(
                failureCount = failureCount,
                onFailureClick = { showFailureDetails = true },
            ) {
                Icon(Icons.Default.Info, null)
            }
        },
        title = { SelectionContainer { Text(AppStrings.ui_properties_arg0_items.format(arg0 = uiState.itemCount.toString())) } },
        text = {
            SelectionContainer {
                Column {
                    LinearProgressIndicator(
                        progress = { progressValue },
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(10.dp))
                            .height(10.dp)
                    )
                    Spacer(Modifier.height(4.dp))
                    DisableSelection {
                        Column(Modifier.fillMaxWidth()) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(12.dp)
                            ) {
                                Column(Modifier.weight(1f)) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Spacer(
                                            Modifier
                                                .size(6.dp)
                                                .clip(RoundedCornerShape(6.dp))
                                                .background(ProgressIndicatorDefaults.linearColor)
                                        )
                                        Spacer(Modifier.width(6.dp))
                                        Text(AppStrings.ui_used, style = MaterialTheme.typography.labelSmall)
                                    }
                                    Spacer(Modifier.height(2.dp))
                                    Text(
                                        "${uiState.totalSize.formatFileSize()} (${(progressValue * 100).roundToInt()}%)",
                                        style = MaterialTheme.typography.bodySmall,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }

                                Column(Modifier.weight(1f)) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Spacer(
                                            Modifier
                                                .size(6.dp)
                                                .clip(RoundedCornerShape(6.dp))
                                                .background(ProgressIndicatorDefaults.linearTrackColor)
                                        )
                                        Spacer(Modifier.width(6.dp))
                                        Text(AppStrings.ui_remaining, style = MaterialTheme.typography.labelSmall)
                                    }
                                    Spacer(Modifier.height(2.dp))
                                    Text(
                                        uiState.freeSpace.formatFileSize(),
                                        style = MaterialTheme.typography.bodySmall,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }

                                Column(Modifier.weight(1f)) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Spacer(
                                            Modifier
                                                .size(6.dp)
                                                .clip(RoundedCornerShape(6.dp))
                                                .background(MaterialTheme.colorScheme.outlineVariant)
                                        )
                                        Spacer(Modifier.width(6.dp))
                                        Text(AppStrings.ui_total, style = MaterialTheme.typography.labelSmall)
                                    }
                                    Spacer(Modifier.height(2.dp))
                                    Text(
                                        uiState.totalSpace.formatFileSize(),
                                        style = MaterialTheme.typography.bodySmall,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                            }

                            Spacer(Modifier.height(4.dp))
                        }
                    }
                    Spacer(Modifier.height(12.dp))

                    Column(Modifier.verticalScroll(rememberScrollState())) {
                        BatchInfoRow(AppStrings.ui_location, uiState.selectionPath)
                        BatchInfoRow(AppStrings.ui_type, uiState.typeText)
                        BatchInfoRow(AppStrings.ui_project, uiState.itemCount.toString())
                        BatchInfoRow(AppStrings.ui_file, "${uiState.fileCount}$countStatusSuffix")
                        BatchInfoRow(AppStrings.ui_folder, "${uiState.folderCount}$countStatusSuffix")
                    }
                }
            }
        },
        onDismissRequest = onCancel,
        confirmButton = {},
        dismissButton = {}
    )
}

@Composable
private fun PropertyDialogHeaderIcon(
    failureCount: Int,
    onFailureClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: @Composable () -> Unit,
) {
    Box(
        modifier = modifier.fillMaxWidth().heightIn(min = 48.dp),
        contentAlignment = Alignment.Center,
    ) {
        icon()
        if (failureCount > 0) {
            val failureText = AppStrings.ui_size_scan_skipped_arg0.format(arg0 = failureCount.toString())
            IconButton(
                onClick = onFailureClick,
                modifier = Modifier.align(Alignment.TopEnd),
            ) {
                BadgedBox(
                    badge = {
                        Badge(
                            containerColor = MaterialTheme.colorScheme.error,
                            contentColor = MaterialTheme.colorScheme.onError,
                        ) {
                            Text(failureCount.toString())
                        }
                    },
                ) {
                    Icon(
                        imageVector = Icons.Default.Warning,
                        contentDescription = failureText,
                        tint = MaterialTheme.colorScheme.error,
                    )
                }
            }
        }
    }
}

@Composable
internal fun PropertyScanIssuesDialog(
    failureCount: Int,
    skippedItemCount: Int,
    errorText: String,
    scanIssues: List<FilePropertyScanIssue>,
    onDismissRequest: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val hiddenIssueCount = (skippedItemCount - scanIssues.size).coerceAtLeast(0)
    AlertDialog(
        modifier = modifier,
        onDismissRequest = onDismissRequest,
        icon = {
            Icon(
                imageVector = Icons.Default.Warning,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.error,
            )
        },
        title = {
            Text(AppStrings.ui_size_scan_skipped_arg0.format(arg0 = failureCount.toString()))
        },
        text = {
            SelectionContainer {
                LazyColumn(
                    modifier = Modifier.fillMaxWidth().heightIn(max = 360.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    if (errorText.isNotEmpty()) {
                        item(key = "summary_error") {
                            Text(
                                text = AppStrings.ui_failed_get_size_arg0.format(arg0 = errorText),
                                color = MaterialTheme.colorScheme.error,
                                style = MaterialTheme.typography.bodyMedium,
                            )
                        }
                    }
                    itemsIndexed(
                        items = scanIssues,
                        key = { index, issue -> "${issue.path}:$index" },
                    ) { _, issue ->
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(
                                text = issue.path,
                                color = MaterialTheme.colorScheme.onSurface,
                                style = MaterialTheme.typography.labelLarge,
                            )
                            Text(
                                text = issue.message,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                    }
                    if (hiddenIssueCount > 0) {
                        item(key = "hidden_issue_count") {
                            Text(
                                text = AppStrings.ui_more_scan_errors_arg0.format(arg0 = hiddenIssueCount.toString()),
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismissRequest) {
                Text(AppStrings.ui_close)
            }
        },
    )
}

private fun propertyScanFailureCount(skippedItemCount: Int, errorText: String): Int {
    return maxOf(skippedItemCount.coerceAtLeast(0), if (errorText.isNotEmpty()) 1 else 0)
}

@Composable
private fun BatchInfoRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(4.dp)) {
        DisableSelection {
            Text(label, Modifier.weight(0.3f))
        }
        Spacer(Modifier.width(8.dp))
        Text(value, Modifier.weight(0.7f))
    }
}

// 远程文件打开确认弹窗（带“下次不再提示”选项）
@Composable
fun RemoteOpenConfirmDialog(
    initialDontShowAgain: Boolean = true,
    onConfirm: (Boolean) -> Unit,
    onCancel: (Boolean) -> Unit
) {
    var dontShowAgain by rememberSaveable(initialDontShowAgain) { mutableStateOf(initialDontShowAgain) }

    AlertDialog(
        onDismissRequest = { onCancel(dontShowAgain) },
        title = { Text(AppStrings.ui_open_remote_file) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(AppStrings.ui_file_located_remote_device_needs_downloaded_before_it_can)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(
                        checked = dontShowAgain,
                        onCheckedChange = { item ->  dontShowAgain = item }
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(AppStrings.ui_don_t_prompt_again_next_time)
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(dontShowAgain) }) {
                Text(AppStrings.ui_confirm)
            }
        },
        dismissButton = {
            TextButton(onClick = { onCancel(dontShowAgain) }) {
                Text(AppStrings.ui_cancel)
            }
        }
    )
}

@Composable
fun DeviceDropUploadConfirmDialog(
    fileCount: Int,
    targetPath: String,
    onConfirm: () -> Unit,
    onCancel: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text(AppStrings.ui_copy_files) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(AppStrings.ui_drag_drop_arg0_item_detected_copy_current_directory.format(arg0 = (fileCount).toString()))
                if (targetPath.isNotBlank()) {
                    Text(
                        text = targetPath,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(AppStrings.ui_confirm)
            }
        },
        dismissButton = {
            TextButton(onClick = onCancel) {
                Text(AppStrings.ui_cancel)
            }
        }
    )
}

// 重命名弹窗
@Composable
fun FileRenameDialog(
    fileInfo: FileSimpleInfo,
    verifyFun: (String) -> Pair<Boolean, String>,
    onCancel: (String) -> Unit
) {
    var text by rememberSaveable { mutableStateOf(fileInfo.name) }
    val verify = verifyFun(text)

    AlertDialog(
        icon = { Icon(Icons.Outlined.Edit, null) },
        title = { Text(AppStrings.ui_rename) },
        text = {
            TextField(
                value = text,
                onValueChange = { item ->  text = item },
                label = { Text(AppStrings.ui_name) },
                isError = verify.first,
                leadingIcon = { FileIcon(fileInfo) },
                modifier = Modifier.semantics { if (verify.first) verify.second },
                trailingIcon = {
                    if (text.isNotEmpty()) {
                        IconButton({ text = "" }) {
                            Icon(Icons.Default.Close, null)
                        }
                    }
                },
                supportingText = {
                    if (verify.first) {
                        Text(
                            modifier = Modifier.fillMaxWidth(),
                            text = verify.second,
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                },
            )
        },
        onDismissRequest = {},
        confirmButton = {
            TextButton(
                { onCancel(text) },
                enabled = text.isNotEmpty() && !verify.first
            ) {
                Text(AppStrings.ui_confirm)
            }
        },
        dismissButton = {
            TextButton({ onCancel("") }) {
                Text(AppStrings.ui_cancel)
            }
        }
    )
}

// 文本输入弹窗
@Composable
fun TextFieldDialog(
    title: String,
    label: String = "",
    initText: String = "",
    optionsUiState: StringListUiState? = null,
    leadingIcon: @Composable (() -> Unit)? = null,
    verifyFun: (String) -> Pair<Boolean, String> = { _ -> Pair(false, "") },
    onCancel: (String) -> Unit
) {
    var text by remember(initText) { mutableStateOf(initText) }
    val focusRequester = remember { FocusRequester() }
    val verify = remember(text, verifyFun) {
        verifyFun(text)
    }

    AlertDialog(
        title = { Text(title) },
        text = {
            if (optionsUiState == null) {
                TextField(
                    value = text,
                    onValueChange = { item -> text = item },
                    label = { Text(label) },
                    isError = verify.first,
                    modifier = Modifier.focusRequester(focusRequester),
                    leadingIcon = leadingIcon,
                    trailingIcon = {
                        if (text.isNotEmpty()) {
                            IconButton({ text = "" }) {
                                Icon(Icons.Default.Close, null)
                            }
                        }
                    },
                    supportingText = {
                        if (verify.first) {
                            Text(
                                modifier = Modifier.fillMaxWidth(),
                                text = verify.second,
                                color = MaterialTheme.colorScheme.error
                            )
                        }
                    },
                )
            } else {
                EditableExposedDropdownMenu(
                    optionsUiState = optionsUiState,
                    value = text,
                    onValueChange = { item -> text = item },
                    label = { Text(label) },
                    isError = verify.first,
                    modifier = Modifier.focusRequester(focusRequester),
                    leadingIcon = leadingIcon,
                    supportingText = {
                        if (verify.first) {
                            Text(
                                modifier = Modifier.fillMaxWidth(),
                                text = verify.second,
                                color = MaterialTheme.colorScheme.error
                            )
                        }
                    },
                    singleLine = true,
                )
            }
        },
        onDismissRequest = {},
        confirmButton = {
            TextButton(
                { onCancel(text) },
                enabled = text.isNotEmpty() && !verify.first
            ) {
                Text(AppStrings.ui_confirm)
            }
        },
        dismissButton = {
            TextButton({ onCancel("") }) {
                Text(AppStrings.ui_cancel)
            }
        }
    )

    LaunchedEffect(Unit) {
        focusRequester.requestFocus()
    }
}

enum class FileType {
    FILE, FOLDER
}

// 文本输入 + 类型选择弹窗
@Composable
fun TextFieldWithTypeDialog(
    title: String,
    label: String = "",
    initText: String = "",
    initType: FileType = FileType.FILE,
    leadingIcon: @Composable (() -> Unit)? = null,
    verifyFun: (String, FileType) -> Pair<Boolean, String> = { _, _ -> Pair(false, "") },
    onConfirm: (String, FileType) -> Unit,
    onCancel: () -> Unit
) {
    var textFieldValue by rememberSaveable(initText, stateSaver = TextFieldValue.Saver) {
        mutableStateOf(
            TextFieldValue(
                text = initText,
                selection = TextRange(initText.length)
            )
        )
    }
    var selectedType by rememberSaveable(initType) { mutableStateOf(initType) }
    val focusRequester = remember { FocusRequester() }
    val verify = remember(textFieldValue.text, selectedType, verifyFun) {
        verifyFun(textFieldValue.text, selectedType)
    }

    AlertDialog(
        title = { Text(title) },
        text = {
            Column {
                TextField(
                    value = textFieldValue,
                    onValueChange = { item ->  textFieldValue = item },
                    label = { Text(label) },
                    isError = verify.first,
                    modifier = Modifier
                        .fillMaxWidth()
                        .focusRequester(focusRequester),
                    leadingIcon = leadingIcon,
                    trailingIcon = {
                        if (textFieldValue.text.isNotEmpty()) {
                            IconButton({
                                textFieldValue = TextFieldValue(
                                    text = "",
                                    selection = TextRange(0)
                                )
                            }) {
                                Icon(Icons.Default.Close, null)
                            }
                        }
                    },
                    supportingText = {
                        if (verify.first) {
                            Text(
                                modifier = Modifier.fillMaxWidth(),
                                text = verify.second,
                                color = MaterialTheme.colorScheme.error
                            )
                        }
                    },
                )

                // 文件类型选择器
                FlowRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    FilterChip(
                        selected = selectedType == FileType.FILE,
                        onClick = { selectedType = FileType.FILE },
                        label = { Text(AppStrings.ui_file) },
                        leadingIcon = {
                            Icon(
                                imageVector = if (selectedType == FileType.FILE) {
                                    Icons.Default.Check
                                } else {
                                    Icons.AutoMirrored.Outlined.InsertDriveFile
                                },
                                contentDescription = null,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    )

                    FilterChip(
                        selected = selectedType == FileType.FOLDER,
                        onClick = { selectedType = FileType.FOLDER },
                        label = { Text(AppStrings.ui_folder) },
                        leadingIcon = {
                            Icon(
                                imageVector = if (selectedType == FileType.FOLDER) {
                                    Icons.Default.Check
                                } else {
                                    Icons.Default.Folder
                                },
                                contentDescription = null,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    )
                }
            }
        },
        onDismissRequest = {},
        confirmButton = {
            TextButton(
                onClick = { onConfirm(textFieldValue.text, selectedType) },
                enabled = textFieldValue.text.isNotEmpty() && !verify.first
            ) {
                Text(AppStrings.ui_confirm)
            }
        },
        dismissButton = {
            TextButton(onClick = onCancel) {
                Text(AppStrings.ui_cancel)
            }
        }
    )

    LaunchedEffect(Unit) {
        focusRequester.requestFocus()
    }
}

// 文件冲突处理弹窗
@Composable
fun FileWarningOperationDialog(
    fileOperations: List<FileOperation>,
    onOperationTypeChange: (FileOperation, FileOperationType) -> Unit,
    onConfirm: () -> Unit,
    onCancel: () -> Unit,
) {
    AlertDialog(
        modifier = Modifier.padding(16.dp),
        onDismissRequest = {},
        title = { Text(AppStrings.ui_do_you_need_replace) },
        text = {
            LazyColumn {
                itemsIndexed(
                    items = fileOperations,
                    key = { _, file -> "${file.src.path}->${file.dest.path}" }
                ) { index, file ->
                    Column {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            modifier = Modifier.padding(
                                top = 12.dp,
                                bottom = 14.dp
                            )
                        ) {
                            OutlinedCard {
                                ListItem(
                                    headlineContent = { Text(file.src.name) },
                                    supportingContent = { FileWarningDialogItem(file.src) },
                                    leadingContent = { FileIcon(file.src) },
                                    trailingContent = {
                                        Badge(
                                            containerColor = MaterialTheme.colorScheme.primaryContainer
                                        ) { Text(AppStrings.ui_new) }
                                    },
                                )
                            }
                            Spacer(Modifier.height(4.dp))
                            Icon(Icons.Default.KeyboardArrowDown, null, modifier = Modifier.size(32.dp))
                            Spacer(Modifier.height(4.dp))
                            OutlinedCard {
                                ListItem(
                                    headlineContent = { Text(file.dest.name) },
                                    supportingContent = { FileWarningDialogItem(file.dest) },
                                    leadingContent = { FileIcon(file.dest) },
                                    trailingContent = {
                                        Badge { Text(AppStrings.ui_old) }
                                    },
                                )
                            }
                        }

                        FlowRow {
                            FileOperationType.entries.forEach { operationType ->
                                FilterChip(
                                    onClick = { onOperationTypeChange(file, operationType) },
                                    label = {
                                        val labelText = when {
                                            file.dest.isDirectory && file.src.isDirectory -> when (operationType) {
                                                FileOperationType.Replace -> AppStrings.ui_overwrite_folder
                                                FileOperationType.Jump -> AppStrings.ui_skip_folder
                                                FileOperationType.Reserve -> AppStrings.ui_keep_folder
                                            }

                                            else -> when (operationType) {
                                                FileOperationType.Replace -> AppStrings.ui_overwrite_file
                                                FileOperationType.Jump -> AppStrings.ui_skip_files
                                                FileOperationType.Reserve -> AppStrings.ui_keep_files
                                            }
                                        }
                                        Text(labelText)
                                    },
                                    selected = file.type == operationType
                                )
                                Spacer(Modifier.width(8.dp))
                            }
                        }

                        if (!file.dest.isDirectory && !file.src.isDirectory) return@Column

                        Spacer(Modifier.height(8.dp))

//                        Row(
//                            Modifier
//                                .toggleable(
//                                    value = file.isUseAll,
//                                    onValueChange = {
//                                        operationState.files.indexOf(file).takeIf { currentIndex -> currentIndex >= 0 }
//                                            ?.let { index ->
//                                            operationState.files[index] = file.withCopy(
//                                                isUseAll = !file.isUseAll
//                                            )
//                                        }
//                                    },
//                                    role = Role.Checkbox
//                                ),
//                            verticalAlignment = Alignment.CenterVertically
//                        ) {
//                            Checkbox(
//                                checked = file.isUseAll,
//                                onCheckedChange = null
//                            )
//                            Text(
//                                text = AppStrings.ui_apply_this_action_all_folders_files,
//                                style = MaterialTheme.typography.bodyLarge,
//                                modifier = Modifier.padding(start = 16.dp)
//                            )
//                        }
                    }

                    if (index < fileOperations.size - 1) {
                        HorizontalDivider(Modifier.padding(vertical = 16.dp))
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onConfirm) { Text(AppStrings.ui_confirm) }
        },
        dismissButton = {
            TextButton(onClick = onCancel) { Text(AppStrings.ui_cancel) }
        }
    )
}

// 冲突项信息展示行
@Composable
internal fun FileWarningDialogItem(newFile: FileSimpleInfo) {
    val sizeText = remember(newFile.size, newFile.isDirectory) {
        if (newFile.isDirectory) AppStrings.ui_arg0_items.format(arg0 = (newFile.size).toString()) else newFile.size.formatFileSize()
    }
    val parentPath = remember(newFile.path) {
        parentDisplayPath(newFile.path)
    }
    Row {
        Text(sizeText, style = MaterialTheme.typography.bodySmall)
        Spacer(Modifier.width(8.dp))
        Text(
            parentPath,
            maxLines = 1,
            style = MaterialTheme.typography.bodySmall
        )
    }
}
