## MODIFIED Requirements
### Requirement: WebRTC room section in the app drawer
The system SHALL display one or more saved `WebRTC` room entries in the app drawer, reuse the saved signaling server address(es) and local device ID, and let the user join, leave, or switch the active signaling room from the drawer.

#### Scenario: Join a saved room entry from the drawer
- **WHEN** the user opens the `WebRTC` drawer section and a saved room entry has complete configuration
- **THEN** the drawer shows that entry's current `Room ID` and `Server`
- **AND** the user can join that entry without opening the WebRTC settings page
- **AND** the drawer shows the current room connection status

#### Scenario: Switch to another saved room entry
- **WHEN** the user is already connected to one saved room entry and selects a different entry
- **THEN** the client disconnects the current room
- **AND** reconnects using the selected entry
- **AND** the drawer marks the selected entry as the active room

#### Scenario: Leave the active room entry
- **WHEN** the user is already connected to a saved room entry from the drawer
- **THEN** the drawer lets the user leave the active room
- **AND** the room member list is cleared after disconnect

### Requirement: Drawer room validation and member visibility
The system SHALL validate each saved drawer room entry, show pairing errors for multiple saved servers and room IDs, and display the online room devices returned by the signaling backend for the active room.

#### Scenario: Invalid room ID in saved entries
- **WHEN** any saved room entry contains a room ID that is not a base64url 256-bit value
- **THEN** the drawer shows a validation error
- **AND** the affected entry cannot be joined until the value is corrected

#### Scenario: Mismatched multi-entry pairing
- **WHEN** the saved signaling server list and room ID list both contain multiple values with different counts
- **THEN** the drawer shows a pairing error
- **AND** the user cannot join any room until the saved lists are aligned

#### Scenario: Room peers update
- **WHEN** the user joins a room that contains other online devices
- **THEN** the drawer lists each device with its identifier and current session status
- **AND** the list updates when peers join or leave the room

#### Scenario: No other devices in the room
- **WHEN** the user joins a room with no other online devices
- **THEN** the drawer shows an empty-state message instead of a peer list
