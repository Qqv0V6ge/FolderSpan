package com.folderspan.pro.presentation.screen.sync

import com.folderspan.pro.domain.usecase.ManualSyncCategory
import com.folderspan.utils.SettingsUtils
import com.russhwolf.settings.Settings
import kotlinx.serialization.json.Json

internal fun Settings.readManualDataSyncSelectedCategories(): Set<ManualSyncCategory> {
    val raw = getStringOrNull(SettingsUtils.KEY_MANUAL_DATA_SYNC_SELECTED_CATEGORIES)
        ?: return ManualDataSyncCategoryItem.defaultItems().map { item -> item.category }.toSet()
    return runCatching {
        Json.decodeFromString<List<String>>(raw)
            .mapNotNull { name -> ManualSyncCategory.entries.firstOrNull { item -> item.name == name } }
            .toSet()
    }.getOrDefault(emptySet())
}

internal fun Settings.writeManualDataSyncSelectedCategories(categories: Set<ManualSyncCategory>) {
    putString(
        SettingsUtils.KEY_MANUAL_DATA_SYNC_SELECTED_CATEGORIES,
        Json.encodeToString(categories.map { item -> item.name }.sorted()),
    )
}
