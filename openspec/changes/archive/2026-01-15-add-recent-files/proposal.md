# Change: Add recent files in drawer

## Why
Users want fast access to recently opened files and folders from FileScreen. Recent entries must persist locally and include non-local items.

## What Changes
- Add a local database table to store recent file/folder entries, de-duplicated by path + protocol + protocolId and capped at 300 entries.
- Record FileScreen clicks (files and folders) into the recent table for local and non-local protocols.
- Add a Recent screen (similar to FavoriteScreen) and a drawer entry above Favorites to open it.
- Support multi-select in the Recent screen to remove multiple recent entries at once.

## Impact
- Affected specs: track-recent-files
- Affected code: `composeApp/src/commonMain/kotlin/com/folderspan/ui/components/drawer/AppDrawerBookmark.kt`, `composeApp/src/commonMain/kotlin/com/folderspan/ui/screen/file/FileScreen.kt`, shared SQLDelight schema/state.
