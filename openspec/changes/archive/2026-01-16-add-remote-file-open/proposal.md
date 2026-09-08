# Change: Add remote file open download flow

## Why
Remote file entries cannot be opened directly; users need a guided download flow before opening.

## What Changes
- Add a FileState openFile flow that opens local files immediately and routes remote files through a confirmation prompt when enabled.
- Add a remote download directory setting used for remote file opens.
- Add a settings section in `composeApp/src/commonMain/kotlin/com/folderspan/ui/screen/settings/FileShareSettingsScreen.kt` to manage the remote open confirmation preference and download directory.
- Persist the preferences via SettingsState/SettingsUtils.

## Impact
- Affected specs: open-remote-files, manage-remote-open-settings
- Affected code: FileScreen, FileState, settings screens/state, settings storage, bookmark PathSelectorDialog
