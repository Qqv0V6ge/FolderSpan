package com.folderspan.ui.components.loading

import androidx.compose.material3.MaterialExpressiveTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.Preview

@Preview(name = "Expressive loading", widthDp = 360, heightDp = 240, showBackground = true)
@Composable
private fun LoadingBasePreview() {
    MaterialExpressiveTheme {
        Surface { LoadingBase() }
    }
}

@Preview(name = "Expressive loading - dark", widthDp = 360, heightDp = 240, showBackground = true)
@Composable
private fun LoadingBaseDarkPreview() {
    MaterialExpressiveTheme(colorScheme = darkColorScheme()) {
        Surface { LoadingBase() }
    }
}
