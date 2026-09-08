## MODIFIED Requirements

### Requirement: Browser device connect uses WebRTC
The system SHALL use the existing device scan/connect flow for WASM/JS clients, route browser-device connections through WebRTC over HTTP signaling, and establish a unified Device Session on the approved peer connection before exposing remote device capabilities.

#### Scenario: Native app hosts browser WebRTC automatically
- **WHEN** the native app starts the device HTTP service
- **THEN** the app joins the HTTP signaling hub as host
- **AND** accepts approved browser peers through WebRTC-backed Device Sessions without a new drawer widget

#### Scenario: WASM or JS connects from discovered device list
- **WHEN** the WASM or JS user scans or manually adds an app HTTP device and presses connect
- **THEN** the client joins the HTTP signaling hub as a browser peer
- **AND** sends the WebRTC connection request to the app host
- **AND** reports the device connected only after the Device Session is ready

#### Scenario: Browser accesses remote capabilities
- **WHEN** a WASM or JS peer has established a WebRTC-backed Device Session
- **THEN** it uses the common device clients for authorized files, paths, bookmarks and messages
- **AND** behavior matches the same capability over TLS Session

#### Scenario: Existing room UI remains unchanged
- **WHEN** the user opens the existing WebRTC room screens
- **THEN** those screens continue to use WebSocket room configuration and Room ID validation
