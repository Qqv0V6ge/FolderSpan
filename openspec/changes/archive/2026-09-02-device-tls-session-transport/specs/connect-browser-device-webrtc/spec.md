## MODIFIED Requirements

### Requirement: Browser device connect uses WebRTC
The system SHALL route WASM/JS device connections through the existing WebRTC path. Native apps SHALL NOT host browser WebRTC signaling on the device session port, and browsers SHALL NOT open `folderspan/1` sessions to that port.

#### Scenario: Native app hosts browser WebRTC automatically
- **WHEN** the native app starts the device session listener
- **THEN** the app does not expose `/api/webrtc/signaling/` on the device session port
- **AND** no new drawer widget is required

#### Scenario: WASM or JS connects from discovered device list
- **WHEN** the WASM or JS user selects a native device and presses connect
- **THEN** the client uses the existing WebRTC device path
- **AND** does not perform a TLS session handshake on the device session port

#### Scenario: Existing room UI remains unchanged
- **WHEN** the user opens the existing WebRTC room screens
- **THEN** those screens continue to use WebSocket room configuration and Room ID validation
