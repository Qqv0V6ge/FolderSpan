package com.folderspan.ui.components.buttons

import strings.AppStrings

import com.folderspan.test.ChineseLocalizationTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TestConnectionButtonTest : ChineseLocalizationTest() {
    @Test
    fun contentStateUsesIdleTextWhenNotTesting() {
        val state = testConnectionButtonContentState(isTesting = false)

        assertEquals(AppStrings.ui_test_connection, state.text)
        assertFalse(state.showProgress)
    }

    @Test
    fun contentStateUsesTestingTextAndProgressWhenTesting() {
        val state = testConnectionButtonContentState(isTesting = true)

        assertEquals(AppStrings.ui_testing, state.text)
        assertTrue(state.showProgress)
    }
}
