package com.folderspan.pro.presentation.component

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.UriHandler
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.v2.runComposeUiTest
import strings.AppStrings
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@OptIn(ExperimentalTestApi::class)
class FeatureAgreementRowTest {
    @Test
    fun clickingAgreementContentTogglesSelectionBothWays() = runComposeUiTest {
        var checked by mutableStateOf(false)

        setContent {
            MaterialTheme {
                FeatureAgreementRow(
                    checked = checked,
                    onCheckedChange = { checked = it },
                    enabled = true,
                )
            }
        }

        onNodeWithText(AppStrings.ui_i_have_read_agree_following_terms).performClick()
        runOnIdle { assertTrue(checked) }

        onNodeWithText(AppStrings.ui_i_have_read_agree_following_terms).performClick()
        runOnIdle { assertFalse(checked) }
    }

    @Test
    fun clickingLegalLinksOpensTheirBrowserUrlsWithoutChangingSelection() = runComposeUiTest {
        val openedUrls = mutableListOf<String>()
        val uriHandler = object : UriHandler {
            override fun openUri(uri: String) {
                openedUrls += uri
            }
        }
        var checked by mutableStateOf(false)

        setContent {
            MaterialTheme {
                CompositionLocalProvider(LocalUriHandler provides uriHandler) {
                    FeatureAgreementRow(
                        checked = checked,
                        onCheckedChange = { checked = it },
                        enabled = true,
                    )
                }
            }
        }

        onNodeWithText(AppStrings.ui_service_agreement_link_label).performClick()
        onNodeWithText(AppStrings.ui_privacy_policy_link_label).performClick()

        runOnIdle {
            assertEquals(
                listOf(FOLDERSPAN_USER_AGREEMENT_URL, FOLDERSPAN_PRIVACY_POLICY_URL),
                openedUrls,
            )
            assertFalse(checked)
        }
    }
}
