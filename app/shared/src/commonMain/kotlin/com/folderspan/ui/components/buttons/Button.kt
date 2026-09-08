package com.folderspan.ui.components.buttons

import strings.AppStrings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.filled.Sort
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material.icons.filled.Sensors
import androidx.compose.material.icons.filled.SwitchLeft
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.unit.dp
import com.folderspan.data.file.FileFilterSort
import com.folderspan.ignore.IgnoreFileMenuItem

/**
 * 一个用于显示排序选项的组合按钮组件。
 *
 * @param sortType 当前选择的文件排序类型。
 * @param onUpdateSort 当排序类型发生变化时触发的回调函数。
 */
@Composable
fun SortButton(
    sortType: FileFilterSort,
    onUpdateSort: (FileFilterSort) -> Unit,
    isFilled: Boolean = false,
    isHideFile: Boolean = false,
    onHideFileChange: ((Boolean) -> Unit)? = null,
    ignoreFileMenuItems: List<IgnoreFileMenuItem> = emptyList(),
    onIgnoreFileToggle: ((String) -> Unit)? = null,
) {
    var expanded by remember { mutableStateOf(false) }
    var ignoreExpanded by remember { mutableStateOf(false) }

    Box(modifier = Modifier.wrapContentSize(Alignment.TopStart)) {
        if (isFilled) {
            FilledIconButton({ expanded = true }) {
                Icon(Icons.AutoMirrored.Default.Sort, null)
            }
        } else {
            IconButton({ expanded = true }) {
                Icon(Icons.AutoMirrored.Default.Sort, null)
            }
        }
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = {
                expanded = false
                ignoreExpanded = false
            }
        ) {
            DropdownMenuItem(
                text = { Text(AppStrings.ui_file_name) },
                onClick = {
                    if (sortType == FileFilterSort.NameAsc) {
                        onUpdateSort(FileFilterSort.NameDesc)
                    } else {
                        onUpdateSort(FileFilterSort.NameAsc)
                    }
                },
                trailingIcon = {
                    if (listOf(FileFilterSort.NameAsc, FileFilterSort.NameDesc).contains(sortType)) {
                        val modifier =
                            if (sortType == FileFilterSort.NameAsc)
                                Modifier.rotate(270f)
                            else
                                Modifier.rotate(90f)
                        Icon(
                            Icons.Default.SwitchLeft,
                            null,
                            modifier
                        )
                    }
                }
            )
            DropdownMenuItem(
                text = { Text(AppStrings.ui_file_size) },
                onClick = {
                    if (sortType == FileFilterSort.SizeAsc) {
                        onUpdateSort(FileFilterSort.SizeDesc)
                    } else {
                        onUpdateSort(FileFilterSort.SizeAsc)
                    }
                },
                trailingIcon = {
                    if (listOf(FileFilterSort.SizeAsc, FileFilterSort.SizeDesc).contains(sortType)) {
                        val modifier =
                            if (sortType == FileFilterSort.SizeAsc)
                                Modifier.rotate(270f)
                            else
                                Modifier.rotate(90f)
                        Icon(
                            Icons.Default.SwitchLeft,
                            null,
                            modifier
                        )
                    }
                }
            )
            DropdownMenuItem(
                text = { Text(AppStrings.ui_file_type) },
                onClick = {
                    if (sortType == FileFilterSort.TypeAsc) {
                        onUpdateSort(FileFilterSort.TypeDesc)
                    } else {
                        onUpdateSort(FileFilterSort.TypeAsc)
                    }
                },
                trailingIcon = {
                    if (listOf(FileFilterSort.TypeAsc, FileFilterSort.TypeDesc).contains(sortType)) {
                        val modifier =
                            if (sortType == FileFilterSort.TypeAsc)
                                Modifier.rotate(270f)
                            else
                                Modifier.rotate(90f)
                        Icon(
                            Icons.Default.SwitchLeft,
                            null,
                            modifier
                        )
                    }
                }
            )
            DropdownMenuItem(
                text = { Text(AppStrings.ui_file_creation_time) },
                onClick = {
                    if (sortType == FileFilterSort.CreatedDateAsc) {
                        onUpdateSort(FileFilterSort.CreatedDateDesc)
                    } else {
                        onUpdateSort(FileFilterSort.CreatedDateAsc)
                    }
                },
                trailingIcon = {
                    if (listOf(FileFilterSort.CreatedDateAsc, FileFilterSort.CreatedDateDesc).contains(sortType)) {
                        val modifier =
                            if (sortType == FileFilterSort.CreatedDateAsc)
                                Modifier.rotate(270f)
                            else
                                Modifier.rotate(90f)
                        Icon(
                            Icons.Default.SwitchLeft,
                            null,
                            modifier
                        )
                    }
                }
            )
            DropdownMenuItem(
                text = { Text(AppStrings.ui_file_modification_time) },
                onClick = {
                    if (sortType == FileFilterSort.UpdatedDateAsc) {
                        onUpdateSort(FileFilterSort.UpdatedDateDesc)
                    } else {
                        onUpdateSort(FileFilterSort.UpdatedDateAsc)
                    }
                },
                trailingIcon = {
                    if (listOf(FileFilterSort.UpdatedDateAsc, FileFilterSort.UpdatedDateDesc).contains(sortType)) {
                        val modifier =
                            if (sortType == FileFilterSort.UpdatedDateAsc)
                                Modifier.rotate(270f)
                            else
                                Modifier.rotate(90f)
                        Icon(
                            Icons.Default.SwitchLeft,
                            null,
                            modifier
                        )
                    }
                }
            )
            if (onHideFileChange != null) {
                HorizontalDivider()
                DropdownMenuItem(
                    text = { Text(AppStrings.ui_show_hidden_files) },
                    onClick = { onHideFileChange(!isHideFile) },
                    trailingIcon = if (isHideFile) {
                        {
                            Icon(
                                imageVector = Icons.Default.Check,
                                contentDescription = null
                            )
                        }
                    } else {
                        null
                    }
                )
            }
            if (onIgnoreFileToggle != null && ignoreFileMenuItems.isNotEmpty()) {
                HorizontalDivider()
                Box {
                    DropdownMenuItem(
                        text = { Text(AppStrings.ui_ignore_file) },
                        onClick = { ignoreExpanded = true },
                        trailingIcon = {
                            Icon(
                                imageVector = Icons.AutoMirrored.Default.KeyboardArrowRight,
                                contentDescription = null
                            )
                        }
                    )
                    DropdownMenu(
                        expanded = ignoreExpanded,
                        onDismissRequest = { ignoreExpanded = false }
                    ) {
                        ignoreFileMenuItems.forEach { item ->
                            DropdownMenuItem(
                                text = { Text(item.fileName) },
                                onClick = { onIgnoreFileToggle(item.fileName) },
                                trailingIcon = if (item.enabled) {
                                    {
                                        Icon(
                                            imageVector = Icons.Default.Check,
                                            contentDescription = null
                                        )
                                    }
                                } else {
                                    null
                                }
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun IpsButton(
    ipAddresses: List<String>,
    isScanning: Boolean,
    onIpClick: (String) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }

    Box(modifier = Modifier.wrapContentSize(Alignment.TopStart)) {
        Icon(
            Icons.Default.Sensors,
            AppStrings.ui_view_ip_list,
            Modifier
                .clip(RoundedCornerShape(25.dp))
                .clickable {
                    expanded = true
                }
        )
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false }
        ) {
            for (ip in ipAddresses) {
                DropdownMenuItem(
                    text = { Text(ip) },
                    onClick = {
                        if (!isScanning) {
                            onIpClick(ip)
                            expanded = false
                        }
                    },
                    enabled = !isScanning,
                    leadingIcon = { Icon(Icons.Default.Dns, null) },
                )
            }
        }
    }
}
