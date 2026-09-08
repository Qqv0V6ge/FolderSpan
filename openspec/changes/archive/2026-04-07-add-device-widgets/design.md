# Design: Device widgets (Android Glance + iOS WidgetKit)

## Goals
- Provide a device widget on Android and iOS that mirrors the desktop tray device behavior:
  - Scan action
  - Connected vs unconnected device grouping
  - Per-device interactions consistent with tray/AppDrawerDevice connect logic
- Keep compatibility broad (Android API 24+, iOS 14+) by executing actions in the app after opening it.
- Keep implementation minimal: widgets are read-only renderers of a persisted snapshot + tap entry points into the existing flows.

## Non-goals
- Fully interactive widgets that perform network scan/connect without opening the app (iOS 17 interactive widgets can be a follow-up).
- Adding new device-management UX beyond what the tray/AppDrawerDevice already exposes.

## Architecture

### Data flow (rendering)
1. The app maintains live device state in `DeviceState` (`loadingDevices`, `socketDevices`).
2. The app writes a “widget device snapshot” whenever relevant state changes.
3. Widgets read the latest snapshot and render:
   - Header: Scan + counts
   - Sections: Connected / Unconnected
   - Grid tiles: device name + icon + status badge

**Why snapshot?** Widgets run out-of-process (iOS extension; Android launcher), so they cannot rely on Compose/Koin in-memory state.

### Action flow (interaction)
For broad compatibility, widget actions **open the app** and request an action. The app executes the same logic as the tray:
- `scan`: trigger `DeviceState.scanner(...)`
- `open` (connected device): open app and select device desk (same as tray “打开”)
- `disconnect` (connected device): disconnect + update connect state (same as tray “断开连接”)
- `connect` (unconnected device): set `Loading` and call connect (same as tray click)
- `cancel` (loading): disconnect and revert to `UnConnect`
- `new` (ConnectType.New): open app and show the same “connect new device” dialog used by tray

Android uses intent extras; iOS uses `widgetURL` deep-links. Both map to a shared Kotlin handler so the behavior stays identical.

## Widget device snapshot

### Payload
- `updatedAtEpochMs`: last write time (helps decide freshness)
- `isScanning`: mirrors `DeviceState.loadingDevices`
- `devices`: the last known `socketDevices` list, serialized into platform-neutral fields:
  - `id`, `name`
  - `type` (string; e.g. `JVM`, `IOS`, `Android`, `JS`)
  - `connectType` (string; `Connect`, `UnConnect`, `Fail`, `Loading`, `New`, `Rejected`)
  - `host`, `port` (for action routing; supports opening the app and resolving the target device)

### Storage
- **Android**: app-private storage accessible to both app and widget (e.g., `SharedPreferences` or `DataStore`).
- **iOS**: App Group `UserDefaults` so the host app and WidgetKit extension can share the same snapshot.
  - The App Group identifier is configured in Xcode; the widget and app targets must share it.

### Refresh
- When the app writes a new snapshot, it requests a widget refresh:
  - Android: update the Glance widget(s).
  - iOS: `WidgetCenter.shared.reloadTimelines(ofKind:)`.

## UI (grid)
- The widget renders devices in a grid (responsive column count per widget size).
- If space is limited, it shows the first N devices per section and a “More / Open App” affordance to open the device screen.
- Device tiles show:
  - icon (type)
  - name
  - status label/badge for non-connected states (e.g., “连接中”, “连接失败”, “新”)

## Version compatibility
- Android: Jetpack Glance supported on minSdk 24.
- iOS: WidgetKit supported on iOS 14+. Interactions open the app via URL on all supported versions.

## Risks / trade-offs
- Snapshot can be stale if the app hasn’t run recently; mitigate by showing `updatedAt` age text or an empty-state prompt.
- iOS requires App Group entitlement and a widget extension target; this changes Xcode project structure.

