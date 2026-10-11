package com.folderspan.ui.screen.file

import strings.AppStrings

import com.folderspan.data.file.FileProtocol

internal enum class EntryTypeFilter {
    All,
    FileOnly,
    FolderOnly
}

internal enum class RecentTimeFilter {
    All,
    Today,
    Last7Days,
    Last30Days
}

internal enum class FavoritePinFilter {
    All,
    Pinned,
    Unpinned
}

internal fun EntryTypeFilter.label(): String = when (this) {
    EntryTypeFilter.All -> AppStrings.ui_all_types
    EntryTypeFilter.FileOnly -> AppStrings.ui_files_only
    EntryTypeFilter.FolderOnly -> AppStrings.ui_folder_only
}

internal fun RecentTimeFilter.label(): String = when (this) {
    RecentTimeFilter.All -> AppStrings.ui_all_time
    RecentTimeFilter.Today -> AppStrings.ui_today
    RecentTimeFilter.Last7Days -> AppStrings.ui_last_7_days
    RecentTimeFilter.Last30Days -> AppStrings.ui_last_30_days
}

internal fun FavoritePinFilter.label(): String = when (this) {
    FavoritePinFilter.All -> AppStrings.ui_all
    FavoritePinFilter.Pinned -> AppStrings.ui_pin_it_top_only
    FavoritePinFilter.Unpinned -> AppStrings.ui_only_non_pinned
}

internal fun FileProtocol.filterLabel(): String = when (this) {
    FileProtocol.Local -> AppStrings.ui_local
    FileProtocol.Device -> AppStrings.ui_equipment
    FileProtocol.Share -> AppStrings.ui_share
    FileProtocol.Network -> AppStrings.ui_network
}

internal val supportedFilterProtocols = listOf(
    FileProtocol.Local,
    FileProtocol.Device,
    FileProtocol.Network,
)
