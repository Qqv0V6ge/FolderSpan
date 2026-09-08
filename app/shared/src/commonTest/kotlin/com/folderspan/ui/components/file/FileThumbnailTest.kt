package com.folderspan.ui.components.file

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.unit.dp
import com.folderspan.data.file.FileSimpleInfo
import com.folderspan.ui.thumbnail.FileThumbnailLoader
import kotlinx.coroutines.awaitCancellation
import kotlin.test.Test
import kotlin.test.assertEquals

@OptIn(ExperimentalTestApi::class)
class FileThumbnailTest {
    @Test
    fun loadingKeepsExistingIconFallbackVisible() = runComposeUiTest {
        val loader = loader { _, _ -> awaitCancellation() }
        setContent {
            MaterialTheme {
                FileThumbnail(
                    file = imageFile(),
                    loader = loader,
                    enabled = true,
                    targetSize = 40.dp,
                    modifier = Modifier.testTag("thumbnail-image"),
                ) {
                    Text("fallback", Modifier.testTag("thumbnail-fallback"))
                }
            }
        }

        onNodeWithTag("thumbnail-fallback").assertIsDisplayed()
        onNodeWithTag("thumbnail-image").assertDoesNotExist()
    }

    @Test
    fun successReplacesFallbackAndFailureKeepsIt() = runComposeUiTest {
        val successLoader = loader { _, _ -> ImageBitmap(32, 24) }
        setContent {
            MaterialTheme {
                FileThumbnail(
                    file = imageFile(),
                    loader = successLoader,
                    enabled = true,
                    targetSize = 40.dp,
                    modifier = Modifier.testTag("thumbnail-image"),
                ) {
                    Text("fallback", Modifier.testTag("thumbnail-fallback"))
                }
            }
        }
        waitForIdle()
        onNodeWithTag("thumbnail-image").assertIsDisplayed()
        onNodeWithTag("thumbnail-fallback").assertDoesNotExist()

        val failureLoader = loader { _, _ -> null }
        setContent {
            MaterialTheme {
                FileThumbnail(
                    file = imageFile(),
                    loader = failureLoader,
                    enabled = true,
                    targetSize = 40.dp,
                ) {
                    Text("fallback", Modifier.testTag("thumbnail-fallback"))
                }
            }
        }
        waitForIdle()
        onNodeWithTag("thumbnail-fallback").assertIsDisplayed()
    }

    @Test
    fun selectionControlsTakePriorityAndDoNotStartThumbnailLoad() = runComposeUiTest {
        var loadCount = 0
        val loader = loader { _, _ ->
            loadCount += 1
            ImageBitmap(32, 24)
        }
        setContent {
            MaterialTheme {
                FileCard(
                    file = imageFile(),
                    isSelected = false,
                    isSelectionMode = true,
                    thumbnailLoader = loader,
                    loadThumbnail = true,
                    onToggleSelect = {},
                    onClick = {},
                )
            }
        }

        runOnIdle { assertEquals(0, loadCount) }
    }
}

private fun loader(
    load: suspend (FileSimpleInfo, Int) -> ImageBitmap?,
): FileThumbnailLoader = object : FileThumbnailLoader {
    override suspend fun load(file: FileSimpleInfo, targetSizePx: Int): ImageBitmap? =
        load(file, targetSizePx)

    override fun close() = Unit
}

private fun imageFile(): FileSimpleInfo = FileSimpleInfo(
    name = "photo.jpg",
    isDirectory = false,
    isHidden = false,
    path = "/photo.jpg",
    mineType = "image/jpeg",
    size = 3,
    createdDate = 1,
    updatedDate = 2,
)
