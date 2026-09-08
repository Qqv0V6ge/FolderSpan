# view-webrtc-settings Specification

## Purpose
TBD - created by archiving change add-webrtc-data-channel-test. Update Purpose after archive.
## Requirements
### Requirement: WebRTC test entry in Settings
The system SHALL display a WebRTC test entry in Settings that opens a secondary WebRTC test page.

#### Scenario: Open WebRTC test page
- **WHEN** the user selects the WebRTC test entry in Settings
- **THEN** the WebRTC test page is opened

### Requirement: Multi-peer controls on the WebRTC test page
The WebRTC test page SHALL display available room peers with per-peer session status, per-peer connect/disconnect actions, and an active transfer target selector.

#### Scenario: Manage room peers from the test page
- **WHEN** the user joins a room that contains online peers
- **THEN** the page shows each peer together with its current session status
- **AND** the user can connect to or disconnect from an individual peer without leaving the room
- **AND** the user can mark a connected peer as the active transfer target

### Requirement: Multi-entry WebRTC room configuration
The WebRTC settings page SHALL accept multiple signaling server URLs or multiple room IDs in the existing configuration fields, using delimiter-separated values, and SHALL validate how those values are paired for the drawer.

#### Scenario: Configure multiple room IDs for one server
- **WHEN** the user saves multiple room IDs while keeping a single WebSocket address
- **THEN** the settings page accepts the value
- **AND** the drawer exposes one room entry per room ID

#### Scenario: Configure ordered server-room pairs
- **WHEN** the user saves multiple WebSocket addresses and multiple room IDs with matching counts
- **THEN** the settings page treats them as ordered pairs
- **AND** the drawer exposes one room entry per pair

#### Scenario: Invalid paired list counts
- **WHEN** the user saves multiple WebSocket addresses and multiple room IDs with different counts
- **THEN** the settings page shows a validation error
- **AND** the configuration is not joinable from the drawer

#### Scenario: Append a generated room ID
- **WHEN** the user taps the room ID generation action while room IDs already exist
- **THEN** the settings page appends a newly generated room ID to the saved list instead of replacing the existing values

