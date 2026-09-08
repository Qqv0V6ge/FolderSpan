# handle-device-share-requests Specification
## Purpose
TBD - created by archiving change update-device-share-save-actions. Update Purpose after archive.

## Requirements

### Requirement: Device share actions
The application SHALL present distinct Save and View actions for device-share requests.

#### Scenario: Save action selected
- **WHEN** a device-share request is shown
- **AND** the user selects Save
- **THEN** the request is approved using the cached save path for the current share
- **AND** the receiver copies the shared files through the device-to-local pipeline
- **AND** future device-share requests still require user approval

#### Scenario: View action selected
- **WHEN** a device-share request is shown
- **AND** the user selects View
- **THEN** the request is approved without creating or updating the device receive share cache entry
- **AND** the receiver opens the sender as a device desk limited to the shared list

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
The application SHALL disconnect the device-share device connection after Save or Auto-Save completes or encounters an error.

#### Scenario: Auto-receive completion
- **WHEN** files are saved via Save or Auto-Save
- **THEN** the device connection created for that share is disconnected after all transfers finish

#### Scenario: Auto-receive error
- **WHEN** a Save or Auto-Save transfer encounters a connection or transfer error
- **THEN** the device connection created for that share is disconnected

### Requirement: Share save transfers SHALL use bounded high-speed chunk reads
Save and Auto-Save file transfers from native App device shares SHALL copy files through the same device copy pipeline used for device-to-local copies. They SHALL NOT download file bytes through `/api/share/read-bytes`.

#### Scenario: Save large shared file
- **WHEN** a user saves a large shared file from a native App device share
- **THEN** the client copies it through the device Session or WebRTC file stream
- **AND** in-flight data is bounded by the device transport window
- **AND** the file is written locally without buffering the whole file in memory

#### Scenario: Save small shared file
- **WHEN** a user saves a shared file that is small
- **THEN** the client copies it through the same device copy pipeline
- **AND** it SHALL NOT open an HTTP share byte route for that file

#### Scenario: Save shared directory with bounded concurrency
- **WHEN** a user saves a shared directory
- **THEN** the client copies files through the device directory copy queue
- **AND** concurrency is bounded by the device transport limits
