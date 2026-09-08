# Change: Sync Device Role Network Sync Settings

## Why

Pro users can already sync selected device settings through the settings API, but device connection configuration, roles, network drives, and sync tasks remain local-only. This makes a signed-in user recreate core operational configuration on every device.

## What Changes

- Extend Pro settings synchronization so devices, roles, network drives, and sync task definitions use `SettingApiService` for remote persistence.
- Add account/device-target snapshots for device connection configuration, role/permission configuration, persisted network drives, and sync task definitions.
- Encrypt sensitive credentials before they are serialized into remote setting values.
- Keep runtime-only data local, including active sockets, temporary tokens, sync run history, failure items, and logs.
- Preserve existing scalar settings, bookmark snapshot, and favorite snapshot synchronization.

## Capabilities

### New Capabilities

- `sync-pro-configuration-settings`: Covers remote synchronization for Pro configuration snapshots using the existing settings API.

### Modified Capabilities

- `manage-network-drives`: Persisted network drives can be synchronized through Pro settings sync.
- `manage-sync-tasks`: Sync task definitions can be synchronized through Pro settings sync while run history remains local.

## Impact

- Affected code:
  - Pro settings API repository and sync use cases under `shared/src/pro/kotlin/com/folderspan/pro/`
  - Device role, permission, network, and sync task state/store code under `shared/src/commonMain/kotlin/com/folderspan/`
  - App startup and local change-notification wiring in `composeApp/src/commonMain/kotlin/com/folderspan/App.kt`
- Affected data:
  - Remote settings entries add versioned JSON snapshot values for device, role, network, and sync task configuration.
  - Local SQLDelight device/role/network tables and the sync task store are updated from remote snapshots.
- No new remote endpoint is required. All network traffic uses the existing `SettingApiService`.
