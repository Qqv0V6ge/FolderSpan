## ADDED Requirements

### Requirement: Rescan devices on app resume
The app SHALL start a device scan when it becomes active again after the user leaves it (native: background → foreground; web: hidden → visible), unless a scan is already in progress (`DeviceState.loadingDevices == true`).

#### Scenario: Resume triggers scan when idle
- **WHEN** the app transitions from background to foreground
- **AND** a device scan is not currently running
- **THEN** the app starts a device scan using the current scan scope and port configuration

#### Scenario: Web tab visibility triggers scan when idle
- **WHEN** the browser tab transitions from hidden to visible
- **AND** a device scan is not currently running
- **THEN** the app starts a device scan using the current scan scope and port configuration

#### Scenario: Resume does not start a second scan
- **WHEN** the app transitions from background to foreground
- **AND** a device scan is currently running
- **THEN** the app does not start a second device scan
