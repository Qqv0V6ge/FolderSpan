## ADDED Requirements
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
