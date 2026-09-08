# Change: Update tray Easy Share menu

## Why
The tray currently exposes a single Easy Share action with no running state or quick controls. Users want the tray to reflect link-share status and provide direct access to addresses, authorization toggles, and device approvals without opening the full page.

## What Changes
- Show a tray Easy Share entry that reflects running vs not-running state.
- When running, expose submenu actions for opening the share page, listing addresses with open/copy actions, toggling authorization restrictions, and managing device approvals.
- Mirror the existing FileShareScreen link-share behavior for allow/reject to keep server-side state consistent, while using the share page's last confirmed file selection and hidden-file preference without showing a modal.
- Provide a tray action to stop the Easy Share service while running.

## Impact
- Affected specs: manage-tray-easy-share (new)
- Affected code: composeApp/src/jvmMain/kotlin/com/folderspan/ui/tray/FolderSpanTray.kt, shared/src/commonMain/kotlin/com/folderspan/ui/state/file/FileShareState.kt (reuse), composeApp/src/commonMain/kotlin/com/folderspan/ui/screen/file/share/FileShareScreen.kt (behavior reference)
