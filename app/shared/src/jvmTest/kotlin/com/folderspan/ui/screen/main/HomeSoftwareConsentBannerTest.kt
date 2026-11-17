package com.folderspan.ui.screen.main

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.unit.dp
import com.folderspan.test.ChineseLocalizationTest
import com.folderspan.test.createInMemorySettings
import com.folderspan.ui.components.banner.MaterialBanner
import com.folderspan.ui.screen.onboarding.SoftwareConsentDialogHost
import com.folderspan.utils.SettingsUtils
import strings.AppStrings
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@OptIn(ExperimentalTestApi::class)
class HomeSoftwareConsentBannerTest : ChineseLocalizationTest() {
    @Test
    fun homeHintPersistsUntilExplicitAgreementAndOpensTheExistingDialog() = runComposeUiTest {
        SettingsUtils.init(createInMemorySettings())
        SettingsUtils.dismissSoftwareConsentRequest()
        setContent {
            val accepted by SettingsUtils.welcomeAgreementAccepted.collectAsState()
            MaterialTheme {
                Column(Modifier.width(360.dp)) {
                    HomeSoftwareConsentBanner(
                        softwareConsent = accepted,
                        onUnlock = { SettingsUtils.ensureSoftwareConsent() },
                        modifier = Modifier.testTag("home-unlock-banner"),
                    )
                    SoftwareConsentDialogHost()
                }
            }
        }
        onNodeWithText(AppStrings.home_unlock_features_title).assertIsDisplayed()
        onNodeWithText(AppStrings.home_unlock_features_action).assertIsDisplayed()
        onNodeWithText(AppStrings.ui_ignore).assertDoesNotExist()
        System.getenv("FOLDERSPAN_TEST_SCREENSHOTS")?.let { directory ->
            val bitmap = onNodeWithTag("home-unlock-banner").captureToImage()
            val pixels = IntArray(bitmap.width * bitmap.height)
            bitmap.readPixels(pixels)
            val image = java.awt.image.BufferedImage(bitmap.width, bitmap.height, java.awt.image.BufferedImage.TYPE_INT_ARGB)
            image.setRGB(0, 0, bitmap.width, bitmap.height, pixels, 0, bitmap.width)
            val file = java.io.File(directory, "home-unlock-banner.png")
            file.parentFile.mkdirs()
            javax.imageio.ImageIO.write(image, "png", file)
        }
        onNodeWithText(AppStrings.home_unlock_features_action).performClick()
        assertFalse(SettingsUtils.isWelcomeAgreementAccepted())
        assertTrue(SettingsUtils.softwareConsentRequested.value)
        onNodeWithText(AppStrings.welcome_local_continue).performClick()
        onNodeWithText(AppStrings.home_unlock_features_title).assertIsDisplayed()
        assertFalse(SettingsUtils.isWelcomeAgreementAccepted())
        onNodeWithText(AppStrings.home_unlock_features_action).performClick()
        onNode(isToggleable()).performClick()
        onAllNodesWithText(AppStrings.welcome_agree_unlock).filter(hasClickAction()).onFirst().performClick()
        assertTrue(SettingsUtils.isWelcomeAgreementAccepted())
        onNodeWithText(AppStrings.home_unlock_features_title).assertDoesNotExist()
    }

    @Test
    fun existingBannersKeepTheirDismissAction() = runComposeUiTest {
        var dismissals = 0
        setContent {
            MaterialTheme {
                MaterialBanner("通知", "内容", onActionClick = {}, onDismiss = { dismissals++ })
            }
        }
        onNodeWithText(AppStrings.ui_ignore).performClick()
        assertEquals(1, dismissals)
    }
}
