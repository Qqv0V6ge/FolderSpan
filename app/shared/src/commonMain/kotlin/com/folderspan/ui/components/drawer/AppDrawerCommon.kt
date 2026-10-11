package com.folderspan.ui.components.drawer

import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

@Composable
fun AppDrawerItem(
    title: String,
    modifier: Modifier? = null,
    actions: @Composable () -> Unit,
    content: @Composable () -> Unit
) {
    AppDrawerHeader(title = title, modifier = modifier, actions = actions)
    content()
    Spacer(Modifier.height(12.dp))
}

@Composable
fun AppDrawerHeader(
    title: String,
    modifier: Modifier? = null,
    actions: @Composable () -> Unit
) {
    Row(
        modifier ?: Modifier.padding(start = 16.dp, end = 16.dp, top = 20.dp, bottom = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = title,
            modifier = Modifier.weight(1f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        Spacer(Modifier.width(8.dp))
        actions()
    }
}
