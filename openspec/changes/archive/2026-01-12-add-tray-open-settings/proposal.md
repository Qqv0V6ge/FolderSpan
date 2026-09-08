# Change: Add tray Settings action

## Why
The desktop tray already provides quick access to device and Easy Share actions, but opening the Settings page requires the app window to be visible first. Adding a Settings action to the tray makes configuration accessible even when the window is hidden.

## What Changes
- Add a Settings tray action.
- Selecting the action shows the application window and opens the Settings page.

## Impact
- Affected specs: manage-tray-settings (new)
- Affected code: composeApp/src/jvmMain/kotlin/com/folderspan/ui/tray/FolderSpanTray.kt
