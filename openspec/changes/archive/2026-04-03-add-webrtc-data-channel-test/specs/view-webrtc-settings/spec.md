## ADDED Requirements
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
