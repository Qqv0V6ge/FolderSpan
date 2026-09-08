package com.folderspan.ui.screen.webrtc

import androidx.compose.material3.Badge
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.folderspan.data.main.webrtc.WebRtcRoomSource

@Composable
internal fun WebRtcRoomSourceBadge(
    source: WebRtcRoomSource,
    modifier: Modifier = Modifier
) {
    Badge(
        modifier = modifier,
        containerColor = when (source) {
            WebRtcRoomSource.Official -> MaterialTheme.colorScheme.tertiaryContainer
            WebRtcRoomSource.Other -> MaterialTheme.colorScheme.secondaryContainer
        },
        contentColor = when (source) {
            WebRtcRoomSource.Official -> MaterialTheme.colorScheme.onTertiaryContainer
            WebRtcRoomSource.Other -> MaterialTheme.colorScheme.onSecondaryContainer
        }
    ) {
        Text(source.label)
    }
}
