package com.folderspan.data.file

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Devices
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import kotlinx.serialization.Serializable

@Serializable
enum class FileProtocol(type: String) {
    Local("Local"),
    Device("Device"),
    Share("Share"),
    Network("Network")
}


@Composable
fun FileProtocol.toIcon(tint: Color = MaterialTheme.colorScheme.primary) {
    when (this) {
        FileProtocol.Local -> {}
        FileProtocol.Device -> {
            Icon(
                Icons.Default.Devices,
                null,
                Modifier.size(12.dp),
                tint = tint
            )
            Spacer(Modifier.width(4.dp))
        }

        FileProtocol.Share -> {
            Icon(
                Icons.Default.Share,
                null,
                Modifier.size(12.dp),
                tint = tint
            )
            Spacer(Modifier.width(4.dp))
        }

        FileProtocol.Network -> {
            Icon(Icons.Default.Public, null, Modifier.size(12.dp), tint = tint)
            Spacer(Modifier.width(4.dp))
        }
    }
}
