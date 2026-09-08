## MODIFIED Requirements

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
