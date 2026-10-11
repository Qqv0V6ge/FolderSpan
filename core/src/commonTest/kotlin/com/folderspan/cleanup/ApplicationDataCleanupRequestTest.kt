package com.folderspan.cleanup

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class ApplicationDataCleanupRequestTest {
    @Test
    fun cleanupRequestRoundTripsSelectedCategories() {
        val categories = linkedSetOf(
            ApplicationDataCleanupCategory.PreferencesAndAccount,
            ApplicationDataCleanupCategory.CacheAndLogs,
        )

        assertEquals(
            categories,
            decodeApplicationDataCleanupRequest(
                encodeApplicationDataCleanupRequest(categories)
            ),
        )
    }

    @Test
    fun platformCategoryValidationRejectsUnavailableSelection() {
        assertFailsWith<IllegalArgumentException> {
            requireAvailableApplicationDataCleanupCategories(
                categories = setOf(ApplicationDataCleanupCategory.LoginStartup),
                availableCategories = setOf(ApplicationDataCleanupCategory.ApplicationData),
            )
        }
    }
}
