package com.folderspan.ui.components.buttons

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialExpressiveTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.folderspan.data.file.FileFilterSort

@Preview(name = "Expressive actions", widthDp = 360, showBackground = true)
@Preview(name = "Expressive actions - short window", widthDp = 640, heightDp = 240, showBackground = true)
@Composable
private fun SortButtonPreview() {
    MaterialExpressiveTheme {
        Surface {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    SortButton(sortType = FileFilterSort.NameAsc, onUpdateSort = {})
                    SortButton(sortType = FileFilterSort.SizeDesc, onUpdateSort = {}, isFilled = true)
                }
                TestConnectionButton(isTesting = false, onClick = {})
                TestConnectionButton(isTesting = true, onClick = {})
            }
        }
    }
}
