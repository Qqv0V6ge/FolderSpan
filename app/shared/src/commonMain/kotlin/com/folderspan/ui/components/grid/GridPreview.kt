package com.folderspan.ui.components.grid

import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.Preview

@Preview
@Composable
private fun GridListPreview() {
    GridList {
        items(3) {
            TextButton({}) {
                Text("Item")
            }
        }
    }
}
