package com.folderspan.ui.screen.onboarding

import androidx.compose.foundation.layout.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.folderspan.test.ChineseLocalizationTest
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.v2.runComposeUiTest
import strings.AppStrings
import kotlin.test.*

@OptIn(ExperimentalTestApi::class)
class WelcomeAgreementInteractionTest : ChineseLocalizationTest() {
    @Test
    fun localActionWorksWithoutConsentAndDocumentsDoNotCheckTheBox() = runComposeUiTest {
        var localStarts = 0
        var unlocks = 0
        var documentRequests = 0
        setContent {
            var accepted by remember { mutableStateOf(false) }
            MaterialTheme {
                WelcomeAgreementBottomBar(
                    agreementAccepted = accepted,
                    onAgreementAcceptedChange = { accepted = it },
                    onDocumentRequested = { documentRequests++ },
                    onContinue = { unlocks++ },
                    onLocalContinue = { localStarts++ },
                )
            }
        }
        onNodeWithText(AppStrings.welcome_agree_unlock).assertIsNotEnabled()
        onNodeWithText(AppStrings.ui_privacy_policy).performClick()
        assertEquals(1, documentRequests)
        onNodeWithText(AppStrings.welcome_agree_unlock).assertIsNotEnabled()
        onNodeWithText(AppStrings.welcome_local_continue).performClick()
        assertEquals(1, localStarts)
        assertEquals(0, unlocks)
        onNode(isToggleable()).performClick()
        onNodeWithText(AppStrings.welcome_agree_unlock).assertIsEnabled().performClick()
        assertEquals(1, unlocks)
    }
    @Test fun unlockingRequiresExplicitCheckboxConfirmation() = runComposeUiTest {
        com.folderspan.utils.SettingsUtils.init(com.folderspan.test.createInMemorySettings())
        com.folderspan.utils.SettingsUtils.ensureSoftwareConsent()
        setContent { MaterialTheme { SoftwareConsentDialogHost() } }
        onAllNodesWithText(AppStrings.welcome_agree_unlock).filter(hasClickAction()).onFirst().assertIsNotEnabled()
        onNode(isToggleable()).performClick()
        onAllNodesWithText(AppStrings.welcome_agree_unlock).filter(hasClickAction()).onFirst().performClick()
        assertTrue(com.folderspan.utils.SettingsUtils.isWelcomeAgreementAccepted())
        assertFalse(com.folderspan.utils.SettingsUtils.softwareConsentRequested.value)
    }

    @Test fun compactWelcomeKeepsLocalActionVisible() = renderWelcome(360, 720)
    @Test fun wideWelcomeShowsFeatureAvailability() = renderWelcome(1000, 720)

    private fun renderWelcome(width: Int, height: Int) = runComposeUiTest {
        setContent {
            MaterialTheme {
                Column(Modifier.requiredSize(width.dp, height.dp).testTag("welcome-preview")) {
                    WelcomeAgreementContent(
                        layout = resolveWelcomeAgreementContentLayout(width.dp, height.dp),
                        onDocumentRequested = {},
                        modifier = Modifier.weight(1f),
                    )
                    WelcomeAgreementBottomBar(false, {}, {}, {}, {})
                }
            }
        }
        onNodeWithText(AppStrings.welcome_local_continue).assertIsDisplayed().assertIsEnabled()
        onNodeWithText(AppStrings.welcome_agree_unlock).assertIsDisplayed().assertIsNotEnabled()
        onNodeWithText(AppStrings.welcome_feature_available).assertExists()
        onAllNodesWithText(AppStrings.welcome_feature_requires_consent).assertCountEquals(3)
        System.getenv("FOLDERSPAN_TEST_SCREENSHOTS")?.let { directory ->
            val bitmap = onNodeWithTag("welcome-preview").captureToImage()
            val pixels = IntArray(bitmap.width * bitmap.height)
            bitmap.readPixels(pixels)
            val image = java.awt.image.BufferedImage(bitmap.width, bitmap.height, java.awt.image.BufferedImage.TYPE_INT_ARGB)
            image.setRGB(0, 0, bitmap.width, bitmap.height, pixels, 0, bitmap.width)
            val file = java.io.File(directory, "welcome-${width}.png")
            file.parentFile.mkdirs()
            javax.imageio.ImageIO.write(image, "png", file)
        }
    }

}
