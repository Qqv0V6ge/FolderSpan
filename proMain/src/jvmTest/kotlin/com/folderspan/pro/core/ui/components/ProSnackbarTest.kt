package com.folderspan.pro.core.ui.components

import strings.AppStrings

import com.folderspan.pro.test.ChineseLocalizationTest
import androidx.compose.material3.SnackbarDuration
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ProSnackbarTest : ChineseLocalizationTest() {
    @Test
    fun blankMessageDoesNotCreatePrompt() {
        assertNull(proSnackbarPrompt("   "))
    }

    @Test
    fun promptTrimsMessageAndKeepsTone() {
        val prompt = proSnackbarPrompt(
            message = AppStrings.ui_test_pro_snackbar_trimmed_saved_successfully_message,
            tone = AuthStatusTone.Success,
        )

        assertEquals(AppStrings.ui_test_pro_snackbar_saved_successfully_message, prompt?.message)
        assertEquals(AuthStatusTone.Success, prompt?.tone)
        assertEquals(SnackbarDuration.Short, prompt?.duration)
    }

    @Test
    fun errorAndActionPromptUseLongDurationByDefault() {
        assertEquals(
            SnackbarDuration.Long,
            proSnackbarPrompt(
                message = AppStrings.ui_save_failed,
                tone = AuthStatusTone.Error,
            )?.duration,
        )
        assertEquals(
            SnackbarDuration.Long,
            proSnackbarPrompt(
                message = AppStrings.ui_test_pro_snackbar_confirm_coverage,
                actionLabel = AppStrings.ui_confirm,
            )?.duration,
        )
    }
}
