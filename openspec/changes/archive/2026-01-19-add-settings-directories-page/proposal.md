# Change: Add Settings page to view platform directories

## Why
Users need a simple way to see the app/system directory paths that the application is using (e.g. home, cache, data) for debugging, sharing logs, and troubleshooting.

## What Changes
- Add a secondary Settings page that lists available directory paths for the current platform.
- Add a multiplatform `expect/actual` directory provider that returns a list of directory items, each containing a title, description, and path string.
- Show only directories that are available on the current platform (platform-dependent list).
- Include common items such as system home, cache, and app data paths when they are retrievable on the current platform.

## Impact
- Affected specs: `view-system-directories-settings` (new)
- Affected code:
  - `composeApp/src/commonMain/kotlin/com/folderspan/ui/screen/settings/SettingsScreen.kt` (add entry)
  - `composeApp/src/commonMain/kotlin/com/folderspan/ui/screen/settings/` (new screen)
  - Platform source sets for directory provider `actual` implementations (Android/iOS/Desktop/Web as applicable)

## Non-Goals
- Editing directory paths or choosing a new directory.
- Adding “open in file manager” or “copy” actions (display-only for this change).
