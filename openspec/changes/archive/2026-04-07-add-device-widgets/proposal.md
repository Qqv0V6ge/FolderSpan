# Change: Add device widgets (Android Glance + iOS WidgetKit)

## Why
Desktop already supports tray-based device scan/connect actions. Android and iOS need a similar at-a-glance surface to view device status and quickly start the same device actions without navigating the full UI first.

## What Changes
- Add an Android home-screen widget using Jetpack Glance that renders devices in a grid, split into connected and unconnected sections, and provides Scan and per-device actions that mirror the desktop tray logic.
- Add an iOS widget using WidgetKit that renders the same grid. For broad iOS version support, widget interactions open the app and execute the same flows as the tray.
- Persist a lightweight “widget device snapshot” (device list + connection state + scanning flag) so widgets can render without relying on in-memory state.
- Add app-side intent/URL handlers that translate widget taps into the same scan/connect/disconnect/open/new-device flows used by the tray.
- Sync widget color and typography rendering with the app theme (custom seed, dynamic colors, and theme mode), across Android and iOS.
- Align widget device tiles with connection-state colors and refresh the widget header styling for clearer status grouping.

## Impact
- Affected specs: manage-device-widgets (new)
- Affected code:
  - Android: `composeApp/src/androidMain` (Glance widget + widget intent handling)
  - iOS: `iosApp/` (WidgetKit extension + open-url handling) and `composeApp/src/iosMain` (bridge handlers)
  - Shared: `shared/src/commonMain` (snapshot model + action mapping), `shared/src/commonMain/.../DeviceState.kt` (emit snapshot updates)
