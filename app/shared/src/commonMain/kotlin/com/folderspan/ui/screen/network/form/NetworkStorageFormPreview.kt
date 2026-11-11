package com.folderspan.ui.screen.network.form

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import com.folderspan.ui.components.grid.GridList
import androidx.compose.ui.tooling.preview.Preview
import com.folderspan.service.network.StorageAccount
import com.folderspan.service.network.StorageAccountKind
import com.folderspan.service.network.StorageAuthorizationStart

@Preview(name = "Compact 360", widthDp = 360, heightDp = 720)
@Preview(name = "Medium 600", widthDp = 600, heightDp = 800)
@Preview(name = "Expanded 900", widthDp = 900, heightDp = 700)
@Preview(name = "Large 1300", widthDp = 1300, heightDp = 800)
@Preview(name = "Extra large 1600", widthDp = 1600, heightDp = 900)
@Preview(name = "Short 900", widthDp = 900, heightDp = 360)
@Composable
private fun NetworkStorageAuthorizationPreview() {
    MaterialTheme {
        GridList {
            NetworkStorageForm("Nextcloud", StorageFormValues(name = "Team cloud", server = "https://cloud.example.test", account = "preview"),
            {}, "", {}, "", {}, listOf(
                StorageAccount("preview", StorageAccountKind.Nextcloud, "Alice", username = "alice@example.test", server = "https://cloud.example.test"),
                StorageAccount("expired", StorageAccountKind.Nextcloud, "Old workspace", server = "https://cloud.example.test", needsAuthorization = true),
            ),
            emptyList(), StorageAuthorizationStart("preview", "https://cloud.example.test/login", "123456", Long.MAX_VALUE),
            busy = false, error = null)
        }
    }
}
