# Change: Rescan devices after returning to the app

## Why
Device discovery can become stale after the user leaves the app (backgrounds it or switches away) and then returns. Automatically rescanning improves freshness without requiring a manual scan tap.

## What Changes
- Trigger a device scan when the app returns to the foreground (resume) on all supported platforms.
- Ignore the resume-triggered scan if a scan is already running (`DeviceState.loadingDevices`).
- Start the scan from a background dispatcher to avoid blocking UI interactions.

## Impact
- Affected specs: `refresh-device-scan` (new)
- Affected code: `composeApp/src/commonMain/kotlin/com/folderspan/App.kt` and existing scan entrypoints (e.g. `AppDrawerDevice`)

## Non-Goals
- Changing device scan scope/port logic (still uses current `getAllIPAddresses(...)/SettingsUtils.fileShare.getPort()` flow).
- Adding new device-management UX beyond triggering the existing scan.

## Open Questions
- Web targets (JS/Wasm) SHALL trigger scan on browser tab visibility changes (hidden → visible), consistent with native resume behavior.
