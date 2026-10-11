package com.folderspan.ui.screen.onboarding

import strings.AppStrings

import androidx.compose.ui.unit.dp
import com.folderspan.test.ChineseLocalizationTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class WelcomeAgreementScreenTest : ChineseLocalizationTest() {
    @Test
    fun welcomeAgreementIntroducesFolderSpanCapabilities() {
        assertEquals(AppStrings.ui_welcome_folderspan, welcomeAgreementTitle())
        assertEquals(
            listOf(AppStrings.ui_local_file_management, AppStrings.ui_quick_link_sharing, AppStrings.ui_transmission_between_devices_same_network, AppStrings.ui_remote_connection_network_disk),
            welcomeAgreementFeatures.map { item -> item.title }
        )
        assertTrue(
            welcomeAgreementFeatures.any { item ->
                item.description.contains("WebDAV") &&
                        item.description.contains("S3") &&
                        item.description.contains("SMB")
            }
        )
    }

    @Test
    fun agreementDocumentsMatchFolderSpanDataAndNetworkBehavior() {
        val userAgreement = welcomeAgreementDocument(WelcomeAgreementDocumentType.UserAgreement)
        val privacyPolicy = welcomeAgreementDocument(WelcomeAgreementDocumentType.PrivacyPolicy)
        val combinedText = (userAgreement.paragraphs + privacyPolicy.paragraphs).joinToString()

        assertEquals(AppStrings.ui_user_agreement, userAgreement.title)
        assertEquals(AppStrings.ui_privacy_policy, privacyPolicy.title)
        assertEquals(15, userAgreement.paragraphs.size)
        assertEquals(20, privacyPolicy.paragraphs.size)
        assertTrue(userAgreement.paragraphs.contains(AppStrings.agreement_user_instructions_and_automation))
        assertTrue(userAgreement.paragraphs.contains(AppStrings.agreement_user_remote_services))
        assertTrue(privacyPolicy.paragraphs.contains(AppStrings.agreement_privacy_mcp))
        assertTrue(privacyPolicy.paragraphs.contains(AppStrings.agreement_privacy_logs))
        assertTrue(combinedText.contains(AppStrings.ui_test_welcome_agreement_screen_local_file))
        assertTrue(combinedText.contains(AppStrings.ui_test_welcome_agreement_screen_local_network))
        assertTrue(combinedText.contains("FTP"))
        assertTrue(combinedText.contains("WebRTC"))
        assertTrue(combinedText.contains("MCP"))
        assertTrue(combinedText.contains(AppStrings.ui_test_welcome_agreement_screen_active))
        assertTrue(combinedText.contains(AppStrings.ui_test_welcome_agreement_screen_do_not_automatically_upload))
        assertFalse(combinedText.contains("proMain"))
    }

    @Test
    fun welcomeAgreementLayoutUsesRowOnlyForWideScreensAndLimitsWidth() {
        assertEquals(
            WelcomeAgreementContentLayout.Row,
            resolveWelcomeAgreementContentLayout(width = 1080.dp, height = 720.dp)
        )
        assertEquals(
            WelcomeAgreementContentLayout.Column,
            resolveWelcomeAgreementContentLayout(width = 720.dp, height = 1080.dp)
        )
        assertEquals(1120.dp, welcomeAgreementContentMaxWidth(WelcomeAgreementContentLayout.Row))
        assertEquals(720.dp, welcomeAgreementContentMaxWidth(WelcomeAgreementContentLayout.Column))
        assertEquals(
            WelcomeAgreementContentPlacement.Center,
            welcomeAgreementContentPlacement(WelcomeAgreementContentLayout.Row)
        )
        assertEquals(
            WelcomeAgreementContentPlacement.Top,
            welcomeAgreementContentPlacement(WelcomeAgreementContentLayout.Column)
        )
    }

    @Test
    fun welcomeAgreementContentAlwaysAllowsScrollAndRequiresConsentBeforeContinuing() {
        assertTrue(shouldScrollWelcomeAgreementContent())
        assertFalse(canContinueWelcomeAgreement(agreementAccepted = false))
        assertTrue(canContinueWelcomeAgreement(agreementAccepted = true))
    }
}
