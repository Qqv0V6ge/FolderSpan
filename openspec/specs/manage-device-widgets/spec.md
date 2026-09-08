# manage-device-widgets Specification

## Purpose
TBD - created by archiving change add-device-widgets. Update Purpose after archive.
## Requirements
### Requirement: Devices widget grid (Android + iOS)
The system SHALL provide a Devices widget on Android (Jetpack Glance) and iOS (WidgetKit) that renders the current scanned devices in a grid, grouped into connected and unconnected sections with counts.

#### Scenario: Widget shows grouped device grid
- **WHEN** the widget is displayed
- **THEN** it shows a connected section and an unconnected section
- **AND** each section label includes the current device count for that group
- **AND** devices in each group are displayed using a grid layout

### Requirement: Widget device tiles reflect connection state
The Devices widget SHALL apply a visual treatment to each device tile based on its connection state, including background color and icon emphasis.

#### Scenario: Status-colored tiles
- **WHEN** the widget renders a device tile
- **THEN** Connected devices use the app primary container colors
- **AND** Loading devices use the app tertiary container colors
- **AND** Failed or Rejected devices use the app error container colors
- **AND** New devices use the app secondary container colors
- **AND** Unconnected devices use the app surface variant colors

### Requirement: Widget uses tray-equivalent device interactions
The Devices widget SHALL provide interactions that execute the same logical flows as the desktop tray device menu.

#### Scenario: Scan devices from widget
- **WHEN** the user selects Scan in the widget
- **THEN** the app starts a device scan using the same network scope and port as the tray/AppDrawerDevice flow

#### Scenario: Unconnected device connect flow
- **WHEN** the user selects an unconnected device in the widget
- **THEN** the app initiates the same connect flow as the tray for that device

#### Scenario: Connected device open and disconnect
- **WHEN** the user selects a connected device open action in the widget
- **THEN** the app opens and selects the same device desk as the tray “打开” action
- **WHEN** the user selects a connected device disconnect action in the widget
- **THEN** the app performs the same disconnect flow as the tray “断开连接” action

#### Scenario: New device connect dialog
- **WHEN** the user selects a device in the widget whose connect state is New
- **THEN** the app opens and presents the same “connect new device” decision flow as the tray

#### Scenario: Cancel connecting
- **WHEN** the user selects a device in the widget whose connect state is Loading
- **THEN** the app cancels the connection attempt and returns the device to the unconnected state

### Requirement: Widget renders from a persisted snapshot
The Devices widget SHALL render from a persisted “widget device snapshot” and refresh when the snapshot changes.

#### Scenario: Widget renders without app process state
- **WHEN** the widget process is started without the app process running
- **THEN** the widget renders device state from the most recently persisted snapshot

#### Scenario: Widget refreshes after state change
- **WHEN** the app updates the snapshot after a scan/connect/disconnect action
- **THEN** the widget refreshes and reflects the updated device grouping and statuses

### Requirement: Widget theme matches app theme
The Devices widget SHALL render using the app's current color scheme and typography sizes, including custom seed colors and dynamic colors, and respect the app theme mode selection.

#### Scenario: Widget follows fixed light/dark theme
- **WHEN** the user selects Light or Dark theme mode in the app
- **THEN** the widget renders with the corresponding app color scheme regardless of system appearance

#### Scenario: Widget follows system theme mode
- **WHEN** the user selects System theme mode in the app
- **THEN** the widget renders with the app light or dark color scheme based on the system appearance

#### Scenario: Widget follows app typography sizes
- **WHEN** the app typography sizes change
- **THEN** the widget renders text using the same typography sizes as the app theme

