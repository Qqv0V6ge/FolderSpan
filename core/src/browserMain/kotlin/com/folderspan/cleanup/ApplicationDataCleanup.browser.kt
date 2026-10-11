package com.folderspan.cleanup

import com.folderspan.createSettings
import com.folderspan.service.webui.platformResult

actual object ApplicationDataCleanup {
    actual val availableCategories = setOf(ApplicationDataCleanupCategory.PreferencesAndAccount)
    actual val isSupported = true
    actual suspend fun requestCleanup(categories: Set<ApplicationDataCleanupCategory>): Result<Unit> = platformResult {
        requireAvailableApplicationDataCleanupCategories(categories, availableCategories)
        val settings = createSettings()
        settings.keys.filter(::isFolderSpanPreferenceKey).forEach(settings::remove)
    }
    // Browser cleanup is immediate; only native platforms defer it until their next process start.
    actual fun completePendingCleanup(): Result<Boolean> = Result.success(false)
}
