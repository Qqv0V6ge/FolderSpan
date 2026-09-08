## Why

Easy Share settings currently persist changes, but several options do not make their runtime effect clear. Users need explicit feedback when an app restart is required, when a running service should be restarted to apply a new port, and when default connection options only affect future connections.

## What Changes

- Show a Snackbar whenever "自动启动" is turned on or off, explaining that restarting the app is required for the change to take effect.
- When the service port is changed while Easy Share is running, show a Snackbar indicating the service is running and offer a restart action.
- When the user clicks the restart action, restart the Easy Share service so the new port is applied.
- Clarify and enforce that changes to 默认分享路径、默认自动允许、默认同设备自动允许、默认密码访问 only affect newly created connections or sessions, not already connected clients.

## Capabilities

### New Capabilities

- None.

### Modified Capabilities

- `manage-easy-file-share-settings`: Extend Easy Share settings behavior for runtime feedback, service restart handling, and default-option effect scope.

## Impact

- Affected code: `composeApp/src/commonMain/kotlin/com/folderspan/ui/screen/settings/EasyFileShareSettingsScreen.kt`
- Potential affected state/service layer: Easy Share runtime status, service stop/start or restart API, settings state persistence
- UI impact: Material3 Snackbar messages and optional restart action from the Easy Share settings page
