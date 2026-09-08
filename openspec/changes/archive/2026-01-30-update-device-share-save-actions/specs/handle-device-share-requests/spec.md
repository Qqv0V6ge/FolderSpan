## ADDED Requirements
### Requirement: Device share actions
The application SHALL present distinct Save and View actions for device-share requests.

#### Scenario: Save action selected
- **WHEN** a device-share request is shown
- **AND** the user selects Save
- **THEN** the request is approved using the cached save path for the current share
- **AND** future device-share requests still require user approval

#### Scenario: View action selected
- **WHEN** a device-share request is shown
- **AND** the user selects View
- **THEN** the request is approved without creating or updating the device receive share cache entry

### Requirement: Device share detail guidance
The notification detail view SHALL describe the Save and View behaviors for device-share requests.

#### Scenario: Device share detail displayed
- **WHEN** a user opens a device-share notification detail
- **THEN** the detail text explains that Save uses the cached save path and View opens the share without auto-save

### Requirement: Save notifications
The application SHALL create a notification when Save or Auto-Save completes or fails.

#### Scenario: Save completes
- **WHEN** a user triggers Save for a device-share request
- **AND** the files are saved successfully
- **THEN** a success notification is created with the save path

#### Scenario: Auto-save fails
- **WHEN** auto-save is enabled for a device
- **AND** a save attempt fails
- **THEN** an error notification is created with the save path

### Requirement: View accept notifications
The application SHALL create a notification when a device-share view connection succeeds or fails.

#### Scenario: View connection succeeds
- **WHEN** a user selects View for a device-share request
- **AND** the connection is established
- **THEN** a success notification is created

#### Scenario: View connection fails
- **WHEN** a user selects View for a device-share request
- **AND** the connection is rejected or fails to connect
- **THEN** an error notification is created

### Requirement: Auto-receive cleanup
The application SHALL disconnect device-share sessions after Save or Auto-Save completes or encounters an error.

#### Scenario: Auto-receive completion
- **WHEN** files are saved via Save or Auto-Save
- **THEN** the share connection is disconnected after all transfers finish

#### Scenario: Auto-receive error
- **WHEN** a Save or Auto-Save transfer encounters a connection or transfer error
- **THEN** the share connection is disconnected
