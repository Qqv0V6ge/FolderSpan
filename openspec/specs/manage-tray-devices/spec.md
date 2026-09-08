# manage-tray-devices Specification

## Purpose
TBD - created by archiving change add-tray-device-menu. Update Purpose after archive.
## Requirements
### Requirement: Tray devices menu
The desktop tray menu SHALL include a Devices entry that displays the total number of known devices.

#### Scenario: Devices menu shows count
- **WHEN** the tray menu is opened
- **THEN** the Devices label includes the current total device count

### Requirement: Tray device lists and actions
The desktop tray menu SHALL provide scan, connected, and unconnected device actions that mirror AppDrawerDevice behavior.

#### Scenario: Scan devices from tray
- **WHEN** the user selects Scan Devices in the tray menu
- **THEN** the app starts a device scan using the same network scope and port as AppDrawerDevice

#### Scenario: Connected devices list
- **WHEN** the tray menu is opened
- **THEN** connected devices are listed separately from unconnected devices

#### Scenario: Unconnected device action
- **WHEN** the user selects an unconnected device entry in the tray menu
- **THEN** the app initiates the same connect flow as AppDrawerDevice

