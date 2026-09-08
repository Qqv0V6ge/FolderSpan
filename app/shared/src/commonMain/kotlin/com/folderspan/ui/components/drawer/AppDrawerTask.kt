package com.folderspan.ui.components.drawer

import strings.AppStrings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.TaskAlt
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material3.*
import androidx.compose.material3.MaterialTheme.typography
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.folderspan.data.StatusEnum
import com.folderspan.data.file.toIcon
import com.folderspan.ui.state.main.Task

@Composable
fun AppDrawerTask(
    tasks: List<Task>,
    onOpenTaskList: () -> Unit,
    onTaskClick: (Task) -> Unit,
) {
    if (tasks.isEmpty()) {
        return
    }

    AppDrawerItem(AppStrings.ui_task, actions = {
        Icon(
            Icons.Default.ChevronRight,
            AppStrings.ui_task_list,
            Modifier.clip(RoundedCornerShape(25.dp))
                .clickable(onClick = onOpenTaskList)
        )
    }) {
        if (tasks.size > 1) {
            NavigationDrawerItem(
                icon = { Icon(Icons.Default.TaskAlt, null) },
                label = {
                    Column {
                        Text(AppStrings.ui_task_arg0.format(arg0 = (tasks.size).toString()))
                        Text(
                            AppStrings.ui_executing_arg0_pausing_arg1_failure_arg2.format(arg0 = (tasks.count { item -> item.status == StatusEnum.LOADING }).toString(), arg1 = (tasks.count { item -> item.status == StatusEnum.PAUSE }).toString(), arg2 = (tasks.count { item -> item.status == StatusEnum.FAILURE }).toString()),
                            style = typography.bodySmall
                        )
                    }
                },
                selected = false,
                onClick = onOpenTaskList,
                badge = {
                    Icon(Icons.Default.ExpandLess, null, Modifier.rotate(90f))
                },
                modifier = Modifier.padding(NavigationDrawerItemDefaults.ItemPadding)
            )
            return@AppDrawerItem
        }

        val task = tasks.first()
        NavigationDrawerItem(
            icon = {
                task.ToIcon()
            },
            label = {
                Column {
                    task.ToTitle()
                    Row {
                        task.protocol.toIcon()
                        Text(
                            task.values["path"] ?: "",
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            style = typography.bodySmall
                        )
                    }
                }
            },
            selected = false,
            onClick = { onTaskClick(task) },
            badge = {
                when (task.status) {
                    StatusEnum.SUCCESS -> {}
                    StatusEnum.FAILURE -> Row {
                        Icon(
                            Icons.Outlined.ErrorOutline,
                            null,
                            tint = MaterialTheme.colorScheme.error
                        )
                        Spacer(Modifier.width(4.dp))
                        Icon(Icons.Default.ExpandLess, null, Modifier.rotate(90f))
                    }

                    StatusEnum.PAUSE -> {
                        Icon(Icons.Default.Stop, null)
                    }

                    StatusEnum.LOADING -> CircularProgressIndicator(
                        modifier = Modifier.size(16.dp),
                        strokeWidth = 3.dp
                    )
                }
            },
            modifier = Modifier.padding(NavigationDrawerItemDefaults.ItemPadding)
        )
    }
}
