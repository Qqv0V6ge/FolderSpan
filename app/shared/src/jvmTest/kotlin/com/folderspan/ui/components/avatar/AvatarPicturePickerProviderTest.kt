package com.folderspan.ui.components.avatar

import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.v2.runComposeUiTest
import com.folderspan.data.file.FileSimpleInfo
import com.folderspan.pro.presentation.screen.profile.AvatarPictureOutcome
import com.folderspan.pro.presentation.screen.profile.LocalAvatarPicturePicker
import com.folderspan.ui.components.file.FileSelectorConstraintResult
import java.nio.file.Files
import kotlinx.coroutines.test.runTest
import strings.AppStrings
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalTestApi::class)
class AvatarPicturePickerProviderTest {
    @Test
    fun selectionCancellationReturnsCancelledOutcome() = runComposeUiTest {
        var outcome: AvatarPictureOutcome? = null
        setContent {
            MaterialTheme {
                AvatarPicturePickerProvider {
                    val picker = LocalAvatarPicturePicker.current
                    Button(onClick = { picker?.request(2_000_000) { outcome = it } }) {
                        Text("open-avatar-picker")
                    }
                }
            }
        }

        onNodeWithText("open-avatar-picker").performClick()
        onNodeWithText(AppStrings.ui_profile_avatar_choose).assertIsDisplayed()
        onNodeWithText(AppStrings.ui_cancel).performClick()
        runOnIdle { assertIs<AvatarPictureOutcome.Cancelled>(outcome) }
    }

    @Test
    fun supersedingRequestCancelsTheEarlierCallback() = runComposeUiTest {
        val outcomes = mutableListOf<AvatarPictureOutcome>()
        setContent {
            MaterialTheme {
                AvatarPicturePickerProvider {
                    val picker = LocalAvatarPicturePicker.current
                    Button(
                        onClick = {
                            picker?.request(2_000_000, outcomes::add)
                            picker?.request(2_000_000) {}
                        },
                    ) { Text("replace-avatar-request") }
                }
            }
        }

        onNodeWithText("replace-avatar-request").performClick()
        runOnIdle {
            assertEquals(1, outcomes.size)
            assertIs<AvatarPictureOutcome.Cancelled>(outcomes.single())
        }
    }

    @Test
    fun sourceLimitIsIndependentFromSmallerUploadLimitAndSelectionIsSingular() {
        val uploadLimit = 1L * 1024L * 1024L
        val selectable = file("photo.jpg", uploadLimit + 1)
        assertEquals(
            FileSelectorConstraintResult.Allowed,
            AvatarSourceSelectionConstraints.evaluate(selectable),
        )
        assertEquals(selectable, selectedAvatarSource(listOf(selectable)))
        assertNull(selectedAvatarSource(listOf(selectable, file("other.png", 10))))
    }

    @Test
    fun selectedFileBytesAreReadWithoutChangingTheirContents() = runTest {
        val path = Files.createTempFile("avatar-source-", ".png")
        val bytes = ByteArray(300_000) { index -> (index % 251).toByte() }
        try {
            Files.write(path, bytes)
            val read = readAvatarSource(
                file(path.fileName.toString(), bytes.size.toLong(), path.toString()),
            ).getOrThrow()
            assertContentEquals(bytes, read)
        } finally {
            Files.deleteIfExists(path)
        }
    }

    private fun file(name: String, size: Long, path: String = "/tmp/$name") = FileSimpleInfo(
        name = name,
        isDirectory = false,
        isHidden = false,
        path = path,
        mineType = "image",
        size = size,
        createdDate = 0,
        updatedDate = 0,
    )
}
