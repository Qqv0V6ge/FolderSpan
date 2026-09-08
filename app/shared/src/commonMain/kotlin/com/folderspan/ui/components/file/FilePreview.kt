package com.folderspan.ui.components.file

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import com.folderspan.data.file.FileProtocol
import com.folderspan.data.file.FileSimpleInfo
import com.folderspan.data.main.DiskMenuPermission
import com.folderspan.db.FileFavorite

@Preview
@Composable
private fun FileCardPreview() {
    FileCard(
        file = previewFile(
            name = "report.pdf",
            description = "Monthly report",
            path = "/Documents/report.pdf",
            mineType = "application/pdf",
            size = 1_024L * 1_024L
        ),
        isSelected = true,
        isSelectionMode = true,
        onToggleSelect = {},
        onClick = {},
    )
}

@Preview
@Composable
private fun FileCardOperationStatusPreview() {
    Column {
        FileCard(
            file = previewFile(
                name = "copy-source.zip",
                path = "/Documents/copy-source.zip",
                mineType = "application/zip",
                size = 2_048L
            ),
            isSelected = true,
            isSelectionMode = true,
            operationStatus = FileItemOperationStatus.Copy,
            onToggleSelect = {},
            onClick = {},
        )

        FileCard(
            file = previewFile(
                name = "move-source.pdf",
                path = "/Documents/move-source.pdf",
                mineType = "application/pdf",
                size = 4_096L
            ),
            isSelected = true,
            isSelectionMode = true,
            operationStatus = FileItemOperationStatus.Move,
            onToggleSelect = {},
            onClick = {},
        )

        FileCard(
            file = previewFile(
                name = "delete-source.txt",
                path = "/Documents/delete-source.txt",
                mineType = "text/plain",
                size = 512L
            ),
            isSelected = true,
            isSelectionMode = true,
            operationStatus = FileItemOperationStatus.Delete,
            onToggleSelect = {},
            onClick = {},
        )
    }
}

@Preview
@Composable
private fun FileGridCardPreview() {
    FileGridCard(
        file = previewFile(
            name = "Pictures",
            path = "/storage/emulated/0/Pictures",
            isDirectory = true,
            size = 128L
        ),
        isSelected = false,
        isSelectionMode = false,
        modifier = Modifier
            .fillMaxWidth()
            .aspectRatio(1.08f),
        onToggleSelect = {},
        onClick = {},
    )
}

@Preview
@Composable
private fun FileFavoriteCardPreview() {
    FileFavoriteCard(
        favorite = previewFavorite(),
        onClick = {},
        onFixed = {},
        onRemove = {},
        isSelectionMode = true,
        isSelected = false
    )
}

@Preview
@Composable
private fun FileMenuPreview() {
    MaterialTheme {
        FileMenu(
            permission = DiskMenuPermission(
                read = true,
                write = true,
                paste = true,
                copy = true,
                move = true,
                delete = true,
                rename = true,
                setting = true,
                favorite = true,
                share = true,
                info = true,
            ),
        )
    }
}

@Preview
@Composable
private fun FileFavoriteCardMenuPreview() {
    FileFavoriteCardMenu(
        favorite = previewFavorite(),
        onFixed = {},
        onRemove = {}
    )
}

private fun previewFile(
    name: String,
    path: String,
    description: String = "",
    isDirectory: Boolean = false,
    mineType: String = "",
    size: Long = 0L,
): FileSimpleInfo {
    return FileSimpleInfo(
        name = name,
        description = description,
        isDirectory = isDirectory,
        isHidden = false,
        path = path,
        mineType = mineType,
        size = size,
        createdDate = 0,
        updatedDate = 0,
        protocol = FileProtocol.Local,
        protocolId = ""
    )
}

private fun previewFavorite(): FileFavorite {
    return FileFavorite(
        id = 1L,
        name = "Documents",
        isDirectory = true,
        isFixed = false,
        path = "/Documents",
        mineType = "",
        size = 0L,
        createdDate = 0L,
        updatedDate = 0L,
        protocol = FileProtocol.Local,
        protocolId = ""
    )
}
