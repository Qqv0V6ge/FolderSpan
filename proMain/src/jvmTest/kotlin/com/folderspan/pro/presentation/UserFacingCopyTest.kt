package com.folderspan.pro.presentation

import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import strings.AppStrings

class UserFacingCopyTest {
    @Test
    fun marketplaceDoesNotShowInternalIdsOrRepeatPageTitle() {
        val source = proSource("presentation/screen/marketplace/MarketplacePage.kt")

        assertFalse(source.contains("\"pluginId:"), AppStrings.ui_test_user_facing_copy_plugin_cards_should_not_display_internal_plugin_numbers)
        assertFalse(source.contains("\"authorId:"), AppStrings.ui_test_user_facing_copy_plugin_cards_should_not_display_internal_author_ids)
        assertEquals(
            1,
            sourceReferenceCount(source, "AppStrings.ui_plug_market"),
            AppStrings.ui_test_user_facing_copy_the_content_of_the_page_should_not_be_repeated_and,
        )
    }

    @Test
    fun networkFallbackMessagesAreReadableForRegularUsers() {
        val source = proSource("data/remote/api/BaseApiService.kt")

        assertFalse(source.contains("Request failed"), AppStrings.ui_test_user_facing_copy_network_failure_text_should_not_use_english_technical)
        assertFalse(source.contains("Unknown error"), AppStrings.ui_test_user_facing_copy_unknown_failure_message_should_not_use_english)
    }

    private fun proSource(relativePath: String): String =
        Files.readString(Path.of("kotlin/com/folderspan/pro").resolve(relativePath))

    private fun sourceReferenceCount(source: String, reference: String): Int =
        Regex("\\b${Regex.escape(reference)}\\b").findAll(source).count()
}
