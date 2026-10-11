package com.folderspan.ui.components.error

import strings.AppStrings

import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.Preview

@Preview
@Composable
private fun ErrorEmptyDataPreview() {
    ErrorEmptyData()
}

@Preview
@Composable
private fun ErrorConnectionPreview() {
    ErrorConnection()
}

@Preview
@Composable
private fun ErrorBlockPreview() {
    ErrorBlock(AppStrings.ui_permissions_restricted_cannot_accessed)
}

@Preview
@Composable
private fun ErrorBasePreview() {
    ErrorBase(AppStrings.ui_exception_occurred_please_try_again_later)
}
