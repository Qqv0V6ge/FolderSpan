## ADDED Requirements
### Requirement: WebRTC room section in the app drawer
The system SHALL display a dedicated `WebRTC` section in the app drawer that reuses the saved signaling server address and local device ID, and lets the user join or leave a signaling room from the drawer.

#### Scenario: Join a room from the drawer
- **WHEN** the user opens the `WebRTC` drawer section and the saved signaling configuration is complete
- **THEN** the drawer shows the current `Room ID`
- **AND** the user can join the room without opening the WebRTC settings page
- **AND** the drawer shows the current room connection status

#### Scenario: Leave a room from the drawer
- **WHEN** the user is already connected to a room from the drawer
- **THEN** the drawer lets the user leave the room
- **AND** the room member list is cleared after disconnect

### Requirement: Drawer room validation and member visibility
The system SHALL validate the drawer `Room ID`, allow generating a new room ID, and display the online room devices returned by the signaling backend.

#### Scenario: Invalid room ID
- **WHEN** the drawer `Room ID` is not a base64url 256-bit value
- **THEN** the drawer shows a validation error
- **AND** the user cannot join the room until the value is corrected

#### Scenario: Room peers update
- **WHEN** the user joins a room that contains other online devices
- **THEN** the drawer lists each device with its identifier and current session status
- **AND** the list updates when peers join or leave the room

#### Scenario: No other devices in the room
- **WHEN** the user joins a room with no other online devices
- **THEN** the drawer shows an empty-state message instead of a peer list
