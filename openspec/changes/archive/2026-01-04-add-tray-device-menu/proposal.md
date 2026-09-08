# Change: Add device submenu to desktop tray

## Why
The desktop tray currently exposes only quick share and exit. Adding device actions in the tray provides faster access to scanning and connection management.

## What Changes
- Add a Devices tray menu that shows the total device count.
- Add tray actions for scanning devices and listing connected/unconnected devices.
- Mirror AppDrawerDevice behaviors for connect, disconnect, and selection.

## Impact
- Affected specs: manage-tray-devices (new)
- Affected code: composeApp/src/jvmMain/kotlin/com/folderspan/main.kt
